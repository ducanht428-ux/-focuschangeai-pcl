// Image quality for the two consumers that read the camera: the YOLO person detector and the face
// measurements behind the indices (SEI, blink, PERCLOS, gaze, distance).
//
// Pure module - no DOM, no browser API - so the Node suite can replay it on synthetic pixels.
//
// What is here and why (each item answers a gap found in the code, not a guess):
//
//   1. liftForDetector: YOLO used to read the raw video frame while the face stage read the lifted one,
//      so in a dim classroom the landmark model saw a usable face and the person detector saw a dark
//      blob. This lifts the 640x640 tensor image by a gamma curve that is chosen from the image itself,
//      is a no-op in normal light, and never touches the letterbox padding.
//   2. downscaleSteps: a 1280 px or 1920 px camera frame squeezed to 640 in ONE drawImage is aliased
//      (the browser's bilinear tap skips pixels). Halving step by step keeps thin features - a pencil, a
//      phone edge, a strap - that the detector needs.
//   3. SharpnessTracker: a blurry face (focus hunting, motion, a smeared lens) makes the lid landmarks
//      wander and the blink / PERCLOS / gaze numbers wander with them, and nothing in the quality score
//      noticed. The score here is RELATIVE to the same camera's own recent best, because an absolute
//      Laplacian threshold would depend on the camera, the room and the face size, and there is no
//      calibration set in this build to set one honestly.

const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);

// ---------------------------------------------------------------------------
// 1. Low-light lift for the YOLO tensor image
// ---------------------------------------------------------------------------
export const LIFT_BELOW_MEAN = 85;   // gentle band starts here, same value as enhance.mjs ON_MEAN
export const LIFT_TARGET_MEAN = 100; // where the lifted picture is aimed
export const LIFT_MAX_GAIN = 3.2;    // never amplify a near-black frame into pure noise
export const LIFT_FLOOR_MEAN = 6;    // below this there is no picture to lift, only sensor noise

/**
 * Mean BT.601 luma of the picture area of a letterboxed RGBA square, padding rows/columns excluded.
 * Sampled on a stride, because this runs once per YOLO pass and the pass itself costs about a second.
 */
export function pictureMean(rgba, size, plan, step = 4) {
  const x0 = plan ? plan.padX : 0;
  const y0 = plan ? plan.padY : 0;
  const x1 = plan ? plan.padX + plan.nw : size;
  const y1 = plan ? plan.padY + plan.nh : size;
  let n = 0;
  let sum = 0;
  for (let y = y0; y < y1; y += step) {
    for (let x = x0; x < x1; x += step) {
      const i = (y * size + x) * 4;
      sum += 0.299 * rgba[i] + 0.587 * rgba[i + 1] + 0.114 * rgba[i + 2];
      n++;
    }
  }
  return n ? sum / n : 128;
}

/**
 * The gamma that moves `mean` to `target` on a 0..255 curve: (mean/255)^gamma = target/255.
 * Returns 1 (no change) outside the band, and caps the resulting gain at the dark end.
 */
export function liftGamma(mean, target = LIFT_TARGET_MEAN, maxGain = LIFT_MAX_GAIN) {
  if (!(mean >= LIFT_FLOOR_MEAN) || mean >= LIFT_BELOW_MEAN) return 1;
  let g = Math.log(target / 255) / Math.log(mean / 255);
  // Cap by the slope at the dark end, which is what amplifies noise: d/dv of 255*(v/255)^g at v=mean.
  const gainAt = (gg) => Math.pow(mean / 255, gg - 1) * gg;
  while (g < 1 && gainAt(g) > maxGain && g < 1) g += 0.02;
  return clamp(g, 0.35, 1);
}

/**
 * Lift the picture area of a letterboxed RGBA square in place. Returns the gamma used (1 = untouched).
 * The same gamma goes on R, G and B, so colour ratios - and with them skin - stay where they were; the
 * padding grey is left alone because it is not part of the scene.
 */
export function liftForDetector(rgba, size, plan) {
  const mean = pictureMean(rgba, size, plan);
  const g = liftGamma(mean);
  if (g >= 0.999) return 1;
  const lut = new Uint8ClampedArray(256);
  for (let v = 0; v < 256; v++) lut[v] = Math.round(255 * Math.pow(v / 255, g));
  const x0 = plan ? plan.padX : 0;
  const y0 = plan ? plan.padY : 0;
  const x1 = plan ? plan.padX + plan.nw : size;
  const y1 = plan ? plan.padY + plan.nh : size;
  for (let y = y0; y < y1; y++) {
    let i = (y * size + x0) * 4;
    for (let x = x0; x < x1; x++, i += 4) {
      rgba[i] = lut[rgba[i]];
      rgba[i + 1] = lut[rgba[i + 1]];
      rgba[i + 2] = lut[rgba[i + 2]];
    }
  }
  return g;
}

// ---------------------------------------------------------------------------
// 2. Step-wise reduction of a large frame
// ---------------------------------------------------------------------------

/**
 * The intermediate sizes ([w, h] pairs) a source of srcW x srcH should pass through on its way to
 * dstW x dstH so that the LAST hop is at most 2x. Empty when the source is already within 2x of the
 * target (a 640 px camera, and 1280x960 into the 640 letterbox) - then one drawImage is exact enough.
 * The final entry is the size the last drawImage reads from.
 */
export function downscaleSteps(srcW, srcH, dstW, dstH) {
  const steps = [];
  let w = srcW;
  let h = srcH;
  let guard = 0;
  while ((w > dstW * 2 || h > dstH * 2) && guard++ < 8) {
    w = Math.max(dstW, Math.ceil(w / 2));
    h = Math.max(dstH, Math.ceil(h / 2));
    steps.push([w, h]);
  }
  return steps;
}

// ---------------------------------------------------------------------------
// 3. Face sharpness, relative to the same camera's own recent best
// ---------------------------------------------------------------------------

/**
 * Variance of the 4-neighbour Laplacian of the luma inside `roi` ({x0, y0, x1, y1}, pixels), or of the
 * central half of the frame when there is no face yet. Returns null when the region is too small to mean
 * anything. Only used as a RATIO against the camera's own recent best, so its absolute scale never matters.
 */
let _sharpBuf = null;

export function measureSharpness(rgba, width, height, roi) {
  const x0 = roi ? roi.x0 : Math.floor(width * 0.25);
  const y0 = roi ? roi.y0 : Math.floor(height * 0.25);
  const x1 = roi ? roi.x1 : Math.ceil(width * 0.75);
  const y1 = roi ? roi.y1 : Math.ceil(height * 0.75);
  const w = x1 - x0;
  const h = y1 - y0;
  if (w < 16 || h < 16) return null;

  // Lấy mẫu bước nhảy step = 2: tính nhanh gấp 4 lần, tiết kiệm CPU và chống giật khung hình
  const sw = Math.floor(w / 2);
  const sh = Math.floor(h / 2);
  const need = sw * sh;
  if (!_sharpBuf || _sharpBuf.length < need) {
    _sharpBuf = new Float32Array(Math.max(need, 320 * 240));
  }
  const luma = _sharpBuf;

  for (let y = 0; y < sh; y++) {
    const srcY = y0 + y * 2;
    const rowOffset = srcY * width;
    const dstOffset = y * sw;
    for (let x = 0; x < sw; x++) {
      const srcX = x0 + x * 2;
      const i = (rowOffset + srcX) * 4;
      luma[dstOffset + x] = 0.299 * rgba[i] + 0.587 * rgba[i + 1] + 0.114 * rgba[i + 2];
    }
  }

  let n = 0;
  let sum = 0;
  let sumSq = 0;
  for (let y = 1; y < sh - 1; y++) {
    const row = y * sw;
    for (let x = 1; x < sw - 1; x++) {
      const c = row + x;
      const lap = 4 * luma[c] - luma[c - 1] - luma[c + 1] - luma[c - sw] - luma[c + sw];
      sum += lap;
      sumSq += lap * lap;
      n++;
    }
  }
  if (n < 32) return null;
  const mean = sum / n;
  return Math.max(0, sumSq / n - mean * mean);
}

/**
 * Keeps the last ~120 readings of one camera. `ratio` is the smoothed current value over this camera's own
 * recent best (90th percentile); `blurred` turns on below `blurRatio` and off again only once it is back
 * above 1.25x that, so a face hovering at the edge does not flicker the quality note.
 */
export class SharpnessTracker {
  constructor(opts = {}) {
    this.size = opts.window || 120;
    this.minSamples = opts.minSamples || 12;
    this.blurRatio = opts.blurRatio || 0.5;
    this.reset();
  }

  reset() {
    this.vals = [];
    this.ema = null;
    this._ratio = 1;
    this._blurred = false;
  }

  push(v) {
    if (v == null || !Number.isFinite(v) || v <= 0) return;
    this.vals.push(v);
    if (this.vals.length > this.size) this.vals.shift();
    this.ema = this.ema == null ? v : this.ema * 0.7 + v * 0.3;
    if (this.vals.length < this.minSamples) return;
    const s = this.vals.slice().sort((a, b) => a - b);
    const best = s[Math.floor((s.length - 1) * 0.9)];
    this._ratio = best > 0 ? clamp(this.ema / best, 0, 1.5) : 1;
    this._blurred = this._blurred ? this._ratio < this.blurRatio * 1.25 : this._ratio < this.blurRatio;
  }

  get blurred() { return this._blurred; }

  get ratio() { return this._ratio; }

  /** A short note for the status line, empty while the picture is fine or there is not enough history yet. */
  text() {
    if (this.vals.length < this.minSamples || !this._blurred) return '';
    return 'ảnh nhòe (độ nét ' + Math.round(this._ratio * 100) + '% so với mức tốt nhất của camera này)';
  }
}
