/* FocusChangeAI dashboard - giao diện 8 màn theo đặc tả mục 5, viết bằng JS thuần.
   Không CDN, không framework: máy lớp offline hoàn toàn nên mọi thứ phải tự chứa.
   Quy tắc hiển thị: số chưa đo được thì hiện "--", không bao giờ bịa; dữ liệu mẫu luôn
   có băng cảnh báo DEMO; ảnh camera chỉ là luồng MJPEG trong bộ nhớ, không lưu xuống đĩa. */
"use strict";

/* ============================== tiện ích chung ============================== */
const $ = (id) => document.getElementById(id);

function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g,
    (c) => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]));
}
function todayStr() {
  const d = new Date();
  return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
}
function fmtTime(ms) {
  if (!ms) return "--";
  const d = new Date(ms);
  return String(d.getHours()).padStart(2, "0") + ":" + String(d.getMinutes()).padStart(2, "0");
}
function fmtDate(ms) {
  if (!ms) return "--";
  const d = new Date(ms);
  return String(d.getDate()).padStart(2, "0") + "/" + String(d.getMonth() + 1).padStart(2, "0") + "/" + d.getFullYear();
}
function fmtDateTime(ms) { return ms ? fmtDate(ms) + " " + fmtTime(ms) : "--"; }
function num(v, digits) {
  if (v == null || isNaN(v)) return "--";
  return Number(v).toFixed(digits == null ? 1 : digits);
}
function fmtBytes(n) {
  if (n == null) return "--";
  if (n > 1048576) return (n / 1048576).toFixed(1) + " MB";
  if (n > 1024) return (n / 1024).toFixed(0) + " KB";
  return n + " B";
}
function stamp() {
  const d = new Date(), p = (x) => String(x).padStart(2, "0");
  return "" + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + "_" + p(d.getHours()) + p(d.getMinutes()) + p(d.getSeconds());
}

/* Gọi API: GET khi không có body, POST JSON khi có. Lỗi mạng/HTTP ném exception để
   chỗ gọi toast; lỗi nghiệp vụ {ok:false,error} trả nguyên vẹn để chỗ gọi tự xử lý. */
async function api(path, body) {
  const opt = body === undefined ? {} : {
    method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)};
  const res = await fetch((window.FC_API_BASE || "") + path, opt);
  const ct = res.headers.get("Content-Type") || "";
  if (!ct.includes("json")) {
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res;
  }
  const data = await res.json();
  if (!res.ok && data && data.error) throw new Error(data.error);
  return data;
}

/* Tải CSV qua fetch để bắt được lỗi 403 (bậc chưa đủ) và toast thay vì mở tab JSON. */
async function downloadCsv(url, filename) {
  try {
    const res = await fetch((window.FC_API_BASE || "") + url);
    const ct = res.headers.get("Content-Type") || "";
    if (ct.includes("json")) {
      const data = await res.json();
      toast(data.error || "Không xuất được CSV", "err");
      return;
    }
    if (!res.ok) throw new Error("HTTP " + res.status);
    const blob = await res.blob();
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = filename;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
    toast("Đã tải " + filename, "ok");
  } catch (e) { toast("Lỗi tải CSV: " + e.message, "err"); }
}

/* Lưu một Blob thành tệp tải xuống. Dùng cho JSON và cho CSV dựng ngay trong trình duyệt (tuần theo từng em),
   nơi không có tuyến máy chủ để gọi. */
function saveBlob(blob, filename) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  toast("Đã tải " + filename, "ok");
}

function toast(msg, kind) {
  const root = $("toast-root");
  if (Array.from(root.children).some((item) => item.textContent === String(msg))) return;
  const el = document.createElement("div");
  el.className = "toast" + (kind ? " " + kind : "");
  el.textContent = msg;
  root.appendChild(el);
  setTimeout(() => el.remove(), 4200);
}

function openModal(html) {
  const root = $("modal-root");
  root.innerHTML = '<div class="modal card">' + html + "</div>";
  root.hidden = false;
}
function closeModal() {
  const root = $("modal-root");
  root.hidden = true;
  root.innerHTML = "";
}
function modalOpen() { return !$("modal-root").hidden; }

function confirmModal(title, messageHtml, onConfirm, opts) {
  opts = opts || {};
  openModal(
    "<h3>" + esc(title) + "</h3><div>" + messageHtml + "</div>" +
    '<div class="modal-actions">' +
    '<button class="btn" id="modal-cancel">Hủy</button>' +
    '<button class="btn ' + (opts.danger ? "btn-danger" : "btn-primary") + '" id="modal-ok">' +
    esc(opts.label || "Xác nhận") + "</button></div>");
  $("modal-cancel").onclick = closeModal;
  $("modal-ok").onclick = async () => { closeModal(); await onConfirm(); };
}

/* ============================== biểu tượng SVG ============================== */
/* Vẽ tay từng icon để không phụ thuộc thư viện ngoài; nét 2px cùng tông chữ. */
const ICON_PATHS = {
  home: '<path d="M3 10.5L12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/>',
  camera: '<path d="M23 7l-7 5 7 5V7z"/><rect x="1" y="5" width="15" height="14" rx="2"/>',
  users: '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
  chart: '<path d="M3 3v18h18"/><path d="M7 14l4-4 3 3 5-6"/>',
  bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/>',
  "bell-off": '<path d="M13.7 21a2 2 0 0 1-3.4 0"/><path d="M18 8a6 6 0 0 0-9.3-5"/><path d="M6 8c0 7-3 9-3 9h14"/><path d="M3 3l18 18"/>',
  report: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6"/><path d="M8 13h8M8 17h5"/>',
  database: '<ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/>',
  settings: '<path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3"/><path d="M1 14h6M9 8h6M17 16h6"/>',
  eye: '<path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/>',
  play: '<polygon points="6 3 20 12 6 21 6 3"/>',
  stop: '<rect x="5" y="5" width="14" height="14" rx="2"/>',
  photo: '<path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z"/><circle cx="12" cy="13" r="4"/>',
  expand: '<path d="M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  edit: '<path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4z"/>',
  trash: '<path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/>',
  upload: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M17 8l-5-5-5 5"/><path d="M12 3v12"/>',
  download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M7 10l5 5 5-5"/><path d="M12 15V3"/>',
  check: '<path d="M20 6L9 17l-5-5"/>',
  x: '<path d="M18 6L6 18M6 6l12 12"/>',
  wifi: '<path d="M5 12.55a10.94 10.94 0 0 1 14.08 0"/><path d="M1.42 9a15.91 15.91 0 0 1 21.16 0"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><path d="M12 20h.01"/>',
  wifioff: '<path d="M1 1l22 22"/><path d="M16.72 11.06A10.94 10.94 0 0 1 19 12.55"/><path d="M5 12.55a10.94 10.94 0 0 1 5.17-2.39"/><path d="M10.71 5.05A16 16 0 0 1 22.58 9"/><path d="M1.42 9a15.91 15.91 0 0 1 4.7-2.88"/><path d="M8.53 16.11a6 6 0 0 1 6.95 0"/><path d="M12 20h.01"/>',
  refresh: '<path d="M23 4v6h-6"/><path d="M1 20v-6h6"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>',
  lock: '<rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
  warn: '<path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><path d="M12 9v4M12 17h.01"/>',
  clock: '<circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>',
  search: '<circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/>',
  activity: '<path d="M22 12h-4l-3 9L9 3l-3 9H2"/>',
  angle: '<path d="M4 20h16"/><path d="M4 20L16 4"/><path d="M9 20a9 9 0 0 0-2.2-5.8"/>',
  mouth: '<circle cx="12" cy="12" r="10"/><path d="M8 14s1.5 2 4 2 4-2 4-2"/><path d="M9 9h.01M15 9h.01"/>',
  target: '<circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="6"/><circle cx="12" cy="12" r="2"/>',
  save: '<path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><path d="M17 21v-8H7v8M7 3v5h8"/>',
  info: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/>',
  mic: '<path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><path d="M12 19v4M8 23h8"/>',
};
function ico(name) {
  return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (ICON_PATHS[name] || "") + "</svg>";
}

/* In the app and on the hosted site this page lives at /dashboard/, one level under the measurement
   screen. A dashboard-only build (tools/build_site.py) is served at the root, where ../index.html
   resolves back to this same page: there is no measurement screen beside it, so the link must not
   pretend there is one. */
const MEASURE_HREF = (() => {
  const self = location.href.split("#")[0].split("?")[0];
  return new URL("../index.html", location.href).href === self ? "" : "../index.html";
})();
function measureHint(tail) {
  return MEASURE_HREF
    ? 'Đo ngay tại <a href="#camera"><b>màn Camera</b></a> của dashboard' + (tail || "")
    : "Bản dashboard-only không có màn đo đi kèm — muốn đo thì chạy app PC hoặc bản web đầy đủ.";
}

/* ============================== trạng thái ============================== */
const S = {
  view: "overview",
  date: todayStr(),
  overview: null, live: null, trend: [], system: null, settings: {},
  classes: [], students: [], recentAlerts: [], audit: [],
  alertFilter: {student: "", kind: "", level: "", date: "", unseen: ""},
  reportScope: "session", reportDate: todayStr(),
  analysisSid: "", analysisScope: "buoi",
  studentQuery: "",
  focusTrack: null, hidePeople: false,
  voice: null,
  soundOn: localStorage.getItem("fc_sound") !== "0",
  alertSummary: localStorage.getItem("fc_alert_summary") === "1",
  lastUnseen: null,
};

const TIER_LABEL = {standard: "Standard", pro: "Pro", promax: "Pro Max", ultra: "Ultra"};
const GATE_LABEL = {
  csv_export: "Xuất CSV/Excel", personal_baseline: "Baseline cá nhân",
  multi_class: "Nhiều lớp / nhiều giáo viên", parent_report: "Báo cáo gửi phụ huynh",
  yolo_context: "Nhận diện bối cảnh (YOLO)", predict: "Dự báo xu hướng AI",
  assistant: "Trợ lý hỏi đáp", parent_portal: "Cổng phụ huynh",
};
const KIND_LABEL = {
  DROWSY: "Buồn ngủ", PERCLOS_HIGH: "PERCLOS cao", LOOK_AWAY: "Nhìn đi chỗ khác",
  VISUAL_DRIFT: "Lệch hướng nhìn", YAWN: "Ngáp", TOO_CLOSE: "Ngồi quá gần",
  LOW_VS_PERSONAL: "Thấp hơn thói quen", LOW_BLINK: "Chớp mắt ít", SQUINT: "Nheo mắt",
  ASYMMETRY: "Bất đối xứng", INCOMPLETE_BLINK: "Chớp không hết", HIGH_BLINK: "Chớp mắt nhiều",
  BREAK: "Nghỉ giữa tiết", CONFUSED: "Bối rối", NOISY: "Ồn / nói chuyện", UNRELIABLE: "Số liệu kém tin cậy",
};
const SIT_LABEL = {
  FORWARD: ["Hướng trước", ""], DOWN: ["Cúi đầu", "warn"], TILT: ["Nghiêng đầu", "warn"],
  TURNED: ["Quay đi", "bad"], DOZING: ["Buồn ngủ", "bad"], UNKNOWN: ["--", "warn"],
};
const ACTION_LABEL = {
  student_create: "Thêm học sinh", student_update: "Sửa học sinh", student_delete: "Xóa học sinh",
  students_import: "Nhập danh sách CSV", consent_set: "Đổi đồng ý", camera_start: "Bật camera",
  camera_stop: "Dừng camera", settings_save: "Lưu cài đặt", backup: "Sao lưu CSDL",
  restore: "Khôi phục CSDL", demo_seed: "Nạp dữ liệu mẫu", demo_purge: "Xóa dữ liệu mẫu",
  alerts_seen_all: "Đánh dấu tất cả cảnh báo đã xem", retention_purge: "Dọn dữ liệu quá hạn",
};

// Bậc tối thiểu của từng tính năng khi máy chủ không trả về (chế độ DEMO). Dùng chung cho allows() và màn Cài đặt.
const DEFAULT_GATES = {
  csv_export: "pro", personal_baseline: "pro", multi_class: "promax",
  parent_report: "promax", yolo_context: "promax", predict: "ultra", assistant: "ultra", parent_portal: "ultra",
};
function allows(feature) {
  const gates = (S.system && S.system.gates) || DEFAULT_GATES;
  const tiers = ["standard", "pro", "promax", "ultra"];
  const need = gates[feature];
  if (!need) return true;
  const tier = S.settings.tier || "standard";
  return tiers.indexOf(tier) >= tiers.indexOf(need);
}

/* ============================== biểu đồ SVG ============================== */
/* vals: mảng số (null = khoảng trống, đường bị ngắt đúng chỗ đó). labels: nhãn trục x.
   opts: yMin/yMax, band {lo,hi,color}, color, height. Không thư viện ngoài. */
function lineChart(vals, labels, opts) {
  opts = opts || {};
  const W = 600, H = opts.height || 190, L = 38, R = 12, T = 12, B = 26;
  const ymin = opts.yMin == null ? 0 : opts.yMin;
  const ymax = opts.yMax == null ? 100 : opts.yMax;
  const n = vals.length;
  if (!n) return '<div class="chart-empty">Chưa có dữ liệu để vẽ.</div>';
  const px = (i) => n < 2 ? L + (W - L - R) / 2 : L + i * (W - L - R) / (n - 1);
  const py = (v) => T + (H - T - B) * (1 - (Math.max(ymin, Math.min(ymax, v)) - ymin) / (ymax - ymin || 1));
  let svg = '<svg viewBox="0 0 ' + W + " " + H + '" role="img">';
  // lưới ngang + nhãn trục y
  for (let g = 0; g <= 4; g++) {
    const v = ymin + (ymax - ymin) * g / 4, y = py(v);
    svg += '<line x1="' + L + '" y1="' + y + '" x2="' + (W - R) + '" y2="' + y +
      '" stroke="var(--line)" stroke-width="1"/>' +
      '<text x="' + (L - 6) + '" y="' + (y + 4) + '" text-anchor="end" font-size="10" fill="var(--ink-soft)">' +
      (Math.abs(v) < 10 && ymax <= 1 ? v.toFixed(2) : Math.round(v)) + "</text>";
  }
  // dải baseline cá nhân (nếu có)
  if (opts.band && opts.band.lo != null && opts.band.hi != null) {
    const y1 = py(opts.band.hi), y2 = py(opts.band.lo);
    svg += '<rect x="' + L + '" y="' + y1 + '" width="' + (W - L - R) + '" height="' + Math.max(1, y2 - y1) +
      '" fill="' + (opts.band.color || "rgba(34,197,94,.14)") + '"/>';
  }
  // đường + vùng tô, ngắt quãng tại điểm null
  const color = opts.color || "var(--blue)";
  let seg = [], segs = [];
  for (let i = 0; i < n; i++) {
    if (vals[i] == null) { if (seg.length) segs.push(seg); seg = []; }
    else seg.push([px(i), py(vals[i])]);
  }
  if (seg.length) segs.push(seg);
  for (const s of segs) {
    if (s.length > 1) {
      const d = s.map((p, i) => (i ? "L" : "M") + p[0].toFixed(1) + " " + p[1].toFixed(1)).join(" ");
      svg += '<path d="' + d + " L" + s[s.length - 1][0].toFixed(1) + " " + (H - B) + " L" + s[0][0].toFixed(1) + " " + (H - B) +
        ' Z" fill="' + color + '" opacity="0.10"/>';
      svg += '<path d="' + d + '" fill="none" stroke="' + color + '" stroke-width="2"/>';
    }
    for (const p of s) svg += '<circle cx="' + p[0].toFixed(1) + '" cy="' + p[1].toFixed(1) + '" r="2.4" fill="' + color + '"/>';
  }
  // nhãn trục x, thưa nhất để không chồng chữ
  const step = Math.max(1, Math.ceil(n / 6));
  for (let i = 0; i < n; i += step) {
    svg += '<text x="' + px(i).toFixed(1) + '" y="' + (H - 8) + '" text-anchor="middle" font-size="10" fill="var(--ink-soft)">' +
      esc(labels[i] || "") + "</text>";
  }
  svg += "</svg>";
  return '<div class="chart-box">' + svg + "</div>";
}

/* Biểu đồ 30 phút: dữ liệu đã lưu theo phút, số đo đang chạy theo 10 giây. */
function trendChart(rows, liveRows) {
  const now = Date.now(), start = now - 30 * 60000;
  const stepMs = liveRows && liveRows.length ? 10000 : 60000;
  const count = 30 * 60000 / stepMs;
  const byBucket = {};
  for (const r of rows) {
    if (r.bucket_ms >= start && r.mean_sei != null) {
      byBucket[Math.floor(r.bucket_ms / stepMs) * stepMs] = r.mean_sei;
    }
  }
  for (const r of liveRows || []) {
    if (r.bucket_ms >= start && r.mean_sei != null) {
      byBucket[Math.floor(r.bucket_ms / stepMs) * stepMs] = r.mean_sei;
    }
  }
  const vals = [], labels = [];
  for (let i = count; i >= 0; i--) {
    const bucket = Math.floor((now - i * stepMs) / stepMs) * stepMs;
    vals.push(Object.prototype.hasOwnProperty.call(byBucket, bucket) ? byBucket[bucket] : null);
    labels.push(i % (300000 / stepMs) === 0 ? fmtTime(bucket) : "");
  }
  if (vals.every((v) => v == null)) {
    return lineChart(vals, labels, {yMin: 0, yMax: 100, color: "var(--blue)"}) +
      '<div class="chart-empty">' +
      (cameraIsRunning()
        ? "Camera đang đo. Gắn tên học sinh và chờ có SEI hợp lệ; xu hướng sẽ hiện tại đây."
        : "Chưa có dữ liệu 30 phút gần nhất.<br>" +
          (window.FC_MODE === "web"
            ? measureHint(" — biểu đồ cập nhật khi có số đo.") + " Trang này không nạp số liệu mẫu."
            : "Bật camera để đo, hoặc nạp dữ liệu mẫu ở màn Sao lưu.")) +
      "</div>";
  }
  return lineChart(vals, labels, {yMin: 0, yMax: 100, color: "var(--blue)"});
}

/* ============================== khung dùng chung ============================== */
const VIEWS = [
  ["overview", "Tổng quan", "home"],
  ["camera", "Camera", "camera"],
  ["students", "Học sinh", "users"],
  ["analysis", "Phân tích AI", "chart"],
  ["alerts", "Cảnh báo", "bell"],
  ["lesson", "Buổi học", "clock"],
  ["reports", "Báo cáo", "report"],
  ["backup", "Sao lưu", "database"],
  ["settings", "Cài đặt", "settings"],
];

// Nút "Tải App PC" chỉ hiện khi gói đó thật sự được đặt trên máy chủ (Cloudflare Pages không cho file > 25 MiB,
// nên trên trang đã deploy thường là không có). Hỏi bằng HEAD một lần; lỗi hoặc trả về trang HTML = ẩn nút.
let PC_DL = null;
function pcDownloadProbe() {
  if (PC_DL !== null) return;
  PC_DL = false;
  fetch("/download/FocusChangeAI-PC-app.zip", { method: "HEAD" }).then((r) => {
    const t = (r.headers.get("content-type") || "").toLowerCase();
    if (r.ok && !t.includes("text/html")) { PC_DL = true; const a = $("nav-pc-dl"); if (a) a.hidden = false; }
  }).catch(() => { /* không có mạng/không có file: giữ ẩn */ });
}

function buildNav() {
  const unseen = S.overview ? S.overview.alerts_unseen : 0;
  $("nav").innerHTML = VIEWS.map(([id, label, icon]) =>
    '<button class="nav-item' + (S.view === id ? " active" : "") + '" data-view="' + id + '">' +
    ico(icon) + "<span>" + label + "</span>" +
    (id === "alerts" ? '<span class="nav-badge" id="nav-alert-badge"' + (unseen ? "" : " hidden") + ">" + unseen + "</span>" : "") +
    "</button>").join("") +
    '<a class="nav-item" id="nav-pc-dl"' + (PC_DL ? "" : " hidden") + ' href="/download/FocusChangeAI-PC-app.zip" download ' +
    'style="margin-top:10px;border:1px dashed var(--blue);color:var(--blue);font-weight:600;text-decoration:none;" title="Tải trọn gói ứng dụng PC không cần mạng">' +
    ico("download") + '<span>Tải App PC (144MB)</span></a>';
  for (const btn of $("nav").querySelectorAll(".nav-item")) {
    if (!btn.dataset.view) continue;
    btn.onclick = () => { location.hash = "#" + btn.dataset.view; closeNav(); };
  }
  pcDownloadProbe();
}

function buildShell() {
  $("brand-logo").innerHTML =
    '<svg viewBox="0 0 44 44"><rect width="44" height="44" rx="12" fill="#1f6feb"/>' +
    '<path d="M8 22s5.5-9 14-9 14 9 14 9-5.5 9-14 9-14-9-14-9z" fill="none" stroke="#fff" stroke-width="2.4"/>' +
    '<circle cx="22" cy="22" r="4.4" fill="#fff"/></svg>';
  $("foot-chip").innerHTML =
    '<svg viewBox="0 0 84 64"><rect x="14" y="6" width="56" height="38" rx="4" fill="#0a1526" stroke="#3b5f96"/>' +
    '<path d="M24 25s4-6 10-6 10 6 10 6-4 6-10 6-10-6-10-6z" fill="none" stroke="#4d94ff" stroke-width="1.6"/>' +
    '<circle cx="34" cy="25" r="2.6" fill="#4d94ff"/>' +
    '<path d="M50 18h14M50 25h14M50 32h9" stroke="#3b5f96" stroke-width="2" stroke-linecap="round"/>' +
    '<path d="M8 50h68l-5 8H13z" fill="#123f7b"/></svg>';
  $("top-class-icon").innerHTML = ico("users");
  // Một nút tắt tiếng duy nhất ở thanh trên, thấy được từ mọi màn: âm cảnh báo là thứ duy nhất
  // trang này phát, và giáo viên đang giảng bài phải tắt được ngay mà không đi tìm trong Cài đặt.
  const tr = document.querySelector("#topbar .top-right");
  if (tr && !$("chip-sound")) {
    const pill = document.createElement("button");
    pill.id = "chip-live";
    pill.type = "button";
    pill.className = "chip-live";
    pill.hidden = !liveAvailable();
    const snd = document.createElement("button");
    snd.id = "chip-sound";
    snd.type = "button";
    snd.className = "chip-sound";
    snd.onclick = () => {
      S.soundOn = !S.soundOn;
      localStorage.setItem("fc_sound", S.soundOn ? "1" : "0");
      livePillPaint();
      const sw = $("lv-sound"); if (sw) sw.checked = S.soundOn;
      const al = $("al-sound"); if (al) al.checked = S.soundOn;
      toast(S.soundOn ? "Âm cảnh báo: BẬT" : "Âm cảnh báo: TẮT");
    };
    tr.insertBefore(snd, tr.firstChild);
    tr.insertBefore(pill, tr.firstChild);
    livePillPaint();
  }
  $("nav-toggle").innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 6h18M3 12h18M3 18h18"/></svg>';
  $("nav-toggle").onclick = toggleNav;
  $("nav-scrim").onclick = closeNav;
  // Mạng lớp là tính năng GIAI ĐOẠN 3 chưa xây: trước đây chip đỏ "Chưa kết nối"
  // trông như một lỗi thật. Nay trung tính + bấm mở popover giải thích đúng bản chất.
  $("chip-lan").innerHTML = ico("wifi") + "<span>Mạng lớp: Giai đoạn 3</span>" + ico("info");
  $("chip-lan").onclick = toggleLanPop;
  $("modal-root").addEventListener("click", (e) => { if (e.target === $("modal-root")) closeModal(); });
}

/* ============================== popover Mạng lớp ============================== */
function lanPopEl() {
  let el = document.getElementById("lan-pop");
  if (el) return el;
  el = document.createElement("div");
  el.id = "lan-pop";
  el.className = "lan-pop";
  el.hidden = true;
  el.setAttribute("role", "dialog");
  el.setAttribute("aria-label", "Giải thích mục Mạng lớp");
  el.innerHTML =
    "<h4>" + ico("wifi") + "Mạng lớp là gì?</h4>" +
    "<p>Là tính năng ghép nối điện thoại học sinh hoặc nhiều camera thành mạng nội bộ trong lớp, " +
    "để đo nhiều góc cùng lúc. Tính năng này thuộc <b>giai đoạn 3</b> — chưa xây, nên không có kết nối nào bị lỗi ở đây.</p>" +
    '<div class="lan-road">' +
    '<div class="lan-step"><i class="dot dot-green"></i><span>GĐ1 · Đo bằng camera máy này</span><b>Đã xong</b></div>' +
    '<div class="lan-step"><i class="dot dot-green"></i><span>GĐ2 · Dashboard web 8 màn</span><b>Đã xong</b></div>' +
    '<div class="lan-step"><i class="dot dot-grey"></i><span>GĐ3 · Mạng lớp / ghép nối điện thoại</span><b>Chưa làm</b></div>' +
    "</div>" +
    '<p class="lan-note">Việc đo hiện tại dùng camera của máy này và không bị ảnh hưởng bởi mục này.</p>' +
    '<div class="modal-actions"><button class="btn btn-primary btn-sm" id="lan-pop-ok" type="button">Đã hiểu</button></div>';
  document.body.appendChild(el);
  el.querySelector("#lan-pop-ok").onclick = closeLanPop;
  return el;
}
function toggleLanPop() {
  const el = lanPopEl();
  if (!el.hidden) { closeLanPop(); return; }
  el.hidden = false;
  const r = $("chip-lan").getBoundingClientRect();
  const w = el.offsetWidth;
  el.style.left = Math.min(Math.max(8, r.right - w), window.innerWidth - w - 8) + "px";
  el.style.top = (r.bottom + 8) + "px";
}
function closeLanPop() {
  const el = document.getElementById("lan-pop");
  if (el) el.hidden = true;
}
document.addEventListener("click", (e) => {
  const el = document.getElementById("lan-pop");
  if (el && !el.hidden && !el.contains(e.target) && !$("chip-lan").contains(e.target)) closeLanPop();
});
document.addEventListener("keydown", (e) => { if (e.key === "Escape") { closeLanPop(); closeNav(); } });
// Thẻ người vẽ lại mỗi nhịp nên uỷ quyền click ở document: nút Gắn tên / Đổi tên / Bỏ tên.
document.addEventListener("click", (e) => {
  const ht = e.target.closest && e.target.closest("[data-hud-track]");
  if (ht) {
    S.focusTrack = parseInt(ht.getAttribute("data-hud-track"), 10);
    updateCameraParts();
    return;
  }
  const b = e.target.closest && e.target.closest("[data-bind]");
  if (b) { bindPerson(parseInt(b.getAttribute("data-bind"), 10)); return; }
  const u = e.target.closest && e.target.closest("[data-unbind]");
  if (u) unbindPerson(parseInt(u.getAttribute("data-unbind"), 10));
});
// Chọn em trong danh sách của khung đo: uỷ quyền ở document vì danh sách vẽ lại khi hồ sơ đổi.
document.addEventListener("click", (e) => {
  const b = e.target.closest && e.target.closest("[data-lvbind]");
  if (!b) return;
  liveCmd("bind", {id: b.getAttribute("data-lvbind")}, 10000).then((r) => {
    if (!r.ok) { toast(r.error, "err"); return; }
    LIVE.rosterKey = "";
    toast("Đang đo và ghi phiên cho “" + ((r.result && r.result.name) || "em này") + "”", "ok");
    livePaint();
  });
});

/* ============================== menu mobile (drawer) ============================== */
function toggleNav() {
  const open = !$("sidebar").classList.contains("open");
  $("sidebar").classList.toggle("open", open);
  $("nav-scrim").hidden = !open;
}
function closeNav() {
  $("sidebar").classList.remove("open");
  $("nav-scrim").hidden = true;
}

async function loadClasses() {
  try { S.classes = await api("/api/classes"); } catch (e) { S.classes = []; }
  const sel = $("class-select");
  const cur = S.overview ? S.overview.class_id : (S.classes[0] || {}).class_id;
  if (!S.classes.length) {
    sel.innerHTML = '<option value="">' + esc((S.overview && S.overview.class_label) || "Lớp học") + "</option>";
  } else {
    sel.innerHTML = S.classes.map((c) =>
      '<option value="' + esc(c.class_id) + '"' + (c.class_id === cur ? " selected" : "") + ">" +
      esc(c.name) + " · " + esc(c.school_year) + "</option>").join("");
  }
  sel.onchange = () => {
    // Máy chủ hiện phục vụ MỘT lớp theo config.yaml; đổi lớp nóng là việc của bậc Pro Max
    // (giai đoạn 5). Nói thẳng thay vì giả vờ đổi.
    toast("Đổi lớp trực tiếp cần bậc Pro Max và sẽ nối ở giai đoạn sau. Lớp hiện tại lấy từ config.yaml.", "err");
    sel.value = cur || "";
  };
}

// A machine that has not closed its first session yet has an EMPTY store, not a broken one. A red
// "Lỗi" chip there reads as a crashed app while the measurement frame is writing perfectly well.
function dbChipState() {
  const db = (S.system && S.system.database) || {};
  if (db.ready) return { dot: "dot-green", label: "Sẵn sàng", bad: false };
  if (db.empty) return { dot: "dot-grey", label: "Trống", bad: false };
  return { dot: "dot-red", label: "Lỗi", bad: true };
}

function updateChips() {
  const dbs = dbChipState();
  $("chip-db").innerHTML = ico("database") +
    '<span><i class="dot ' + dbs.dot + '"></i>SQLite: ' + dbs.label + "</span>";
  const banner = $("demo-banner");
  const ov = S.overview || {};
  const demo = !!ov.demo;
  // Bản web đọc SQLite thật của máy này: băng thông tin màu xanh, không phải băng cảnh báo DEMO.
  const web = !demo && window.FC_MODE === "web";
  banner.hidden = !demo && !web;
  banner.classList.toggle("banner-real", web);
  if (demo) {
    banner.innerHTML = ico("warn") +
      "<span><b>DỮ LIỆU MẪU (DEMO)</b> đang được hiển thị để nghiệm thu giao diện — không phải số đo thật.</span>" +
      '<span class="spacer" style="flex:1"></span><button class="btn btn-sm" id="demo-banner-purge">Xóa dữ liệu mẫu</button>';
    $("demo-banner-purge").onclick = purgeDemo;
  } else if (web) {
    const wi = ov.web_info || {};
    const has = (wi.sessions || 0) > 0;
    banner.innerHTML = ico(has ? "database" : "info") +
      "<span>" + (has
        ? "<b>SỐ ĐO THẬT</b> — " + wi.sessions + " phiên · " + wi.students + " em, đọc từ " +
          esc(wi.where || "SQLite của máy này") + ". Đo tiếp ngay tại màn Camera của dashboard."
        : "<b>CHƯA CÓ DỮ LIỆU ĐO</b> — máy này chưa ghi phiên nào. Bấm <b>Đo tại đây</b>, chọn em rồi bấm Dừng để chốt phiên vào SQLite. Trang không hiển thị số liệu mẫu.") +
      "</span>" +
      '<span class="spacer" style="flex:1"></span>' +
      (liveAvailable() ? '<button class="btn btn-sm btn-primary" id="web-banner-measure">' + ico("camera") + " Đo tại đây</button>" : "") +
      '<button class="btn btn-sm" id="web-banner-live" title="Dò dashboard Python đang chạy trên máy này (127.0.0.1:8730)">Kết nối LIVE</button>';
    $("web-banner-live").onclick = connectLive;
    const bm = $("web-banner-measure");
    if (bm) bm.onclick = liveShowCamera;
  }
  const badge = $("nav-alert-badge");
  if (badge && S.overview) {
    badge.textContent = S.overview.alerts_unseen;
    badge.hidden = !S.overview.alerts_unseen;
  }
}

/* Dò dashboard Python cục bộ rồi chuyển sang LIVE. Header không "simple" để buộc preflight:
   Chrome khi đó hiện hộp quyền mạng nội bộ một lần thay vì chặn thầm lặng GET https -> loopback. */
async function connectLive() {
  const btn = $("web-banner-live");
  if (btn) btn.disabled = true;
  try {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), 2500);
    const r = await fetch("http://127.0.0.1:8730/api/system",
      { signal: ctl.signal, headers: { "Content-Type": "application/json" } });
    clearTimeout(t);
    const j = await r.json();
    if (j && j.app) {
      toast("Đã thấy máy cục bộ — chuyển sang LIVE…", "ok");
      setTimeout(() => { location.href = location.pathname + "?mode=live"; }, 600);
      return;
    }
    throw new Error("no server");
  } catch (e) {
    toast("Chưa nối được dashboard cục bộ. Nếu trình duyệt hỏi quyền truy cập mạng cục bộ, bấm Cho phép rồi thử lại; hoặc mở bản cục bộ đầy đủ.", "err");
  }
  if (btn) btn.disabled = false;
}

/* ============================== polling ============================== */
async function tickFast() {
  try {
    // Khẩu lệnh giáo viên chỉ có ở LIVE/DEMO (bản web đọc SQLite không có micro, không có Python).
    // .catch riêng để một tuyến thiếu trên server cũ không kéo sập cả nhịp cập nhật số đo.
    const jobs = [
      api("/api/overview?date=" + encodeURIComponent(S.date)),
      api("/api/live")];
    if (window.FC_MODE !== "web") jobs.push(api("/api/teacher-voice").catch(() => null));
    const [ov, live, voice] = await Promise.all(jobs);
    S.overview = ov; S.live = live;
    if (window.FC_MODE !== "web") { try { lessonRecord(); } catch (e) { /* bỏ qua */ } try { seatMapPaint(); } catch (e) { /* bỏ qua */ } }
    if (voice) S.voice = voice;

    // 1. Cảnh báo thời gian thực NGAY TRONG LÚC QUAY CAMERA (nghiêm ngặt 5 phút 1 lần = 300.000 ms chống spam)
    const ALERT_COOLDOWN_MS = 300000;
    const now = Date.now();
    if (live) {
      let candidateAlert = null;
      if (live.recent_alerts && live.recent_alerts.length) {
        candidateAlert = live.recent_alerts[live.recent_alerts.length - 1];
      } else if (live.people) {
        for (const p of live.people) {
          if (p.alert) {
            candidateAlert = {student_id: p.student_id || p.track_id, student_name: p.name || ("Em #" + p.track_id), code: p.alert};
            break;
          }
        }
      }
      if (candidateAlert) {
        const sid = candidateAlert.student_id || candidateAlert.track_id;
        if (!window._studentAlertTimes) window._studentAlertTimes = {};
        const lastStudentTime = window._studentAlertTimes[sid] || 0;
        const lastGlobalTime = window._globalLastAlertTime || 0;

        // Cảnh báo 5 phút 1 lần để chống spam chuông/thông báo
        if (now - lastStudentTime >= ALERT_COOLDOWN_MS && now - lastGlobalTime >= ALERT_COOLDOWN_MS) {
          window._studentAlertTimes[sid] = now;
          window._globalLastAlertTime = now;
          beep();
          const label = KIND_LABEL[candidateAlert.code] || candidateAlert.code;
          const sName = candidateAlert.student_name || ("Học sinh #" + (candidateAlert.track_id || sid));
          toast("⚠️ CẢNH BÁO: " + sName + " — " + label, "err");
        }
      }
    }

    // 2. Âm báo khi có cảnh báo CHƯA XEM mới (đặc tả 5.7). Giãn cách tối thiểu 5 phút 1 lần.
    if (S.lastUnseen != null && ov.alerts_unseen > S.lastUnseen) {
      if (!window._globalLastAlertTime || now - window._globalLastAlertTime >= ALERT_COOLDOWN_MS) {
        window._globalLastAlertTime = now;
        beep();
        toast("Có cảnh báo mới (" + (ov.alerts_unseen - S.lastUnseen) + ")", "err");
      }
    }
    S.lastUnseen = ov.alerts_unseen;
    updateChips();
    if (S.view === "overview") updateOverviewParts();
    if (S.view === "camera") updateCameraParts();
  } catch (e) { /* server chưa kịp dậy hoặc đang khởi động: thử lại nhịp sau */ }
}

async function tickSlow() {
  try {
    const [trend, system, audit, alerts] = await Promise.all([
      api("/api/trend?minutes=30"), api("/api/system"), api("/api/audit"), api("/api/alerts")]);
    S.trend = trend; S.system = system; S.audit = audit; S.recentAlerts = alerts;
    updateChips();
    if (S.view === "overview") {
      liveOverviewPaint();
      const sy = $("ov-system"); if (sy) sy.innerHTML = systemRows();
      const ac = $("ov-activity"); if (ac) ac.innerHTML = activityRows();
    }
    if (S.view === "settings") { const a = $("audit-body"); if (a) a.innerHTML = auditRows(); }
  } catch (e) { /* bỏ qua nhịp này */ }
}

/* ============================== mảnh ghép Tổng quan ============================== */
// Màu theo ngưỡng gợi ý (chỉ để giáo viên liếc nhanh, không phải kết luận): xanh = ổn, vàng = lưu ý, đỏ = cần xem.
function avgTone(kind, v, fallback) {
  if (v == null || !isFinite(v)) return fallback;
  const G = "var(--green)", O = "var(--orange)", R = "var(--red)";
  switch (kind) {
    case "sei": return v >= 70 ? G : v >= 50 ? O : R;
    case "ear": return v >= 0.22 ? G : v >= 0.17 ? O : R;      // mắt nhắm dần
    case "mar": return v <= 0.45 ? G : v <= 0.6 ? O : R;       // miệng há (ngáp)
    case "angle": { const m = Math.abs(v); return m <= 15 ? G : m <= 25 ? O : R; }
    default: return fallback;
  }
}

function avgRow(icon, label, value, digits, scale, color, unit, signed, kind) {
  const magnitude = signed ? Math.abs(value) : value;
  const pct = value == null ? 0 : Math.max(0, Math.min(100, magnitude / scale * 100));
  const tone = avgTone(kind, value, color);
  return '<div class="avg-row">' + ico(icon) +
    '<div class="avg-label">' + label + ' — <b>' + num(value, digits) + (unit || "") + "</b></div>" +
    '<div class="avg-bar"><div class="avg-fill" style="width:' + pct.toFixed(0) + "%;background:" + tone + '"></div></div></div>';
}

// Biểu đồ mini mức chú ý trung bình trong ~vài phút gần nhất (chỉ giữ trong bộ nhớ tab, không ghi vào db).
function seiSpark(hist) {
  if (!hist || hist.length < 3) return "";
  const W = 260, H = 44, pad = 3;
  const step = (W - pad * 2) / (hist.length - 1);
  const pts = hist.map((v, i) => (pad + i * step).toFixed(1) + "," +
    (H - pad - Math.max(0, Math.min(100, v)) / 100 * (H - pad * 2)).toFixed(1)).join(" ");
  const last = hist[hist.length - 1];
  return '<div class="avg-spark"><div class="avg-spark-cap">Mức chú ý gần đây · ' + hist.length + ' lần đo</div>' +
    '<svg viewBox="0 0 ' + W + " " + H + '" preserveAspectRatio="none" aria-hidden="true">' +
    '<polyline fill="none" stroke="' + avgTone("sei", last, "var(--purple)") + '" stroke-width="2" stroke-linejoin="round" points="' + pts + '"/></svg></div>';
}

function paintOverviewAverages(a, source) {
  const box = $("ov-avgs");
  if (!box) return;
  box.innerHTML =
    avgRow("eye", "EAR (mở mắt)", a.ear, 2, 0.5, "var(--blue)", "", false, "ear") +
    avgRow("mouth", "MAR (miệng)", a.mar, 2, 1.0, "var(--green)", "", false, "mar") +
    avgRow("angle", "Quay trái/phải (yaw)", a.yaw, 1, 45, "var(--orange)", "°", true, "angle") +
    avgRow("angle", "Cúi/ngẩng đầu (pitch)", a.pitch, 1, 45, "var(--orange)", "°", true, "angle") +
    avgRow("eye", "Hướng nhìn ngang (lệch mốc)", a.gazeH, 3, 0.5, "var(--blue)", "", true) +
    avgRow("eye", "Hướng nhìn dọc (lệch mốc)", a.gazeV, 3, 0.6, "var(--blue)", "", true) +
    avgRow("target", "Mức chú ý", a.sei, 0, 100, "var(--purple)", "%", false, "sei") +
    (a.live ? seiSpark(LIVE.seiHist) : "");
  const note = $("ov-avg-note");
  if (note) note.textContent = "Nguồn: " + source + (a.n ? " · " + a.n + " em" : "") +
    ". EAR là độ mở mắt; yaw/pitch và hướng nhìn là độ lệch so với mốc cá nhân. " +
    "Màu xanh/vàng/đỏ chỉ là ngưỡng gợi ý. " +
    "Số chỉ để hỗ trợ, không dùng làm căn cứ kỷ luật duy nhất.";
}

function meanValue(values) {
  const valid = values.filter((v) => v != null && isFinite(Number(v))).map(Number);
  return valid.length ? valid.reduce((sum, v) => sum + v, 0) / valid.length : null;
}

function cameraIsRunning() {
  return window.FC_MODE === "web" ? liveOn()
    : !!(S.live && S.live.camera && S.live.camera.running);
}

function liveNamedMeasurements(s) {
  const kids = (s.kids || []).filter((k) => k && k.name && k.h);
  if (kids.length) return kids.map((k) => k.h);
  return s.bound && s.h ? [s.h] : [];
}

// Chưa gắn tên ai thì vẫn đo được khuôn mặt đang được chấm điểm (s.h). Trước đây trường hợp này bị
// bỏ qua nên bảng "Chỉ số trung bình lớp" đứng yên ở số của phiên cũ. Số này chỉ để hiển thị trực tiếp,
// không ghi vào hồ sơ học sinh nào.
function liveAverageSources(s) {
  const named = liveNamedMeasurements(s);
  if (named.length) return {hs: named, named: true};
  return {hs: s.h ? [s.h] : [], named: false};
}

function liveTrendCapture(running, hs) {
  if (!running) {
    LIVE.trendRows = [];
    LIVE.trendAt = 0;
    return LIVE.trendRows;
  }
  const now = Date.now();
  if (now - LIVE.trendAt < 1000) return LIVE.trendRows;
  LIVE.trendAt = now;
  const sei = meanValue(hs.map((h) => h.sei));
  if (sei == null) return LIVE.trendRows;
  const bucket = Math.floor(now / 10000) * 10000;
  const last = LIVE.trendRows[LIVE.trendRows.length - 1];
  if (last && last.bucket_ms === bucket) {
    last.total += sei;
    last.samples += 1;
    last.mean_sei = last.total / last.samples;
  } else {
    LIVE.trendRows.push({bucket_ms: bucket, mean_sei: sei, total: sei, samples: 1});
  }
  const cutoff = now - 30 * 60000;
  LIVE.trendRows = LIVE.trendRows.filter((r) => r.bucket_ms >= cutoff);
  return LIVE.trendRows;
}

// Báo nhẹ khi mức chú ý trung bình thấp liên tục >= 30 giây (tối đa 1 lần / 2 phút). Chỉ là thông báo
// trên màn hình, không lưu thành cảnh báo trong db (bản web chủ ý không đếm cảnh báo).
function liveLowAttentionNotice(sei, now) {
  if (sei == null || sei >= 50) { LIVE.lowSince = 0; return; }
  if (!LIVE.lowSince) LIVE.lowSince = now;
  if (now - LIVE.lowSince >= 30000 && now - (LIVE.lowToastAt || 0) >= 120000) {
    LIVE.lowToastAt = now;
    toast("Mức chú ý trung bình đang thấp (" + Math.round(sei) + "%) hơn 30 giây. Có thể cần đổi nhịp bài giảng.", "warn");
  }
}

function liveOverviewPaint(s, force) {
  const ovTrend = $("ov-trend");
  if (!$("ov-avgs") && !ovTrend) return false;
  let running, hs, source, named = true;
  if (window.FC_MODE === "web") {
    const state = s || LIVE.state || {};
    running = !!state.running;
    const src = liveAverageSources(state);
    hs = src.hs; named = src.named;
    source = named ? "camera trực tiếp · chưa chốt phiên"
      : "camera trực tiếp · chưa gắn tên, đang lấy khuôn mặt đang đo";
  } else {
    const state = S.live || {};
    running = !!(state.camera && state.camera.running);
    hs = (state.people || []).filter((p) => p && p.student_id).map((p) => ({
      sei: p.sei, ear1: p.ear, mar: p.mar, yaw: p.yaw, pitch: p.pitch,
    }));
    source = window.FC_MODE === "demo"
      ? "camera mô phỏng DEMO · chưa chốt phiên"
      : "camera trực tiếp · chưa chốt phiên";
  }
  let painted = false;
  if (running && hs.length) {
    // Vẽ lại tối đa ~3 lần/giây: số đo đến 4 lần/giây, vẽ dày hơn chỉ tốn CPU của máy đang chạy YOLO.
    const now = Date.now();
    if (force || now - (LIVE.avgAt || 0) >= 300) {
      const active = {
        ear: meanValue(hs.map((h) => h.ear1)),
        mar: meanValue(hs.map((h) => h.mar)),
        yaw: meanValue(hs.map((h) => h.yaw)),
        pitch: meanValue(hs.map((h) => h.pitch)),
        gazeH: meanValue(hs.map((h) => h.gazeHDev)),
        gazeV: meanValue(hs.map((h) => h.gazeVDev)),
        sei: meanValue(hs.map((h) => h.sei)),
        n: hs.length,
      };
      if (active.ear != null || active.mar != null || active.yaw != null || active.pitch != null ||
          active.gazeH != null || active.gazeV != null || active.sei != null) {
        LIVE.avgAt = now;
        if (active.sei != null) {
          LIVE.seiHist.push(Math.round(active.sei));
          if (LIVE.seiHist.length > 90) LIVE.seiHist.shift();
        }
        active.live = true;
        paintOverviewAverages(active, source);
        liveLowAttentionNotice(active.sei, now);
        painted = true;
      }
    } else {
      painted = true; // vừa vẽ xong, không để nhánh dự phòng ghi đè
    }
  }
  if (!running && LIVE.avgAt) {
    // Vừa dừng camera: trả bảng về số phiên đã chốt ngay, không chờ nhịp tickFast kế tiếp.
    LIVE.avgAt = 0; LIVE.seiHist = []; LIVE.lowSince = 0;
    const a0 = (S.overview && S.overview.averages) || {};
    paintOverviewAverages(a0, a0.label || "chưa có dữ liệu");
  }
  if (!painted && running) {
    // Camera đã chạy nhưng chưa có khuôn mặt / chưa đo xong: hiện số phiên đã chốt kèm lời giải thích,
    // thay vì để bảng trống hoặc nằm im mà không nói vì sao.
    const saved = (S.overview && S.overview.averages) || {};
    paintOverviewAverages(saved, "phiên đã chốt gần nhất (camera đang chờ khuôn mặt hoặc đang hiệu chuẩn, số trực tiếp sẽ hiện ngay khi đo được)");
  }
  if (ovTrend) ovTrend.innerHTML = trendChart(S.trend, liveTrendCapture(running, hs));
  return painted;
}

function systemRows() {
  const st = S.system;
  if (!st) return '<div class="muted small">Đang tải trạng thái…</div>';
  const row = (label, ok, text, bad) =>
    '<div class="sys-row"><span>' + label + '</span><span class="' +
    (bad ? "status-bad" : ok ? "status-ok" : "status-off") + '"><i class="dot ' +
    (bad ? "dot-red" : ok ? "dot-green" : "dot-grey") + '"></i>' + text + "</span></div>";
  const d = st.deps || {};
  // Bản web đo bằng khung nhúng ngay trong trang này: engine chạy trong iframe chứ không phải Python
  // của máy chủ, nên các hàng dưới đây nói đúng trạng thái khung đo thay vì "chưa cài" như máy hỏng.
  if (window.FC_MODE === "web") {
    const db = st.database || {};
    const s = LIVE.state;
    const on = liveOn();
    return row("Ứng dụng", true, st.app) +
      row("Nơi đọc số liệu", !!db.ready || !!db.empty,
        (db.path || "SQLite của máy này") + (db.empty ? " — chưa có phiên đo nào" : ""), !db.ready && !db.empty) +
      row("Đã ghi trong db", true, (db.sessions || 0) + " phiên · " + (db.students || 0) + " em") +
      row("Khung đo nhúng", LIVE.boot === "ready", LIVE.boot === "ready" ? "Sẵn sàng trong trang này"
        : LIVE.boot === "error" ? "Lỗi nạp khung" : LIVE.boot === "loading" ? "Đang nạp…" : "Chưa nạp", LIVE.boot === "error") +
      row("MediaPipe / YOLO", on, on ? (s && s.layer1 ? s.layer1 : "Đang chạy trong khung đo") : "Nạp khi bấm Bắt đầu đo") +
      row("Xử lý ảnh", on, on ? (s && s.imgProc ? s.imgProc : "WebGL/CPU") : "Chưa chạy") +
      row("Camera", on, on ? "Đang đo · " + (s ? s.faces : 0) + " mặt" : (s && s.starting) ? "Đang mở…" : "Chưa bật") +
      row("Bảng cảnh báo", false, "Bản web/app không ghi alerts") +
      row("Mạng nội bộ lớp", false, "Giai đoạn 3") +
      row("Đám mây (Firebase)", false, "Không dùng") +
      row("Bậc sản phẩm", true, "Không áp dụng cho bản web");
  }
  // Bản web DEMO không cài Python/MediaPipe trong trình duyệt: engine mô phỏng của
  // hosted-bridge.js mới là thứ chạy, nên nhãn phải nói đúng "mô phỏng", không đổ lỗi
  // "chưa cài" như máy thật (trông như hỏng app trong khi demo vẫn chạy đủ chức năng).
  if (window.FC_MODE === "demo") {
    const on = !!st.camera_running;
    return row("Ứng dụng", true, "Bản xem trước web (demo)") +
      row("MediaPipe", on, on ? "Mô phỏng đang chạy" : "Mô phỏng (chưa bật)") +
      row("YOLO (ultralytics)", on, on ? "Mô phỏng đang chạy" : "Mô phỏng (chưa bật)") +
      row("OpenCV", on, on ? "Mô phỏng đang chạy" : "Mô phỏng (chưa bật)") +
      row("Model khuôn mặt", true, "Không cần cho bản demo") +
      row("Cơ sở dữ liệu", true, "Bộ nhớ trình duyệt (demo)") +
      row("Camera", on, on ? "Đang chạy (demo)" : "Chưa bật") +
      row("Mạng nội bộ lớp", false, "Giai đoạn 3") +
      row("Đám mây (Firebase)", false, "Không dùng") +
      row("Bậc sản phẩm", true, TIER_LABEL[st.tier] || st.tier);
  }
  return row("Ứng dụng", true, st.app) +
    row("MediaPipe", d.mediapipe, d.mediapipe ? "Đã cài" : "Chưa cài", !d.mediapipe) +
    row("YOLO (ultralytics)", d.ultralytics, d.ultralytics ? "Đã cài" : "Chưa cài", !d.ultralytics) +
    row("OpenCV", d.cv2, d.cv2 ? "Đã cài" : "Chưa cài", !d.cv2) +
    row("Model khuôn mặt", st.model.present, st.model.present ? "Sẵn sàng" : "Thiếu file", !st.model.present) +
    row("Cơ sở dữ liệu SQLite", st.database.ready, st.database.ready ? "Sẵn sàng" : "Lỗi", !st.database.ready) +
    row("Camera", st.camera_running, st.camera_running ? "Đang chạy" : "Chưa bật") +
    row("Mạng nội bộ lớp", false, "Giai đoạn 3") +
    row("Đám mây (Firebase)", false, "Không dùng") +
    row("Bậc sản phẩm", true, TIER_LABEL[st.tier] || st.tier);
}

/* demo_seed/demo_purge ghi detail là JSON kết quả đếm được; đọc thành câu tiếng Việt cho
   dễ nhìn trên bảng hoạt động. Các hành động khác có detail ngắn sẵn nên giữ nguyên. */
function auditDetail(a) {
  const d = a.detail || "";
  if (a.action !== "demo_seed" && a.action !== "demo_purge") return d;
  try {
    const r = JSON.parse(d);
    if (r.already) return "dữ liệu mẫu đã có sẵn";
    const parts = [];
    if (r.students != null) parts.push(r.students + " học sinh");
    if (r.sessions != null) parts.push(r.sessions + " phiên");
    if (r.alerts != null) parts.push(r.alerts + " cảnh báo");
    if (parts.length) return (a.action === "demo_seed" ? "đã nạp " : "đã xóa ") + parts.join(", ");
  } catch (e) { /* detail không phải JSON thì giữ nguyên chuỗi gốc */ }
  return d;
}

function activityRows() {
  const items = [];
  for (const a of (S.audit || []).slice(0, 30)) {
    const detail = auditDetail(a);
    items.push({ms: a.at_ms, tint: "tint-blue", icon: "clock",
      text: (ACTION_LABEL[a.action] || a.action) + (detail ? " · " + detail : "")});
  }
  for (const a of (S.recentAlerts || []).slice(0, 30)) {
    items.push({ms: a.raised_at_wall_ms, tint: "tint-red", icon: "bell",
      text: (a.student_name || a.student_id) + ": " + a.message});
  }
  // Khuyến nghị sư phạm / dấu hiệu Tic (classroom.py ghi vào class_live JSON): lớp riêng,
  // gắn chữ "khuyến nghị" để không lẫn với 15 mức cảnh báo parity của điện thoại.
  const adv = ((S.live && S.live.board) || {}).advisories || [];
  for (const a of adv.slice(-30)) {
    const who = a.student_name || a.student_id || ("track " + a.track_id);
    const text = a.kind === "TIC_WATCH"
      ? who + ": " + (a.label || "dấu hiệu giật lặp lại") + " (khuyến nghị theo dõi)"
      : who + ": mất tập trung theo ngữ cảnh" + (a.reason ? " — " + a.reason : "") + " (khuyến nghị)";
    items.push({ms: a.wall_ms, tint: a.kind === "TIC_WATCH" ? "tint-orange" : "tint-red", icon: "warn", text});
  }
  items.sort((x, y) => y.ms - x.ms);
  if (!items.length) return '<div class="muted small">Chưa có hoạt động nào được ghi.</div>';
  return items.slice(0, 8).map((it) =>
    '<div class="list-row ' + it.tint + '"><span class="list-ico">' + ico(it.icon) + "</span>" +
    '<span class="list-time">' + fmtTime(it.ms) + '</span><span class="list-text">' + esc(it.text) + "</span></div>").join("");
}

/* Đồng bộ luồng MJPEG với trạng thái camera: chỉ gắn src khi đang chạy, gỡ src khi dừng
   để trình duyệt đóng kết nối stream. Khung hình không bao giờ đi qua đĩa. */
function syncStream(imgId, emptyId, fpsId) {
  const img = $(imgId), empty = $(emptyId), fps = $(fpsId);
  if (!img) return;
  const cam = S.live && S.live.camera;
  const running = !!(cam && cam.running);
  if (running) {
    img.hidden = false;
    if (!img.getAttribute("src")) img.src = (window.FC_API_BASE || "") + "/api/camera/stream";
    empty.hidden = true;
    if (fps) { fps.hidden = false; fps.textContent = num(S.live.fps, 1) + " FPS"; }
    watchStream(img);
  } else {
    unwatchStream(img);
    img.removeAttribute("src");
    // Không có src thì Chrome vẫn vẽ alt + icon ảnh hỏng cho img đã đặt cỡ bằng CSS,
    // nên phải ẩn hẳn thẻ img khi camera dừng (luật [hidden] trong app.css lo phần còn lại).
    img.hidden = true;
    empty.hidden = false;
    if (fps) fps.hidden = true;
    let msg = window.FC_MODE === "demo"
      ? "Camera demo chưa bật. Bấm <b>Bắt đầu</b> để chạy mô phỏng lớp học ngay trong trình duyệt."
      : "Camera chưa bật. Bấm <b>Bắt đầu</b> để đo bằng webcam máy này.";
    if (window.FC_MODE === "web") {
      // Khung đo nhúng nằm ở màn Camera/Tổng quan; ô này chỉ còn là chỗ dự phòng nên nói đúng chỗ đo.
      msg = 'Camera đo ngay trong dashboard. ' +
        (MEASURE_HREF
          ? 'Vào <a href="#camera"><b>màn Camera</b></a> rồi bấm <b>Bắt đầu đo</b> — đo xong bấm Dừng để chốt phiên vào SQLite.'
          : "Bản dashboard-only không có màn đo đi kèm; muốn đo thì chạy app PC hoặc bản web đầy đủ.");
    } else {
      if (cam && cam.error) msg = "<b>Camera gặp lỗi:</b> " + esc(cam.error);
      if (window.FC_MODE !== "demo") {
        const miss = [];
        if (S.system && S.system.deps) {
          if (!S.system.deps.mediapipe) miss.push("mediapipe");
          if (!S.system.deps.ultralytics) miss.push("ultralytics");
        }
        if (miss.length) msg += "<br><br>Thiếu thư viện: <b>" + miss.join(", ") +
          "</b><br>Cài: <code>python -m pip install " + miss.join(" ") + "</code>";
        if (S.system && S.system.model && !S.system.model.present) {
          msg += "<br><br>Thiếu model <b>face_landmarker.task</b> (chép từ app Android sang thư mục dự án).";
        }
      }
    }
    empty.innerHTML = "<div>" + ico("camera") + "<div>" + msg + "</div></div>";
  }
}

/* Một số trình duyệt chặn thẻ <img> kéo luồng MJPEG từ máy cục bộ (quyền mạng nội bộ
   cấp cho fetch nhưng không cấp cho ảnh). Nếu camera ĐANG chạy mà ảnh không tải được
   sau 3 giây, chuyển sang kéo từng JPEG qua /api/camera/snapshot bằng fetch — đường
   fetch đã được cấp quyền thì mặt vẫn hiện, chỉ chậm hơn MJPEG một chút. */
function watchStream(img) {
  if (img._sw || img._poll) return;
  const t0 = Date.now();
  img._sw = setInterval(() => {
    if (img.naturalWidth > 0) { clearInterval(img._sw); img._sw = null; return; }
    if (Date.now() - t0 < 3000) return;
    clearInterval(img._sw); img._sw = null;
    img._poll = setInterval(async () => {
      try {
        const r = await fetch((window.FC_API_BASE || "") + "/api/camera/snapshot", {cache: "no-store"});
        if (!r.ok) return;
        const u = URL.createObjectURL(await r.blob());
        const old = img.src;
        img.src = u;
        if (old && old.indexOf("blob:") === 0) URL.revokeObjectURL(old);
      } catch (e) { /* nhịp sau thử lại */ }
    }, 700);
  }, 1000);
}
function unwatchStream(img) {
  if (img._sw) { clearInterval(img._sw); img._sw = null; }
  if (img._poll) { clearInterval(img._poll); img._poll = null; }
  if (img.src && img.src.indexOf("blob:") === 0) URL.revokeObjectURL(img.src);
}

function cameraButtons(prefix) {
  return '<div class="cam-buttons">' +
    '<button class="btn btn-primary" id="' + prefix + '-start">' + ico("play") + " Bắt đầu</button>" +
    '<button class="btn" id="' + prefix + '-stop">' + ico("stop") + " Dừng</button>" +
    '<button class="btn" id="' + prefix + '-shot" title="Chụp MÀN HÌNH qua quyền trình duyệt - không lưu khung hình mặt">' +
    ico("photo") + " Chụp ảnh màn hình</button>" +
    '<button class="btn" id="' + prefix + '-full">' + ico("expand") + " Toàn màn hình (F)</button></div>";
}

function wireCameraButtons(prefix, wrapId) {
  $(prefix + "-start").onclick = startCamera;
  $(prefix + "-stop").onclick = stopCamera;
  $(prefix + "-shot").onclick = screenshot;
  $(prefix + "-full").onclick = () => fullscreen($(wrapId));
}

/* ============================== màn 1: Tổng quan ============================== */
function renderOverview() {
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("home") + 'Tổng quan</h2>' +
    '<p id="ov-sub">Lớp học · năm học 2026–2027</p></div>' +
    '<div class="field" style="margin:0"><label>Ngày xem</label><input type="date" id="ov-date" value="' + S.date + '"></div></div>' +
    '<div class="grid grid-stats five">' +
    statCard("blue", "users", "ov-students", "--", "Học sinh", "ov-students-sub", "trong lớp") +
    statCard("green", "camera", "ov-camera", "--", "Camera", "ov-camera-sub", "") +
    statCard("orange", "activity", "ov-ai", "--", "Trạng thái hoạt động", "ov-ai-sub", "MediaPipe + AI") +
    statCard("red", "bell", "ov-alerts", "--", "Cảnh báo hôm nay", "ov-alerts-sub", "") +
    // Biến đếm "đang mất tập trung" (OFF_TASK theo ngữ cảnh khẩu lệnh GV) — bấm vào mở
    // danh sách cụ thể từng em. Không đếm WATCH (chưa vượt cửa sổ) và không lẫn cảnh báo parity.
    statCard("red", "warn", "ov-offtask", "--", "Đang mất tập trung", "ov-offtask-sub", "bấm để xem em nào") +
    "</div>" +
    seatMapCardHtml() +
    '<div class="grid grid-overview">' +
    (window.FC_MODE === "web"
      ? '<div class="card"><h3>' + ico("camera") + 'Camera thời gian thực — đo tại đây</h3>' +
        '<div class="cam-wrap live-stage" data-live-slot="overview"></div>' +
        '<div class="cam-buttons mt">' +
        '<button class="btn btn-primary" id="ov-start">' + ico("play") + " Bắt đầu đo</button>" +
        '<button class="btn" id="ov-stop">' + ico("stop") + " Dừng &amp; chốt phiên</button>" +
        '<a class="btn" href="#camera" title="Mở danh sách để thêm hoặc chọn học sinh">' +
        ico("users") + " Gán tên HS</a></div>" +
        '<div class="avg-note">Chọn tên ở tab Camera → Danh sách em trước khi đo. Chưa có tên thì bấm Thêm em.</div>' +
        '<div class="avg-note" id="ov-cam-note"></div></div>'
      : '<div class="card"><h3>' + ico("camera") + 'Camera thời gian thực</h3>' +
        '<div class="cam-wrap" id="ov-wrap"><img id="ov-stream" alt="Luồng camera" hidden>' +
        '<div class="cam-empty" id="ov-empty"></div><span class="fps-chip" id="ov-fps" hidden></span></div>' +
        cameraButtons("ov") + '<div class="avg-note" id="ov-cam-note"></div>' +
        '<div class="people-grid mt" id="cam-people"></div></div>') +
    '<div class="card"><h3>' + ico("target") + 'Chỉ số trung bình lớp</h3><div id="ov-avgs"></div>' +
    '<div class="avg-note" id="ov-avg-note"></div></div>' +
    '<div class="card"><h3>' + ico("chart") + 'Xu hướng chú ý 30 phút</h3><div id="ov-trend"></div>' +
    '<div class="avg-note">Mức chú ý trung bình lớp (SEI 0–100); khi camera đang đo, các thanh cập nhật liên tục theo thời gian thực; khi dừng, bảng hiện phiên đã chốt lấy từ SQLite.</div></div>' +
    "</div>" +
    '<div class="grid grid-overview-2">' +
    '<div class="card"><h3>' + ico("clock") + 'Hoạt động gần đây</h3><div id="ov-activity"></div></div>' +
    '<div class="card"><h3>' + ico("info") + 'Trạng thái hệ thống</h3><div id="ov-system"></div></div>' +
    "</div>";
  $("ov-date").onchange = (e) => { S.date = e.target.value || todayStr(); tickFast(); };
  const offCard = $("ov-offtask").closest(".stat");
  offCard.classList.add("stat-click");
  offCard.title = "Bấm để xem em nào đang mất tập trung";
  offCard.setAttribute("role", "button");
  offCard.tabIndex = 0;
  offCard.onclick = showOffTaskList;
  offCard.onkeydown = (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); showOffTaskList(); } };
  if (window.FC_MODE !== "web") wireCameraButtons("ov", "ov-wrap");
  else {
    $("ov-start").onclick = liveStart;
    $("ov-stop").onclick = liveStop;
    livePlaceSoon();
  }
  updateOverviewParts();
  liveOverviewPaint();
  seatMapPaint(true);
  $("ov-system").innerHTML = systemRows();
  $("ov-activity").innerHTML = activityRows();
}

function statCard(color, icon, numId, numText, label, subId, subText) {
  return '<div class="card stat"><div class="stat-icon ' + color + '">' + ico(icon) + "</div>" +
    '<div><div class="stat-num" id="' + numId + '">' + numText + '</div>' +
    '<div class="stat-label">' + label + '</div>' +
    '<div class="stat-sub" id="' + subId + '">' + subText + "</div></div></div>";
}

function updateOverviewParts() {
  const ov = S.overview;
  if (!ov || !$("ov-students")) return;
  $("ov-sub").textContent = ov.class_label + " · năm học " + (ov.school_year || "--") + " · ngày " + fmtDate(new Date(S.date + "T00:00:00").getTime());
  $("ov-students").textContent = ov.students;
  $("ov-students-sub").textContent = "em trong lớp " + ov.class_label;
  const camOn = ov.camera.state === "on" || ov.camera.state === "live";
  // Bản web: camera thật là khung đo nhúng, không phải trạng thái đọc-only của cầu nối db.
  const webCam = window.FC_MODE === "web" ? liveOn() : camOn;
  $("ov-camera").textContent = webCam ? "1/1" : "0/1";
  $("ov-camera-sub").textContent = window.FC_MODE === "web"
    ? (liveOn() ? "Đang đo trong dashboard" : (LIVE.state && LIVE.state.starting) ? "Đang mở camera…" : "Chưa bật — bấm Bắt đầu đo")
    : ov.camera.note;
  const aiText = {active: "Đang chạy", ready: "Sẵn sàng", missing: "Chưa đủ"}[ov.ai.state] || ov.ai.state;
  $("ov-ai").textContent = aiText;
  $("ov-ai-sub").textContent = ov.ai.note;
  $("ov-alerts").textContent = ov.alerts_today;
  $("ov-alerts-sub").textContent = ov.alerts_unseen + " chưa xem";
  $("ov-alerts").closest(".stat").classList.toggle("danger", ov.alerts_unseen > 0);
  updateOffTaskCard();
  const a = ov.averages || {};
  // Đang đo thì liveOverviewPaint lo bảng này; vẽ số đã lưu ở đây sẽ đè mất số trực tiếp mỗi 5 giây.
  if (!cameraIsRunning()) paintOverviewAverages(a, a.label || "chưa có dữ liệu");
  if (window.FC_MODE === "web") { livePaint(); return; }
  $("ov-cam-note").textContent = ov.camera.note;
  syncStream("ov-stream", "ov-empty", "ov-fps");
  liveOverviewPaint(S.live);
}

/* ============================== đo trực tiếp trong dashboard ============================== */
/* Camera chạy NGAY TẠI ĐÂY. Khung đo là ../index.html?embed=1 trong một iframe cùng origin: cùng
   engine đã kiểm thử (vòng lặp, gắn tên, thói quen, ghi SQLite), không phải bản sao. Dashboard quanh
   nó vẫn chỉ ĐỌC db — khung đo là người ghi duy nhất, và nó báo lên mỗi khi chốt phiên để trang này
   đọc lại tệp.

   Iframe bị đổi chỗ trong DOM là bị TẢI LẠI, nên nó nằm cố định trong một lớp #live-layer ở body và
   chỉ được căn theo ô giữ chỗ [data-live-slot] của màn đang mở. Màn khác không có ô thì lớp bị đỗ
   ngoài màn hình (không display:none) để vòng đo vẫn chạy khi giáo viên sang màn Báo cáo. */
const LIVE = {
  mod: null, host: null, frame: null, layer: null, veil: null,
  state: null, logs: [], logPainted: 0,
  boot: "idle", bootError: "",
  waiters: [], lastRect: "", placeQueued: false,
  alertSeen: "", kidsAt: 0, kidsKey: "", rosterKey: "", dbKey: "", camKey: "", veilKey: "",
  trendRows: [], trendAt: 0, avgAt: 0, seiHist: [], lowSince: 0, lowToastAt: 0,
};
const SIT_IDX = ["FORWARD", "DOWN", "TILT", "TURNED", "DOZING", "UNKNOWN"];

function liveAvailable() { return window.FC_MODE === "web" && !!MEASURE_HREF; }
function liveOn() { return !!(LIVE.state && LIVE.state.running); }

// The camera layer tracks its slot, so a Camera screen scrolled down to the roster carries the picture
// (and its "Bắt đầu đo") above the viewport. Every jump to the Camera screen must land at the top, or
// the pill and the banner button send the teacher to a screen whose start button is off-screen.
function liveShowCamera() {
  if (location.hash !== "#camera") {
    location.hash = "#camera";
  } else {
    const v = $("view");
    if (v) v.scrollTop = 0;
    LIVE.lastRect = "";
    livePlaceSoon();
  }
}

async function liveBoot(retry) {
  if (!liveAvailable() || LIVE.host || (LIVE.boot === "loading" && !retry)) return;
  LIVE.boot = "loading";
  liveVeilPaint();
  try {
    LIVE.mod = await import("../js/liveframe.mjs");
  } catch (e) {
    LIVE.boot = "error";
    LIVE.bootError = "Không nạp được js/liveframe.mjs: " + ((e && e.message) || e);
    liveVeilPaint();
    return;
  }
  const layer = document.createElement("div");
  layer.id = "live-layer";
  layer.className = "parked";
  layer.style.left = "-20000px";
  layer.style.top = "0px";
  layer.innerHTML =
    '<iframe id="live-frame" title="Khung đo trực tiếp" ' +
    'allow="camera; microphone; fullscreen; display-capture"></iframe>' +
    '<div class="live-veil" id="live-veil"></div>';
  document.body.appendChild(layer);
  LIVE.layer = layer;
  LIVE.frame = layer.querySelector("#live-frame");
  LIVE.veil = layer.querySelector("#live-veil");
  LIVE.host = LIVE.mod.connectHost(LIVE.frame, {
    onReady: (s) => { LIVE.boot = "ready"; LIVE.state = s; livePaint(); },
    onState: (s) => { LIVE.state = s; livePaint(); },
    onEvent: liveEvent,
    onLog: (line) => {
      LIVE.logs.push({at: Date.now(), line: String(line)});
      if (LIVE.logs.length > 240) { LIVE.logs.shift(); LIVE.logPainted = Math.max(0, LIVE.logPainted - 1); }
      liveLogPaint();
    },
    onReply: liveReply,
  });
  LIVE.frame.src = MEASURE_HREF + "?embed=1";
  LIVE.mod.watchLease(() => livePaint());
  addEventListener("resize", livePlaceSoon);
  document.addEventListener("scroll", livePlaceSoon, true);
  document.addEventListener("fullscreenchange", () => {
    const fs = document.fullscreenElement === LIVE.layer;
    LIVE.layer.classList.toggle("fs", fs);
    // Phần tử toàn màn hình phải phủ kín: toạ độ căn theo ô giữ chỗ không còn đúng nữa.
    if (fs) LIVE.layer.style.cssText = "";
    LIVE.lastRect = "";
    livePlaceSoon();
  });
  setInterval(livePlace, 250);
  liveVeilPaint();
}

function livePlace() {
  const layer = LIVE.layer;
  if (!layer || document.fullscreenElement === layer) return;
  const slot = document.querySelector("[data-live-slot]");
  if (!slot) {
    if (!layer.classList.contains("parked") || layer.style.left !== "-20000px") {
      layer.classList.add("parked");
      layer.style.left = "-20000px";
      layer.style.top = "0px";
      LIVE.lastRect = "parked";
    }
    return;
  }
  layer.classList.remove("parked");
  const r = slot.getBoundingClientRect();
  const key = [r.left, r.top, r.width, r.height].map((v) => Math.round(v)).join(",");
  if (key === LIVE.lastRect) return;
  LIVE.lastRect = key;
  layer.style.left = r.left + "px";
  layer.style.top = r.top + "px";
  layer.style.width = r.width + "px";
  layer.style.height = r.height + "px";
}
function livePlaceSoon() {
  if (LIVE.placeQueued) return;
  LIVE.placeQueued = true;
  requestAnimationFrame(() => { LIVE.placeQueued = false; livePlace(); });
}

/* Lệnh xuống khung đo, chờ trả lời. Model nạp lâu trên máy yếu nên start/restart được cho tới 3 phút;
   hết hạn thì nói thẳng là khung không trả lời thay vì treo nút vĩnh viễn. */
function liveCmd(cmd, payload, timeoutMs) {
  return new Promise((resolve) => {
    if (!LIVE.host || !LIVE.host.send(cmd, payload)) {
      resolve({ok: false, error: "Khung đo chưa sẵn sàng."});
      return;
    }
    const w = {cmd, resolve};
    LIVE.waiters.push(w);
    setTimeout(() => {
      const i = LIVE.waiters.indexOf(w);
      if (i >= 0) { LIVE.waiters.splice(i, 1); resolve({ok: false, error: "Khung đo không trả lời lệnh “" + cmd + "”."}); }
    }, timeoutMs || 20000);
  });
}
function liveReply(cmd, ok, result) {
  const i = LIVE.waiters.findIndex((w) => w.cmd === cmd);
  if (i < 0) return;
  LIVE.waiters.splice(i, 1)[0].resolve(ok ? {ok: true, result} : {ok: false, error: String(result)});
}

function liveEvent(kind, data) {
  data = data || {};
  if (kind === "db") liveReloadDb();
  else if (kind === "started") toast("Đã mở camera — đo ngay trong dashboard", "ok");
  else if (kind === "stopped") toast("Đã dừng đo — phiên được chốt vào SQLite", "ok");
  else if (kind === "refused" || kind === "error") toast(data.message || "Khung đo gặp lỗi", "err");
  livePaint();
}
async function liveReloadDb() {
  try { if (window.FC_WEBDB && window.FC_WEBDB.reload) await window.FC_WEBDB.reload(); }
  catch (e) { /* giữ số cũ, nhịp sau đọc lại */ }
  await tickFast();
  tickSlow();
}

function liveCfgFromUI() {
  const s = LIVE.state || {};
  const cur = s.cfg || {};
  const val = (id, d) => { const el = $(id); return el ? el.value : d; };
  return {
    faces: val("lv-faces", cur.faces), res: val("lv-res", cur.res),
    delegate: val("lv-delegate", cur.delegate), rate: val("lv-rate", cur.rate),
    cam: val("lv-cam", cur.cam), sens: val("lv-sens", s.sensLevel),
  };
}

async function liveStart() {
  if (!LIVE.host) { toast("Khung đo chưa nạp xong — thử lại sau giây lát.", "err"); return; }
  if (liveOn()) { toast("Camera đang đo rồi.", "ok"); return; }
  toast("Đang mở camera và nạp model…", "ok");
  const r = await liveCmd("start", liveCfgFromUI(), 180000);
  if (!r.ok) toast(r.error, "err");
  else if (r.result && r.result.error) toast(r.result.error, "err");
  livePaint();
}
async function liveStop() {
  if (!LIVE.host) return;
  const r = await liveCmd("stop", {}, 20000);
  if (!r.ok) toast(r.error, "err");
  livePaint();
  if (r.ok) { await tickFast(); await tickSlow(); }
}
async function liveRestart(why) {
  if (!LIVE.host) return;
  toast((why || "Đổi thông số") + " — đang mở lại camera…", "ok");
  const r = await liveCmd("restart", liveCfgFromUI(), 180000);
  if (!r.ok) toast(r.error, "err");
  else if (r.result && r.result.error) toast(r.result.error, "err");
  livePaint();
}
async function liveToggle(key) {
  const r = await liveCmd("toggle", {key}, 8000);
  if (!r.ok) { toast(r.error, "err"); return; }
  livePaint();
}

/* ---- vẽ ---- */
function livePaint() {
  liveVeilPaint();
  livePillPaint();
  const s = LIVE.state;
  if (!s) return;
  liveAlertCheck(s);
  try { lessonRecord(); } catch (e) { /* ghi buổi học không được làm hỏng vẽ số đo */ }
  try { seatMapPaint(); } catch (e) { /* sơ đồ lớp không được làm hỏng vẽ số đo */ }
  const note = $("cam-status-note");
  if (note) note.textContent = s.stat || "";
  const ovnote = $("ov-cam-note");
  if (ovnote) ovnote.textContent = liveOn()
    ? "Đang đo · " + num(s.fps, 0) + " FPS · " + s.faces + " mặt · " + num(s.cost, 0) + " ms/khung"
    : (s.error || "Camera chưa bật — bấm Bắt đầu đo.");
  liveOverviewPaint(s);
  liveNumbersPaint(s);
  liveKidsPaint(s);
  liveRosterPaint(s);
  liveDbPaint(s);
  liveLogPaint();
  liveControlsPaint(s);
}

function liveAlertCheck(s) {
  const h = s.h;
  const code = h && h.alertMessage ? String(h.alertMessage) : "";
  if (!code) { LIVE.alertSeen = ""; return; }
  if (code === LIVE.alertSeen) return;
  LIVE.alertSeen = code;
  // alertHeld = tình huống đang hoãn cảnh báo (đầu vẫn hướng màn hình): hoãn thì không kêu.
  if (h.alertHeld) return;
  beep();
  toast((KIND_LABEL[code] || code) + (s.bound ? " · " + s.bound.name : ""), "err");
}

function liveVeilPaint() {
  const v = LIVE.veil;
  if (!v) return;
  const s = LIVE.state;
  if (s && s.running) { v.hidden = true; LIVE.veilKey = ""; return; }
  v.hidden = false;
  let title = "Khung đo trực tiếp", body = "", btn = "";
  if (LIVE.boot === "loading") {
    title = "Đang nạp khung đo…";
    body = "Engine đo (MediaPipe + YOLO) nạp trong khung này. Lần đầu có thể mất chục giây.";
  } else if (LIVE.boot === "error") {
    title = "Không mở được khung đo";
    body = esc(LIVE.bootError);
    btn = '<button class="btn btn-primary" id="lv-veil-reload">Thử nạp lại</button>';
  } else if (s && s.starting) {
    title = "Đang mở camera…";
    body = "Chọn <b>Cho phép</b> khi trình duyệt hỏi quyền camera. Model đang nạp.";
  } else if (s && s.error) {
    title = "Camera chưa mở được";
    body = esc(s.error);
    btn = '<button class="btn btn-primary" id="lv-veil-start">' + ico("play") + " Thử lại</button>";
  } else if (s && s.lease && s.lease.surface) {
    title = "Một cửa sổ khác đang đo";
    body = "Quyền ghi db thuộc về <b>" + esc(s.lease.surface === "dashboard" ? "dashboard lớp học" : "màn đo") +
      "</b>. Hai cửa sổ cùng ghi sẽ đè mất phiên của nhau — dừng cửa sổ kia rồi quay lại đây.";
  } else {
    body = "Camera và toàn bộ phép đo chạy ngay trong khung này, không phải chuyển sang trang khác. " +
      "Bấm <b>Bắt đầu đo</b>, chọn em ở cột phải, rồi bấm <b>Dừng</b> để chốt phiên vào SQLite.";
    btn = '<button class="btn btn-primary" id="lv-veil-start">' + ico("play") + " Bắt đầu đo</button>";
  }
  // The state arrives four times a second. Rewriting the veil's HTML on every one of them would replace
  // the button between a real mousedown and mouseup, and the click would land on the veil instead of on
  // "Bắt đầu đo" - so the markup is only rebuilt when the sentence actually changes.
  const key = title + "|" + body + "|" + btn;
  if (key === LIVE.veilKey) return;
  LIVE.veilKey = key;
  v.innerHTML = '<div class="live-veil-in">' + ico("camera") + "<h4>" + title + "</h4><p>" + body + "</p>" +
    (btn ? '<div class="cam-buttons">' + btn + "</div>" : "") + "</div>";
  const b = $("lv-veil-start");
  if (b) b.onclick = liveStart;
  const reload = $("lv-veil-reload");
  if (reload) reload.onclick = () => {
    if (LIVE.boot !== "error" || reload.disabled) return;
    reload.disabled = true;
    LIVE.boot = "loading";
    LIVE.bootError = "";
    LIVE.veilKey = "";
    liveVeilPaint();
    liveBoot(true);
  };
}

function livePillPaint() {
  const pill = $("chip-live");
  if (pill) {
    const s = LIVE.state;
    const on = liveOn();
    const busy = !!(s && s.starting);
    pill.className = "chip-live" + (on ? " on" : busy ? " busy" : "");
    pill.innerHTML = '<i class="dot ' + (on ? "dot-red" : busy ? "dot-orange" : "dot-grey") + '"></i>' +
      "<span>" + (on ? "ĐANG ĐO" : busy ? "ĐANG MỞ…" : "CHƯA ĐO") + "</span>" +
      (on && s ? "<b>" + s.faces + " mặt</b>" : "");
    pill.title = on ? "Đang đo trực tiếp trong dashboard — bấm Dừng ở màn Camera để chốt phiên"
      : "Chưa đo. Vào màn Camera rồi bấm Bắt đầu đo.";
    pill.onclick = liveShowCamera;
  }
  const snd = $("chip-sound");
  if (snd) {
    snd.innerHTML = ico(S.soundOn ? "bell" : "bell-off") + "<span>" + (S.soundOn ? "Âm: Bật" : "Âm: Tắt") + "</span>";
    snd.title = S.soundOn ? "Tắt toàn bộ âm cảnh báo" : "Bật âm cảnh báo";
    snd.setAttribute("aria-pressed", S.soundOn ? "true" : "false");
    snd.classList.toggle("off", !S.soundOn);
  }
}

function seiTone(v) {
  return v == null || isNaN(v) ? "var(--ink-soft)" : v >= 70 ? "var(--green)" : v >= 45 ? "var(--orange)" : "var(--red)";
}

function liveNumbersPaint(s) {
  const big = $("lv-sei");
  if (!big) return;
  const h = s.h;
  const sei = h ? h.sei : null;
  big.textContent = sei == null || isNaN(sei) ? "--" : Math.round(sei);
  big.style.color = seiTone(sei);
  const sub = $("lv-sei-sub");
  if (sub) {
    sub.textContent = !s.running ? "chưa đo"
      : !h ? "không thấy mặt nào trong khung"
      : !h.calibrated ? "đang hiệu chuẩn " + (h.calibProgress || 0) + "/" + (h.calibTotal || 60) + " khung — ngồi thẳng, nhìn màn hình"
      : "TB " + num(h.averageSei, 0) + " · thấp nhất " + num(h.minSei, 0) +
        (h.personalSeiMean != null ? " · thói quen " + num(h.personalSeiMean, 0) : "") +
        " · " + Math.floor((h.sessionSeconds || 0) / 60) + " phút";
  }
  const box = $("lv-kv");
  if (box) {
    const kv = (label, value, tone) =>
      '<div class="lv-kv"><span>' + label + '</span><b' + (tone ? ' style="color:' + tone + '"' : "") + ">" + value + "</b></div>";
    if (!s.running) {
      box.innerHTML = '<div class="muted small">Bấm Bắt đầu đo để thấy EAR, MAR, góc đầu, PERCLOS, khoảng cách và nhịp chớp mắt của em đang chọn.</div>';
    } else if (!h) {
      box.innerHTML = '<div class="muted small">Chưa thấy khuôn mặt. Ngồi vào khung hình, đủ sáng, mặt cách camera khoảng 40–70 cm.</div>';
    } else {
      const sit = SIT_LABEL[SIT_IDX[h.situation] || "UNKNOWN"] || ["--", "warn"];
      box.innerHTML =
        kv("EAR trái / phải", num(h.ear1, 2) + " / " + num(h.ear2, 2)) +
        kv("Độ mở mắt", num(h.openness, 2), h.closed ? "var(--red)" : null) +
        kv("MAR (miệng)", num(h.mar, 2), h.yawnConfirmed ? "var(--orange)" : null) +
        kv("Pitch / Yaw / Roll", num(h.pitch, 0) + "° / " + num(h.yaw, 0) + "° / " + num(h.roll, 0) + "°") +
        kv("PERCLOS", num(h.perclos, 0) + "%", (h.perclos || 0) > 30 ? "var(--red)" : null) +
        kv("Chớp mắt", (h.blinkTotal || 0) + " lần · " + num(h.avgBlinkMs, 0) + " ms") +
        kv("Nhịp thở (ước lượng)", h.bpm == null ? "--" : num(h.bpm, 0) + " /phút") +
        kv("Khoảng cách", num(h.distanceCm, 0) + " cm", h.tooClose ? "var(--red)" : null) +
        kv("Lệch hướng nhìn", num(h.gazeHDev, 2) + " / " + num(h.gazeVDev, 2), h.driftNow ? "var(--orange)" : null) +
        kv("Tư thế", '<span class="sit-chip ' + sit[1] + '">' + sit[0] + "</span>") +
        kv("Mệt / mãn tính", num(h.fatigue, 0) + " · mức " + (h.chronicLevel || 0)) +
        kv("Chất lượng số đo", num(h.quality, 2) + (h.reliable ? "" : " (kém tin cậy)"), h.reliable ? null : "var(--orange)") +
        (s.bound ? kv("Em đang đo", esc(s.bound.name) + " · " + esc(s.bound.identity === "manual" ? "gắn tay" : "tự nhận diện")) : "") +
        (s.cls ? kv("Bối cảnh lớp", esc(s.cls.label || "--")) : "") +
        (s.penalty ? kv("Phạt bối cảnh", "-" + num(s.penalty, 0) + " SEI", "var(--orange)") : "");
    }
  }
  const spark = $("lv-spark");
  if (spark) {
    const hist = (s.seiHistory || []).slice(-120);
    spark.innerHTML = hist.length > 1
      ? lineChart(hist, hist.map((_, i) => (i % 20 === 0 ? String(i) : "")), {height: 96, yMin: 0, yMax: 100, color: "var(--purple)"})
      : '<div class="chart-empty">Đường chú ý hiện sau khi hiệu chuẩn xong (khoảng 60 khung).</div>';
  }
  const al = $("lv-alert");
  if (al) {
    const code = h && h.alertMessage;
    if (s.running && code) {
      al.hidden = false;
      al.className = "live-alert" + (h.alertHeld ? " held" : "");
      al.innerHTML = ico(h.alertHeld ? "clock" : "warn") + "<span><b>" + esc(KIND_LABEL[code] || code) + "</b>" +
        (h.alertHeld ? " — đang hoãn vì đầu vẫn hướng màn hình" : "") +
        (s.bound ? " · " + esc(s.bound.name) : "") + "</span>";
    } else { al.hidden = true; al.innerHTML = ""; }
  }
  const cal = $("lv-calib");
  if (cal) {
    const need = s.running && h && !h.calibrated;
    cal.hidden = !need;
    if (need) {
      const pct = Math.max(0, Math.min(100, (h.calibProgress || 0) / (h.calibTotal || 60) * 100));
      cal.innerHTML = '<div class="avg-bar"><div class="avg-fill" style="width:' + pct.toFixed(0) +
        '%;background:var(--blue)"></div></div><span class="muted small">Hiệu chuẩn ' + (h.calibProgress || 0) +
        "/" + (h.calibTotal || 60) + " khung — giữ mặt thẳng, đủ sáng.</span>";
    }
  }
}

function liveKidsPaint(s) {
  const box = $("cam-people");
  if (!box) return;
  if (S.hidePeople) { box.innerHTML = ""; return; }
  const kids = s.kids || [];
  const key = kids.map((k) => k.name).join("|") + "#" + kids.length;
  const now = Date.now();
  if (key === LIVE.kidsKey && now - LIVE.kidsAt < 500) return;
  LIVE.kidsKey = key; LIVE.kidsAt = now;
  if (!s.running || !kids.length) {
    box.innerHTML = '<div class="card muted small">' + (s.running
      ? "Chưa có em nào được gắn tên trong khung. Chọn em ở cột phải để máy đo và ghi phiên cho em đó."
      : "Bật camera để xem thẻ từng em.") + "</div>";
    return;
  }
  box.innerHTML = kids.map((k) => {
    const h = k.h || {};
    const sit = SIT_LABEL[SIT_IDX[h.situation] || "UNKNOWN"] || ["--", "warn"];
    return '<div class="card person-card">' +
      '<div class="person-head"><span class="person-name">' + esc(k.name || "Em chưa gắn tên") + "</span></div>" +
      '<div class="person-sei" style="color:' + seiTone(k.sei) + '">' + (k.sei == null || isNaN(k.sei) ? "--" : Math.round(k.sei)) +
      '<span class="muted" style="font-size:13px;font-weight:600"> /100</span></div>' +
      '<span class="sit-chip ' + sit[1] + '">' + sit[0] + "</span>" +
      '<div class="person-kv"><span>EAR / MAR</span><b>' + num(h.ear1, 2) + " / " + num(h.mar, 2) + "</b></div>" +
      '<div class="person-kv"><span>Pitch / Yaw</span><b>' + num(h.pitch, 0) + "° / " + num(h.yaw, 0) + "°</b></div>" +
      '<div class="person-kv"><span>PERCLOS</span><b>' + num(h.perclos, 0) + "%</b></div>" +
      '<div class="person-kv"><span>Khoảng cách</span><b>' + num(h.distanceCm, 0) + " cm</b></div>" +
      (h.alertMessage ? '<div class="person-alert">' + ico("warn") + " " + esc(KIND_LABEL[h.alertMessage] || h.alertMessage) + "</div>" : "") +
      "</div>";
  }).join("");
}

function liveRosterPaint(s) {
  const box = $("lv-roster");
  if (!box) return;
  const list = s.students || [];
  const key = list.map((r) => [r.id, r.name, r.sessions, r.faces, r.ready ? 1 : 0].join(":")).join("|") +
    "#" + (s.bound ? s.bound.id : "") + "#" + (s.learning ? s.learning.name + s.learning.got : "") +
    "#" + (s.running ? 1 : 0);
  if (key === LIVE.rosterKey) return;
  LIVE.rosterKey = key;
  if (!list.length) {
    box.innerHTML = '<div class="muted small">Chưa có em nào trong danh sách. Bấm <b>Thêm em</b> rồi gõ tên — ' +
      "có tên thì máy mới mở phiên và ghi vào SQLite khi bấm Dừng.</div>";
    return;
  }
  box.innerHTML = list.map((r) => {
    const on = s.bound && s.bound.id === r.id;
    return '<div class="roster-row' + (on ? " on" : "") + '">' +
      '<button class="roster-pick" data-lvbind="' + esc(r.id) + '" title="Gắn hồ sơ này với khuôn mặt đang đo">' +
      '<span class="roster-name">' + esc(r.name) + "</span>" +
      '<span class="roster-meta">' + r.sessions + " phiên · " + num(r.minutes, 0) + " phút · " +
      r.faces + " mẫu mặt" + (r.ready ? "" : " · chưa đủ thói quen") + "</span></button>" +
      (on ? '<span class="roster-now">' + (s.running ? "đang đo" : "đã chọn") + "</span>" : "") + "</div>";
  }).join("") +
  (s.learning ? '<div class="lock-note mt">' + ico("info") + " Đang học mặt <b>" + esc(s.learning.name) + "</b>: " +
    s.learning.got + "/" + s.learning.need + " mẫu" + (s.learning.skipped ? " · bỏ " + s.learning.skipped + " khung kém" : "") +
    (s.learning.note ? " — " + esc(s.learning.note) : "") + ". Giữ mặt thẳng 4 giây.</div>" : "") +
  (s.idStatus ? '<div class="avg-note">' + esc(s.idStatus) + "</div>" : "");
}

function liveDbPaint(s) {
  const box = $("lv-db");
  if (!box) return;
  const q = s.sql || {};
  const c = q.counts || {};
  const key = JSON.stringify([q.ready, q.where, q.failed, c.observation_sessions, c.students, c.feature_records, s.recPeersOn]);
  if (key === LIVE.dbKey) return;
  LIVE.dbKey = key;
  const row = (label, val, cls) => '<div class="sys-row"><span>' + label + '</span><span class="' + (cls || "") + '">' + val + "</span></div>";
  box.innerHTML =
    row("Nơi ghi", esc(q.where || (window.fcDb ? "userData/focuschange.db" : "IndexedDB trình duyệt này"))) +
    row("Trạng thái", q.failed ? '<span class="status-bad">Ghi lỗi — xem nhật ký</span>'
      : q.ready ? '<span class="status-ok">Sẵn sàng</span>' : '<span class="status-off">Chưa mở</span>') +
    row("Đã ghi", (c.observation_sessions || 0) + " phiên · " + (c.students || 0) + " em · " + (c.feature_records || 0) + " dòng chỉ số") +
    row("Ghi cùng lúc", s.recPeersOn ? '<span class="status-ok">Bật — mỗi em có tên một phiên</span>' : '<span class="status-off">Tắt — chỉ em đang chọn</span>') +
    '<div class="avg-note">Khung đo giữ cả tệp db trong bộ nhớ và ghi lại sau mỗi phiên đóng. Trang báo cáo đọc lại tệp đó, không ghi đè.</div>';
}

function liveLogPaint() {
  const box = $("lv-log");
  if (!box) return;
  if (LIVE.logPainted > LIVE.logs.length) LIVE.logPainted = 0;
  if (LIVE.logPainted === LIVE.logs.length) return;
  const add = LIVE.logs.slice(LIVE.logPainted);
  LIVE.logPainted = LIVE.logs.length;
  const html = add.map((l) => '<div class="log-line"><span>' + fmtTime(l.at) + "</span>" + esc(l.line) + "</div>").join("");
  if (!box.childNodes.length) box.innerHTML = html;
  else box.insertAdjacentHTML("beforeend", html);
  while (box.childNodes.length > 240) box.removeChild(box.firstChild);
  box.scrollTop = box.scrollHeight;
}

/* Công tắc/select phải phản chiếu đúng trạng thái khung đo, kể cả khi giáo viên đổi bằng phím tắt
   ngay trong khung — nên chúng được đồng bộ từ snapshot thay vì chỉ từ lần bấm cuối. */
function liveControlsPaint(s) {
  const set = (id, v) => { const el = $(id); if (el && el.value !== String(v)) el.value = String(v); };
  const chk = (id, v) => { const el = $(id); if (el && el.checked !== !!v) el.checked = !!v; };
  if (s.cfg) { set("lv-faces", s.cfg.faces); set("lv-res", s.cfg.res); set("lv-delegate", s.cfg.delegate); set("lv-rate", s.cfg.rate); }
  set("lv-sens", s.sensLevel);
  const cams = $("lv-cam");
  // Rebuilt on the LABELS too, not just the count: before camera permission every source is called
  // "Camera 1", and granting it renames them without changing how many there are.
  const camKey = (s.cameras || []).map((c) => c.id + ":" + c.label).join("|");
  if (cams && camKey !== LIVE.camKey) {
    LIVE.camKey = camKey;
    const cur = s.cfg ? s.cfg.cam : "";
    cams.innerHTML = (s.cameras || []).map((c) => '<option value="' + esc(c.id) + '">' + esc(c.label) + "</option>").join("");
    cams.value = cur;
  }
  const t = s.toggles || {};
  chk("lv-box", t.box); chk("lv-yolo", t.yolo); chk("lv-numbers", t.numbers); chk("lv-enhance", t.enhance);
  chk("lv-recpeers", s.recPeersOn); chk("lv-autoid", s.autoIdOn); chk("lv-sound", S.soundOn);
  const st = $("lv-state");
  if (st) {
    st.innerHTML = '<span class="' + (liveOn() ? "status-ok" : s.starting ? "status-off" : "status-off") + '"><i class="dot ' +
      (liveOn() ? "dot-green" : s.starting ? "dot-orange" : "dot-grey") + '"></i>' +
      (liveOn() ? "Đang đo" : s.starting ? "Đang mở camera" : "Chưa đo") + "</span>" +
      '<span class="muted small">' + (s.layer1 ? esc(s.layer1) : "chưa nạp bộ phát hiện") +
      " · " + esc(s.imgProc || "") + (s.camSize ? " · " + s.camSize.w + "×" + s.camSize.h : "") +
      " · nhịp " + (s.loopClock === "timer" ? "hẹn giờ (khung ẩn)" : "khung hình") + "</span>";
  }
  const b = $("lv-start"), d = $("lv-stop");
  if (b) b.disabled = !!s.starting || liveOn();
  if (d) d.disabled = !liveOn() && !s.starting;
  // The Overview carries the same two buttons, so they must not disagree with the Camera screen.
  const ob = $("ov-start"), od = $("ov-stop");
  if (ob) ob.disabled = !!s.starting || liveOn();
  if (od) od.disabled = !liveOn() && !s.starting;
}

/* ---- màn Camera: bản đo tại chỗ ---- */
function renderWebCamera() {
  const s = LIVE.state || {};
  const sel = (id, opts, cur) => '<select id="' + id + '">' + opts.map(([v, l]) =>
    '<option value="' + esc(v) + '"' + (String(v) === String(cur) ? " selected" : "") + ">" + esc(l) + "</option>").join("") + "</select>";
  const cfg = s.cfg || {faces: "2", res: "640", delegate: "GPU", rate: "4", cam: ""};
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("camera") + "Camera — đo trực tiếp</h2>" +
    "<p>Camera, phép đo và ghi SQLite chạy ngay trong trang này. Phím tắt: F, Y, O, G, H, N.</p></div>" +
    '<div class="lv-state" id="lv-state"></div></div>' +
    '<div class="card mt"><h3>' + ico("users") + 'Gắn tên học sinh</h3>' +
    '<div class="avg-note">Chọn tên để gắn hồ sơ với khuôn mặt đang đo. Chưa có tên thì bấm “Thêm em”; chọn xong hãy bắt đầu đo hoặc tiếp tục phiên.</div>' +
    '<div class="cam-buttons mt">' +
    '<button class="btn btn-sm btn-primary" id="lv-add">' + ico("edit") + " Thêm em</button></div>" +
    '<div id="lv-roster"></div>' +
    '<div class="cam-buttons mt">' +
    '<button class="btn btn-sm" id="lv-learn" title="Giữ mặt 4 giây trước camera để lần sau máy tự nhận ra">' + ico("camera") + " Học mặt em này</button>" +
    '<button class="btn btn-sm" id="lv-forget">' + ico("trash") + " Xóa hồ sơ đã học</button></div>" +
    '<div class="cam-buttons mt">' +
    '<button class="btn btn-sm" id="lv-export">' + ico("save") + " Xuất danh sách</button>" +
    '<button class="btn btn-sm" id="lv-import">' + ico("upload") + " Nhập danh sách</button>" +
    '<input type="file" id="lv-importfile" accept=".json,application/json" hidden></div>' +
    '<div class="avg-note">Em chưa gắn tên vẫn được đo trực tiếp nhưng KHÔNG lưu phiên. Gắn tên rồi bấm Dừng để chốt.</div></div>' +
    '<div class="grid grid-camera">' +
    '<div><div class="card"><h3>' + ico("camera") + 'Hình camera trực tiếp</h3>' +
    '<div class="cam-wrap live-stage" data-live-slot="camera"></div>' +
    '<div class="cam-buttons mt">' +
    '<button class="btn btn-primary" id="lv-start">' + ico("play") + " Bắt đầu đo</button>" +
    '<button class="btn" id="lv-stop">' + ico("stop") + " Dừng &amp; chốt phiên</button>" +
    '<button class="btn" id="lv-recal" title="Đặt lại mốc mắt mở và khoảng cách cho em đang ngồi">' + ico("refresh") + " Hiệu chuẩn lại</button>" +
    '<button class="btn" id="lv-next" title="Chọn em kế tiếp trong khung (N)">' + ico("users") + " Em kế tiếp (N)</button>" +
    '<button class="btn" id="lv-full">' + ico("expand") + " Toàn màn hình (F)</button></div>" +
    '<div class="live-alert" id="lv-alert" hidden></div>' +
    '<div class="avg-note" id="cam-status-note"></div>' +
    '<div id="lv-calib" class="mt" hidden></div>' +
    '<div class="people-grid" id="cam-people"></div></div>' +
    '<div class="card mt"><h3>' + ico("target") + 'Số đo trực tiếp của em đang chọn</h3>' +
    '<div class="lv-sei-row"><div class="lv-sei" id="lv-sei">--</div>' +
    '<div><div class="lv-sei-label">Mức chú ý (SEI /100)</div><div class="muted small" id="lv-sei-sub">chưa đo</div></div></div>' +
    '<div class="lv-kv-grid" id="lv-kv"></div>' +
    '<div id="lv-spark" class="mt"></div>' +
    '<div class="avg-note">Đường chú ý 120 mẫu gần nhất của riêng em đang chọn. Số chỉ để hỗ trợ, không dùng làm căn cứ kỷ luật duy nhất.</div></div>' +
    '<div class="card mt"><h3>' + ico("clock") + 'Nhật ký khung đo</h3><div class="live-log" id="lv-log"></div></div></div>' +
    '<div>' +
    '<div class="card mt"><h3>' + ico("settings") + 'Thông số đo</h3>' +
    '<div class="field"><label>Camera</label><select id="lv-cam"></select></div>' +
    '<div class="field"><label>Số mặt đo cùng lúc</label>' + sel("lv-faces", [[1, "1"], [2, "2"], [3, "3"], [4, "4"]], cfg.faces) + "</div>" +
    '<div class="field"><label>Độ phân giải xử lý</label>' + sel("lv-res", [[640, "640×480"], [480, "480×360"], [320, "320×240"]], cfg.res) + "</div>" +
    '<div class="field"><label>Bộ xử lý</label>' + sel("lv-delegate", [["GPU", "GPU (WebGL)"], ["CPU", "CPU"]], cfg.delegate) + "</div>" +
    '<div class="field"><label>Tần suất quét người</label>' + sel("lv-rate", [[2, "2 lần/giây"], [4, "4 lần/giây"], [8, "8 lần/giây"]], cfg.rate) + "</div>" +
    '<div class="field"><label>Độ nhạy cảnh báo</label>' + sel("lv-sens", [[0, "Thấp"], [1, "Vừa"], [2, "Cao"], [3, "Rất cao"]], s.sensLevel == null ? 1 : s.sensLevel) + "</div>" +
    '<div class="cam-buttons"><button class="btn btn-primary" id="lv-apply">' + ico("save") + " Áp dụng &amp; mở lại</button>" +
    '<button class="btn" id="lv-cams" title="Quét lại danh sách camera của máy">' + ico("refresh") + " Tìm camera</button></div>" +
    '<div class="lock-note mt">' + ico("info") + " Camera, số mặt, độ phân giải và bộ xử lý được đọc lúc NẠP model, " +
    "nên đổi chúng phải mở lại camera. Độ nhạy và tần suất quét áp dụng ngay.</div></div>" +
    '<div class="card mt"><h3>' + ico("eye") + 'Hiển thị &amp; ghi</h3>' +
    switchRow("lv-box", "Hộp mặt (F)", !!(s.toggles && s.toggles.box)) +
    switchRow("lv-yolo", "Hộp người / vật (Y)", !!(s.toggles && s.toggles.yolo)) +
    switchRow("lv-numbers", "Số liệu đè khung (O)", !!(s.toggles && s.toggles.numbers)) +
    switchRow("lv-enhance", "Tăng sáng (G)", !!(s.toggles && s.toggles.enhance)) +
    switchRow("lv-people", "Hiện thẻ từng em (H)", !S.hidePeople) +
    switchRow("lv-recpeers", "Ghi cùng lúc nhiều em", !!s.recPeersOn) +
    switchRow("lv-autoid", "Tự nhận diện khuôn mặt", !!s.autoIdOn) +
    switchRow("lv-sound", "Âm cảnh báo", S.soundOn) +
    '<div class="avg-note">"Ghi cùng lúc" mở một phiên cho MỖI em có tên trong khung; tắt thì chỉ em đang chọn được ghi.</div></div>' +
    '<div class="card mt"><h3>' + ico("database") + 'Đang ghi vào db</h3><div id="lv-db"></div>' +
    '<div class="cam-buttons mt"><button class="btn btn-sm" id="lv-reload">' + ico("refresh") + " Đọc lại db</button>" +
    '<a class="btn btn-sm" href="#reports">' + ico("report") + " Xem báo cáo</a></div></div>" +
    '<div class="lock-note mt">' + ico("info") + ' <a href="' + MEASURE_HREF + '">Màn đo gốc</a> vẫn còn đó như trang test chức năng ' +
    "(đầy đủ cột số, phím tắt và báo cáo phiên). Hai nơi dùng chung một engine và chung một tệp db — chỉ nơi đang giữ quyền đo mới được mở camera.</div>" +
    "</div></div>";
  wireWebCamera();
  livePaint();
  livePlaceSoon();
}

function wireWebCamera() {
  $("lv-start").onclick = liveStart;
  $("lv-stop").onclick = liveStop;
  $("lv-recal").onclick = async () => {
    const r = await liveCmd("recal", {}, 8000);
    toast(r.ok ? "Đã đặt lại hiệu chuẩn — ngồi thẳng để máy lấy mốc mới" : r.error, r.ok ? "ok" : "err");
  };
  $("lv-next").onclick = async () => {
    const r = await liveCmd("next", {}, 8000);
    if (!r.ok) toast(r.error, "err");
    livePaint();
  };
  $("lv-full").onclick = () => fullscreen(LIVE.layer);
  $("lv-apply").onclick = () => liveRestart("Đã áp dụng thông số");
  $("lv-cams").onclick = async () => {
    const r = await liveCmd("fillcams", {}, 15000);
    if (!r.ok) { toast(r.error, "err"); return; }
    LIVE.camKey = "";
    toast("Đã quét camera: " + ((r.result || []).length) + " nguồn", "ok");
    livePaint();
  };
  $("lv-sens").onchange = async (e) => {
    const r = await liveCmd("sens", {level: parseInt(e.target.value, 10)}, 8000);
    if (!r.ok) toast(r.error, "err");
    else toast("Độ nhạy: " + ((r.result && r.result.name) || e.target.value), "ok");
  };
  $("lv-rate").onchange = async (e) => {
    const r = await liveCmd("rate", {value: e.target.value}, 8000);
    if (!r.ok) toast(r.error, "err");
  };
  const tog = (id, key) => { $(id).onchange = () => liveToggle(key); };
  tog("lv-box", "f"); tog("lv-yolo", "y"); tog("lv-numbers", "o"); tog("lv-enhance", "g");
  $("lv-people").onchange = (e) => { S.hidePeople = !e.target.checked; LIVE.kidsKey = ""; livePaint(); };
  $("lv-recpeers").onchange = async (e) => {
    const r = await liveCmd("recpeers", {on: e.target.checked}, 8000);
    if (!r.ok) toast(r.error, "err");
    LIVE.dbKey = "";
  };
  $("lv-autoid").onchange = async (e) => {
    const r = await liveCmd("autoid", {on: e.target.checked}, 8000);
    if (!r.ok) toast(r.error, "err");
  };
  $("lv-sound").onchange = (e) => {
    S.soundOn = e.target.checked;
    localStorage.setItem("fc_sound", S.soundOn ? "1" : "0");
    livePillPaint();
  };
  $("lv-add").onclick = () => {
    openModal("<h3>Thêm em vào danh sách</h3>" +
      '<div class="field"><label>Tên em</label><input id="lv-add-name" placeholder="Ví dụ: Nguyễn Văn A"></div>' +
      '<div class="modal-actions"><button class="btn" id="modal-cancel">Hủy</button>' +
      '<button class="btn btn-primary" id="lv-add-ok">Thêm</button></div>');
    $("modal-cancel").onclick = closeModal;
    $("lv-add-name").focus();
    $("lv-add-ok").onclick = async () => {
      const name = $("lv-add-name").value.trim();
      if (!name) { toast("Tên trống — chưa thêm", "err"); return; }
      closeModal();
      const r = await liveCmd("add", {name}, 10000);
      if (!r.ok) { toast(r.error, "err"); return; }
      LIVE.rosterKey = "";
      toast("Đã thêm “" + name + "” và chọn em này để đo", "ok");
      livePaint();
    };
  };
  $("lv-learn").onclick = async () => {
    const r = await liveCmd("learn", {}, 10000);
    if (!r.ok) { toast(r.error, "err"); return; }
    LIVE.rosterKey = "";
    toast(r.result && r.result.learning ? "Đang học mặt — giữ em ấy nhìn thẳng 4 giây" : (r.result && r.result.stat) || "Chưa học được", "ok");
  };
  $("lv-forget").onclick = () => {
    const b = LIVE.state && LIVE.state.bound;
    if (!b) { toast("Chưa chọn em nào để xóa hồ sơ", "err"); return; }
    confirmModal("Xóa hồ sơ đã học của " + b.name + "?",
      "<p>Xóa mẫu mặt và thói quen đã học của em này. Danh sách tên và các phiên đã ghi trong db vẫn còn.</p>",
      async () => {
        const r = await liveCmd("forget", {}, 10000);
        if (!r.ok) { toast(r.error, "err"); return; }
        LIVE.rosterKey = "";
        toast("Đã xóa hồ sơ đã học của " + b.name, "ok");
        livePaint();
      }, {danger: true, label: "Xóa hồ sơ"});
  };
  $("lv-export").onclick = async () => {
    const r = await liveCmd("export", {}, 10000);
    toast(r.ok ? "Đã xuất danh sách + hồ sơ + mẫu mặt ra tệp .json" : r.error, r.ok ? "ok" : "err");
  };
  $("lv-import").onclick = () => $("lv-importfile").click();
  $("lv-importfile").onchange = async (e) => {
    const f = e.target.files && e.target.files[0];
    if (!f) return;
    const text = await f.text();
    const r = await liveCmd("import", {text, name: f.name}, 20000);
    e.target.value = "";
    if (!r.ok) { toast(r.error, "err"); return; }
    LIVE.rosterKey = "";
    toast("Đã nhập " + f.name + " — xem nhật ký khung đo để biết số em được gộp", "ok");
    livePaint();
  };
  $("lv-reload").onclick = async () => { await liveReloadDb(); LIVE.dbKey = ""; toast("Đã đọc lại tệp db", "ok"); livePaint(); };
}

/* ============================== màn 2: Camera ============================== */
function renderCamera() {
  if (window.FC_MODE === "web") { renderWebCamera(); return; }
  const cc = (S.system && S.system.camera_cfg) || {sensitivity: 1, max_active_faces: 10, process_width: 640, detect_fps: 8};
  const opt = (S.live && S.live.camera && S.live.camera.options) || {boxes: true, overlay: false, brighten: false};
  const sel = (id, opts, cur) => '<select id="' + id + '">' + opts.map(([v, l]) =>
    '<option value="' + v + '"' + (String(v) === String(cur) ? " selected" : "") + ">" + l + "</option>").join("") + "</select>";
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("camera") + 'Camera</h2>' +
    "<p>Mở/dừng camera, chỉnh thông số đo và tuỳ chọn hiển thị. Phím tắt: F, Y, O, G, H, N.</p></div></div>" +
    '<div class="grid grid-camera">' +
    '<div class="card"><h3>' + ico("camera") + 'Hình camera trực tiếp</h3>' +
    '<div class="cam-wrap" id="cam-wrap"><img id="cam-stream" alt="Luồng camera" hidden>' +
    '<div class="cam-empty" id="cam-empty"></div><span class="fps-chip" id="cam-fps" hidden></span></div>' +
    cameraButtons("cam") +
    '<div class="avg-note" id="cam-status-note"></div>' +
    '<div class="people-grid" id="cam-people"></div></div>' +
    '<div>' +
    '<div class="card" id="cam-hud-card"><h3>' + ico("activity") + 'Bảng chỉ số chi tiết</h3>' +
    '<div class="hud-target-bar" id="cam-hud-target"></div>' +
    '<div class="hud-lines-box" id="cam-hud-lines"></div></div>' +
    '<div class="card mt"><h3>' + ico("settings") + 'Cài đặt camera</h3>' +
    '<div class="field"><label>Nguồn camera</label>' + sel("cam-source", [[0, "Cam máy tính (Built-in, index 0)"], [1, "Webcam rời USB (Logitech C270, index 1)"], [2, "Camera phụ (index 2)"]], cc.source != null ? cc.source : 1) + "</div>" +
    '<div class="field"><label>Độ phân giải xử lý</label>' + sel("cam-width", [[640, "640×480"], [480, "480×360"], [320, "320×240"]], cc.process_width) + "</div>" +
    '<div class="field"><label>Số mặt đo cùng lúc</label>' + sel("cam-faces", [[1, "1"], [2, "2"], [3, "3"], [4, "4"], [10, "10 (tối đa)"]], cc.max_active_faces) + "</div>" +
    '<div class="field"><label>Tần suất quét (khung/giây)</label>' + sel("cam-fps-set", [[8, "8 (Tiết kiệm)"], [12, "12"], [15, "15"], [20, "20 (Mượt)"], [25, "25"], [30, "30 (Tối đa)"]], cc.detect_fps || 20) + "</div>" +
    '<div class="field"><label>Độ nhạy</label>' + sel("cam-sens", [[0, "Thấp"], [1, "Vừa"], [2, "Cao"], [3, "Rất cao"]], cc.sensitivity) + "</div>" +
    '<button class="btn btn-primary" id="cam-save">' + ico("save") + " Lưu cài đặt camera</button>" +
    '<div class="lock-note mt">Thông số lưu vào config.yaml, áp dụng từ lần bật camera kế tiếp. ' +
    "Hiệu chuẩn khoảng cách và gắn tên thủ công (phím M) hiện nằm ở cửa sổ camera lõi (main.py).</div></div>" +
    voiceCard() +
    '<div class="card mt"><h3>' + ico("eye") + 'Tuỳ chọn hiển thị</h3>' +
    switchRow("sw-boxes", "Hộp người / hộp mặt (Y)", opt.boxes) +
    switchRow("sw-overlay", "Số liệu đè khung (O)", opt.overlay) +
    switchRow("sw-brighten", "Tăng sáng (G)", opt.brighten) +
    switchRow("sw-people", "Hiện thẻ từng em (H)", !S.hidePeople) +
    "</div>" +
    (window.FC_MODE === "live" ?
      '<div class="card mt"><h3>' + ico("info") + 'Khẩu lệnh giáo viên (offline)</h3>' +
      '<div class="sys-row"><span>Ngữ cảnh</span><b id="tv-ctx">--</b></div>' +
      '<div class="sys-row"><span>Câu nghe được</span><span class="muted small" id="tv-utt">chưa có</span></div>' +
      '<div class="sys-row"><span>Bộ nghe</span><span class="muted small" id="tv-engine">chưa bật</span></div>' +
      '<div class="cam-buttons mt">' +
      '<button class="btn btn-sm btn-primary" id="tv-listen">Bật nghe</button>' +
      '<button class="btn btn-sm" data-tvctx="LECTURE">Nghe giảng</button>' +
      '<button class="btn btn-sm" data-tvctx="STUDY_NOTE">Ghi bài</button>' +
      '<button class="btn btn-sm" data-tvctx="GROUP_WORK">Nhóm</button></div>' +
      '<div class="avg-note">Nhận dạng tiếng Việt chạy trên máy này (Vosk), không gửi âm thanh đi đâu. ' +
      'Khẩu lệnh: "hoạt động nhóm", "bắt đầu vào bài học", "các em ghi bài"… ' +
      "Câu nói lúc đang giải mã câu trước có thể bị bỏ lỡ — nhắc lại khẩu lệnh nếu chưa thấy đổi ngữ cảnh. " +
      "Luật chú ý theo ngữ cảnh là luật viết tay, chưa kiểm định.</div></div>" : "") +
    '<div class="card mt"><h3>' + ico("info") + "Phím tắt</h3>" +
    '<div class="sys-row"><span>F</span><span class="muted">Toàn màn hình camera</span></div>' +
    '<div class="sys-row"><span>Y</span><span class="muted">Bật/tắt hộp người và mặt</span></div>' +
    '<div class="sys-row"><span>O</span><span class="muted">Bật/tắt số liệu đè khung</span></div>' +
    '<div class="sys-row"><span>G</span><span class="muted">Bật/tắt tăng sáng</span></div>' +
    '<div class="sys-row"><span>H</span><span class="muted">Ẩn/hiện thẻ từng em</span></div>' +
    '<div class="sys-row"><span>N</span><span class="muted">Chọn em kế tiếp để xem kỹ</span></div>' +
    "</div></div></div>";
  wireCameraButtons("cam", "cam-wrap");
  $("cam-save").onclick = saveCameraSettings;
  $("sw-boxes").onchange = (e) => setOption("boxes", e.target.checked);
  $("sw-overlay").onchange = (e) => setOption("overlay", e.target.checked);
  $("sw-brighten").onchange = (e) => setOption("brighten", e.target.checked);
  $("sw-people").onchange = (e) => { S.hidePeople = !e.target.checked; updateCameraParts(); };
  if ($("tv-listen")) {
    $("tv-listen").onclick = toggleVoiceListen;
    document.querySelectorAll("[data-tvctx]").forEach((b) => {
      b.onclick = () => setVoiceContext(b.getAttribute("data-tvctx"));
    });
    refreshVoicePanel();
  }
  updateCameraParts();
}

function switchRow(id, label, checked) {
  return '<div class="sys-row"><span>' + label + '</span><label class="switch">' +
    '<input type="checkbox" id="' + id + '"' + (checked ? " checked" : "") + "><i></i></label></div>";
}

/* ============================== khẩu lệnh giáo viên ==============================
   focusedge/teacher_voice.py + /api/teacher-voice*. Trước đợt này backend đã có nhưng giao diện
   không gọi tuyến nào, nên tính năng không dùng được từ dashboard. Thẻ này nằm ở màn Camera vì đó
   là chỗ ngữ cảnh lớp đang được đọc. Ba điều phải nói thật trên màn hình:
     - chưa có khẩu lệnh thì nhãn là của luật viết tay đọc từ hình ảnh, không phải "nghe giảng";
     - bấm chọn bằng tay khác với nghe được từ micro (nguồn ghi trong nhãn);
     - bật micro là gửi ghi âm ra Google, nên hỏi trước khi bật và in câu cảnh báo ngay dưới công tắc. */
const VOICE_LABEL = {
  UNKNOWN: "chưa có khẩu lệnh", LECTURE: "nghe giảng",
  STUDY_NOTE: "ghi bài / tự học", GROUP_WORK: "thảo luận nhóm",
  SEATWORK: "làm bài tập / suy nghĩ", Q_AND_A: "hỏi – đáp",
};
const VOICE_CHOICES = [["LECTURE", "Nghe giảng"], ["STUDY_NOTE", "Ghi bài"], ["GROUP_WORK", "Thảo luận nhóm"],
  ["SEATWORK", "Làm bài tập"], ["Q_AND_A", "Hỏi – đáp"]];

function voiceCard() {
  if (window.FC_MODE === "web") return "";
  return '<div class="card mt" id="voice-card"><h3>' + ico("mic") + "Khẩu lệnh giáo viên</h3>" +
    '<div class="avg-note" id="voice-status"></div>' +
    '<div class="voice-btns">' + VOICE_CHOICES.map(([v, l]) =>
      '<button class="btn btn-sm" data-voice-ctx="' + v + '">' + l + "</button>").join("") +
    '<button class="btn btn-sm" data-voice-ctx="UNKNOWN">Bỏ chọn</button></div>' +
    switchRow("sw-voice-listen", "Nghe khẩu lệnh bằng micro", false) +
    '<div class="lock-note" id="voice-note"></div>' +
    '<div class="muted small" id="voice-heard"></div></div>';
}

function updateVoiceParts() {
  const box = $("voice-status");
  if (!box) return;
  const v = S.voice;
  if (!v) { box.textContent = "Đang tải trạng thái khẩu lệnh…"; return; }
  const demo = window.FC_MODE === "demo";
  const src = !v.has_command ? "nhãn do luật đọc từ hình ảnh"
    : v.source === "speech" ? "nghe được từ micro" : "giáo viên chọn";
  box.innerHTML = "Ngữ cảnh: <b>" + esc(VOICE_LABEL[v.context] || v.context) + "</b> · " + esc(src) +
    (v.has_command ? " · " + num(v.elapsed_s, 0) + " s trước" : "") +
    (demo ? ' · <span class="muted">DEMO mô phỏng</span>' : "");
  document.querySelectorAll("[data-voice-ctx]").forEach((b) => {
    b.classList.toggle("btn-primary", b.getAttribute("data-voice-ctx") === v.context);
  });
  const sw = $("sw-voice-listen");
  if (sw) { sw.checked = !!v.is_running; sw.disabled = !demo && !v.mic_available; }
  const note = $("voice-note");
  if (note) {
    note.textContent = demo
      ? "DEMO: micro mô phỏng trong trình duyệt, không thu âm thật. Bản LIVE trên máy này mới nghe micro."
      : (v.mic_available ? (v.privacy_note || "") : (v.last_error || "Chưa cài SpeechRecognition / PyAudio."));
  }
  const heard = $("voice-heard");
  if (heard) heard.textContent = v.last_speech ? "Câu gần nhất: “" + v.last_speech + "”" : "";
}

async function setVoiceContext(ctx) {
  try {
    const r = await api("/api/teacher-voice/context", {context: ctx});
    S.voice = r.status || r;
    updateVoiceParts();
    toast(ctx === "UNKNOWN" ? "Đã bỏ chọn — nhãn ngữ cảnh trở về luật đọc từ hình ảnh"
      : "Đã đặt ngữ cảnh: " + (VOICE_LABEL[ctx] || ctx), "ok");
  } catch (e) { toast("Không đặt được ngữ cảnh: " + e.message, "err"); }
}

async function toggleVoiceListen(on) {
  const sw = $("sw-voice-listen");
  if (on && window.FC_MODE !== "demo") {
    const note = (S.voice && S.voice.privacy_note) ||
      "Nhận dạng khẩu lệnh gửi đoạn ghi âm từ micro ra Google (cần internet).";
    if (!window.confirm(note + "\n\nBật nghe khẩu lệnh giáo viên?")) {
      if (sw) sw.checked = false;
      return;
    }
  }
  try {
    const r = await api("/api/teacher-voice/listen", {action: on ? "start" : "stop"});
    S.voice = r.status || r;
    updateVoiceParts();
    if (on && !S.voice.is_running) {
      toast("Không nghe được micro: " + (S.voice.last_error || "thiếu SpeechRecognition / PyAudio"), "err");
    } else {
      toast(on ? "Đang nghe khẩu lệnh giáo viên" : "Đã tắt nghe khẩu lệnh", "ok");
    }
  } catch (e) {
    if (sw) sw.checked = !on;
    toast("Không bật/tắt được micro: " + e.message, "err");
  }
}

function wireVoiceCard() {
  document.querySelectorAll("[data-voice-ctx]").forEach((b) => {
    b.onclick = () => setVoiceContext(b.getAttribute("data-voice-ctx"));
  });
  const sw = $("sw-voice-listen");
  if (sw) sw.onchange = (e) => toggleVoiceListen(e.target.checked);
  updateVoiceParts();
}

/* ============================== ngữ cảnh từng em + dấu hiệu Tic ==============================
   focusedge/context_eval.py + focusedge/tic_watch.py (3 tài liệu đợt này). Đây là lớp KHUYẾN NGHỊ
   chạy song song với chuỗi cảnh báo parity: không đếm vào alerts_total, không thay SEI. Thẻ người
   chỉ hiện khi CÓ khẩu lệnh và mặt đo được (state NEUTRAL thì không phán gì - đúng nguyên tắc
   "không có bằng chứng thì không có nhãn"). Huy hiệu Tic luôn kèm disclaimer trong title. */
const CTX_STATE_CHIP = { ON_TASK: ["Tập trung", ""], WATCH: ["Theo dõi", "warn"], OFF_TASK: ["Mất tập trung", "bad"] };
const CTX_SEVERITY = { trung_binh: "trung bình", nang: "nặng" };

function contextRows(p) {
  const c = p.context;
  if (!c || !c.state || c.state === "NEUTRAL") return "";
  const chip = CTX_STATE_CHIP[c.state] || [c.state, "warn"];
  let html = '<div class="ctx-row"><span class="sit-chip ' + chip[1] + '">' + chip[0] + "</span>" +
    '<span class="ctx-reason">' + esc(c.reason || "") + "</span></div>";
  if (c.state !== "ON_TASK" && c.off_s > 0) {
    html += '<div class="person-kv"><span>Lệch ngữ cảnh</span><b>' + num(c.off_s, 0) +
      "s / cửa sổ " + num(c.window_s, 0) + "s</b></div>";
  }
  if (c.off_total_s > 0) {
    html += '<div class="person-kv"><span>Cộng dồn cả phiên</span><b>' + num(c.off_total_s, 0) + "s" +
      (CTX_SEVERITY[c.severity] ? " · " + CTX_SEVERITY[c.severity] : "") + "</b></div>";
  }
  return html;
}

function ticBadge(p) {
  const t = p.tic;
  if (!t || !t.level) return "";
  return '<div class="tic-badge lv' + t.level + '" title="' + esc(t.disclaimer || "") + '">' +
    ico("warn") + " " + esc(t.label || "Dấu hiệu giật lặp lại") + "</div>";
}

/* ============================== đếm "đang mất tập trung" + danh sách bấm xem ==============================
   Nguồn duy nhất: S.live.people[].context (context_eval.py ở LIVE, ctxView ở DEMO — cùng dạng).
   OFF_TASK = đã lệch quá cửa sổ ngữ cảnh; WATCH chỉ "đang theo dõi", KHÔNG đếm vào biến này.
   Camera chưa chạy thì hiện "--" chứ không hiện 0 giả vờ "cả lớp ngoan". Danh sách trong modal
   tự cập nhật mỗi nhịp tickFast khi còn mở, nên giáo viên để đó nhìn số nhảy theo thời gian thực. */
function offTaskPeople() {
  const people = (S.live && S.live.people) || [];
  return {
    running: !!(S.live && S.live.camera && S.live.camera.running),
    off: people.filter((p) => p.context && p.context.state === "OFF_TASK"),
    watch: people.filter((p) => p.context && p.context.state === "WATCH"),
    judged: people.some((p) => p.context && p.context.state && p.context.state !== "NEUTRAL"),
  };
}

function updateOffTaskCard() {
  const el = $("ov-offtask");
  if (!el) return;
  const t = offTaskPeople();
  el.textContent = t.running ? String(t.off.length) : "--";
  $("ov-offtask-sub").textContent = !t.running ? "chưa bật camera"
    : t.off.length ? "bấm để xem em nào"
    : !t.judged ? "chưa có khẩu lệnh ngữ cảnh"
    : t.watch.length ? t.watch.length + " em đang theo dõi" : "cả lớp trong ngữ cảnh";
  el.closest(".stat").classList.toggle("danger", t.off.length > 0);
  const body = $("offtask-body");
  if (body && modalOpen()) body.innerHTML = offTaskBody();
}

function offTaskRow(p) {
  const c = p.context;
  const chip = CTX_STATE_CHIP[c.state] || [c.state, "warn"];
  const sev = CTX_SEVERITY[c.severity];
  return '<div class="offtask-row ' + (c.state === "OFF_TASK" ? "bad" : "warn") + '">' +
    '<div class="offtask-head"><b>' + esc(p.name) + "</b>" +
    '<span class="sit-chip ' + chip[1] + '">' + chip[0] + "</span>" +
    (p.student_id ? "" : '<span class="muted small">chưa gắn tên — không lưu phiên</span>') + "</div>" +
    (c.context_vi ? '<div class="offtask-kv">Ngữ cảnh: <b>' + esc(c.context_vi) + "</b></div>" : "") +
    '<div class="ctx-reason">' + esc(c.reason || "") + "</div>" +
    '<div class="offtask-kv">Lệch <b>' + num(c.off_s, 0) + "s</b> / cửa sổ " + num(c.window_s, 0) + "s" +
    " · Cộng dồn cả phiên <b>" + num(c.off_total_s, 0) + "s</b>" + (sev ? " · mức " + sev : "") +
    (p.sei != null ? " · SEI " + num(p.sei, 0) + "%" : "") + "</div></div>";
}

function offTaskBody() {
  const t = offTaskPeople();
  if (!t.running) {
    return '<div class="muted small">Camera chưa bật nên chưa đánh giá được em nào. ' +
      "Bật camera ở màn Camera (hoặc bấm Bắt đầu đo) và chọn khẩu lệnh ngữ cảnh trước.</div>";
  }
  if (!t.off.length && !t.watch.length) {
    return '<div class="muted small">' + (t.judged
      ? "Không có em nào đang lệch ngữ cảnh — cả lớp đang trong tiêu chí của ngữ cảnh hiện tại."
      : "Chưa đánh giá được: chưa có khẩu lệnh ngữ cảnh của giáo viên, hoặc chưa có mặt nào đo được. " +
        "Chọn ngữ cảnh ở thẻ Khẩu lệnh giáo viên (màn Camera) để bắt đầu đánh giá.") + "</div>";
  }
  let html = "";
  if (t.off.length) {
    html += '<div class="offtask-sec">Đang mất tập trung (' + t.off.length + " em)</div>" +
      t.off.map(offTaskRow).join("");
  }
  if (t.watch.length) {
    html += '<div class="offtask-sec">Đang theo dõi — chưa vượt cửa sổ (' + t.watch.length + " em)</div>" +
      t.watch.map(offTaskRow).join("");
  }
  return html;
}

function showOffTaskList() {
  openModal("<h3>" + ico("warn") + ' Học sinh đang mất tập trung</h3>' +
    '<div id="offtask-body">' + offTaskBody() + "</div>" +
    '<div class="avg-note mt">Khuyến nghị sư phạm theo ngữ cảnh khẩu lệnh GV — không phải cảnh báo kỷ luật. ' +
    "Danh sách tự cập nhật khi còn mở.</div>" +
    '<div class="modal-actions">' +
    '<button class="btn" id="offtask-close">Đóng</button>' +
    '<button class="btn btn-primary" id="offtask-camera">' + ico("camera") + " Mở màn Camera</button></div>");
  $("offtask-close").onclick = closeModal;
  $("offtask-camera").onclick = () => { closeModal(); location.hash = "#camera"; };
}

function updateCameraParts() {
  if (window.FC_MODE === "web") { livePaint(); livePlaceSoon(); return; }
  if ($("tv-ctx")) refreshVoicePanel();
  if (!$("cam-stream") && !$("ov-stream")) return;
  if ($("cam-stream")) syncStream("cam-stream", "cam-empty", "cam-fps");
  const cam = (S.live && S.live.camera) || {};
  if ($("cam-status-note")) {
    $("cam-status-note").textContent = window.FC_MODE === "web"
      ? "Không có luồng camera ở trang này — số bên dưới lấy từ db đã đo xong."
      : cam.running
        ? "Đang chạy · " + cam.frames + " khung đã xử lý · " + num(cam.processing_ms, 0) + " ms/khung · " + cam.people + " người trong khung."
        : (cam.error ? "Lỗi: " + cam.error : "Camera chưa bật.");
  }
  if ($("cam-hud-lines")) updateHudPanel();
  if (!$("cam-people")) return;
  const people = (S.live && S.live.people) || [];
  if (S.hidePeople) { $("cam-people").innerHTML = ""; return; }
  if (!cam.running || !people.length) {
    $("cam-people").innerHTML = '<div class="card muted small">Chưa có người nào trong khung hình. Bật camera để xem thẻ từng em.</div>';
    return;
  }
  if (S.focusTrack != null && !people.some((p) => p.track_id === S.focusTrack)) S.focusTrack = null;
  $("cam-people").innerHTML = people.map((p) => {
    const sit = SIT_LABEL[p.situation] || ["--", "warn"];
    const seiColor = p.sei == null ? "var(--ink-soft)" : p.sei >= 70 ? "var(--green)" : p.sei >= 45 ? "var(--orange)" : "var(--red)";
    // Nút gắn tên chỉ có ở LIVE: DEMO là người mô phỏng, bản web chỉ đọc db, không có track thật để khai báo.
    let bindRow = "";
    if (window.FC_MODE === "live") {
      if (!p.student_id) {
        const seatNote = p.seat_hint
          ? "Chỗ này trước đó là em " + esc(p.seat_hint) + " — bấm Gắn tên để gắn lại"
          : "Chưa gắn tên nên không lưu phiên";
        bindRow = '<div class="person-bind"><button class="btn btn-sm btn-primary" data-bind="' + p.track_id + '">' +
          ico("edit") + " Gắn tên</button>" +
          '<span class="muted small">' + seatNote + "</span></div>";
      } else if (p.identity_conf === "manual") {
        bindRow = '<div class="person-bind"><button class="btn btn-sm" data-bind="' + p.track_id + '">Đổi tên</button>' +
          '<button class="btn btn-sm" data-unbind="' + p.track_id + '">Bỏ tên</button></div>';
      }
    }
    return '<div class="card person-card' + (p.track_id === S.focusTrack ? " focused" : "") + '">' +
      '<div class="person-head"><span class="person-name">' + esc(p.name) + "</span>" +
      '<span class="person-id">' + esc(p.student_id || "guest-" + p.track_id) + "</span></div>" +
      '<div class="person-sei" style="color:' + seiColor + '">' + (p.sei == null ? "--" : Math.round(p.sei)) +
      '<span class="muted" style="font-size:13px;font-weight:600"> /100</span></div>' +
      '<span class="sit-chip ' + sit[1] + '">' + sit[0] + "</span>" +
      '<div class="person-kv"><span>EAR / MAR</span><b>' + num(p.ear, 2) + " / " + num(p.mar, 2) + "</b></div>" +
      '<div class="person-kv"><span>Pitch / Yaw</span><b>' + num(p.pitch, 0) + "° / " + num(p.yaw, 0) + "°</b></div>" +
      '<div class="person-kv"><span>PERCLOS</span><b>' + num(p.perclos, 0) + "%</b></div>" +
      '<div class="person-kv"><span>Khoảng cách</span><b>' + num(p.distance_cm, 0) + " cm</b></div>" +
      '<div class="person-kv"><span>Nhận diện</span><b>' + esc(p.identity_conf || "--") + "</b></div>" +
      (p.attention_label ? '<div class="person-kv" title="Luật viết tay theo khẩu lệnh GV, chưa kiểm định"><span>Chú ý (GV)</span><b>' + esc(attnText(p)) + "</b></div>" : "") +
      (p.alert ? '<div class="person-alert">' + ico("warn") + " " + esc(p.alert) + "</div>" : "") +
      contextRows(p) + ticBadge(p) +
      bindRow +
      "</div>";
  }).join("");
}

function updateHudPanel() {
  const el = $("cam-hud-lines");
  if (!el) return;
  const people = (S.live && S.live.people) || [];
  let targetPerson = null;
  if (S.focusTrack != null) {
    targetPerson = people.find((p) => p.track_id === S.focusTrack);
  }
  if (!targetPerson && people.length > 0) {
    targetPerson = people[0];
  }

  const targetBar = $("cam-hud-target");
  if (targetBar) {
    if (people.length > 1) {
      targetBar.innerHTML = '<div class="hud-target-chips"><span class="muted small">Đang xem:</span>' +
        people.map((p) => {
          const active = (targetPerson && targetPerson.track_id === p.track_id);
          return '<button class="btn btn-sm' + (active ? ' btn-primary' : '') + '" data-hud-track="' + p.track_id + '">' +
            esc(p.name || ('guest-' + p.track_id)) + '</button>';
        }).join(" ") + '</div>';
      targetBar.hidden = false;
    } else if (targetPerson) {
      targetBar.innerHTML = '<span class="muted small">Đang đo: <b>' + esc(targetPerson.name) + '</b></span>';
      targetBar.hidden = false;
    } else {
      targetBar.hidden = true;
    }
  }

  let lines = (targetPerson && targetPerson.hud_lines && targetPerson.hud_lines.length)
    ? targetPerson.hud_lines
    : ((S.live && S.live.hud_lines && S.live.hud_lines.length) ? S.live.hud_lines : null);

  if (!lines || !lines.length) {
    el.innerHTML = '<div class="muted small" style="padding:12px;">Đang tải chỉ số chi tiết...</div>';
    return;
  }

  el.innerHTML = lines.map((l) => {
    if (l.text === "\x00sei_chart") {
      return '<div class="hud-chart-divider"></div>';
    }
    if (!l.text) {
      return '<div class="hud-line-spacer"></div>';
    }
    const color = l.colour ? `rgb(${l.colour[0]},${l.colour[1]},${l.colour[2]})` : "inherit";
    const weight = l.bold ? "600" : "400";
    const fontSize = l.size ? (l.size + "px") : "13px";
    return '<div class="hud-line-row" style="color:' + color + ';font-size:' + fontSize + ';font-weight:' + weight + ';">' + esc(l.text) + '</div>';
  }).join("");
}

/* Nhãn chú ý từ lõi Python là chữ ASCII không dấu ("TAP TRUNG", "MAT TAP TRUNG (Xs)",
   "Dang theo doi...") vì console không in được tiếng Việt có dấu; màn hình thì phải có dấu. */
function attnText(p) {
  const l = p.attention_label || "";
  let t = l.indexOf("MAT TAP TRUNG") === 0 ? "Mất tập trung" + l.slice(13)
    : l.indexOf("Dang theo doi") >= 0 ? "Đang theo dõi"
    : "Tập trung";
  if (p.attention_reason) t += " — " + p.attention_reason;
  return t;
}

const TV_CTX_LABEL = {LECTURE: "Nghe giảng", STUDY_NOTE: "Ghi bài / tự học", GROUP_WORK: "Thảo luận nhóm"};

async function refreshVoicePanel() {
  if (!$("tv-ctx")) return;
  try {
    const st = await api("/api/teacher-voice");
    S.voice = st;
    const src = st.context_source === "voice" ? " (giọng nói)" : st.context_source === "manual" ? " (chọn tay)" : "";
    $("tv-ctx").textContent = (TV_CTX_LABEL[st.context] || st.context) + src;
    $("tv-utt").textContent = st.last_utterance || "chưa có";
    $("tv-engine").textContent = st.is_running
      ? (st.engine === "vosk-vi-offline" ? "Vosk tiếng Việt — offline, đang nghe"
        : st.engine === "manual" ? "không nghe được — chỉ chọn tay" : "đang khởi động…")
      : "đã tắt";
    $("tv-listen").textContent = st.is_running ? "Tắt nghe" : "Bật nghe";
  } catch (e) { /* server chưa chạy thì giữ nguyên bảng */ }
}

async function toggleVoiceListen() {
  const on = S.voice && S.voice.is_running;
  try {
    const r = await api("/api/teacher-voice/listen", {action: on ? "stop" : "start"});
    if (r.ok) { toast(on ? "Đã tắt nghe khẩu lệnh" : "Đang nghe khẩu lệnh (offline)", "ok"); await refreshVoicePanel(); }
    else toast(r.error || "Không bật/tắt được", "err");
  } catch (e) { toast(e.message, "err"); }
}

async function setVoiceContext(ctx) {
  try {
    const r = await api("/api/teacher-voice/context", {context: ctx});
    if (r.ok) { toast("Ngữ cảnh: " + (TV_CTX_LABEL[ctx] || ctx), "ok"); await refreshVoicePanel(); }
    else toast(r.error || "Không đổi được ngữ cảnh", "err");
  } catch (e) { toast(e.message, "err"); }
}

async function saveCameraSettings() {
  const body = {camera: {
    source: parseInt($("cam-source").value, 10),
    process_width: parseInt($("cam-width").value, 10),
    max_active_faces: parseInt($("cam-faces").value, 10),
    detect_fps: parseInt($("cam-fps-set").value, 10),
    sensitivity: parseInt($("cam-sens").value, 10)}};
  try {
    const r = await api("/api/settings", body);
    if (r.ok) { toast(window.FC_MODE === "demo" ? "Đã lưu cài đặt camera (demo trong trình duyệt)" : "Đã lưu vào config.yaml — áp dụng từ lần bật camera sau", "ok"); await tickSlow(); }
    else toast(r.error || "Không lưu được", "err");
  } catch (e) { toast(e.message, "err"); }
}

async function setOption(key, value) {
  try {
    const r = await api("/api/camera/options", {[key]: value});
    if (r.ok && S.live && S.live.camera) S.live.camera.options = r.options;
  } catch (e) { toast(e.message, "err"); }
}

async function startCamera() {
  try {
    const r = await api("/api/camera/start", {});
    if (r.ok) { toast(window.FC_MODE === "demo" ? "Đã bật camera demo — đây là MÔ PHỎNG, không phải camera của bạn" : "Đã bật camera", "ok"); await tickFast(); }
    else toast((r.error || "Không bật được camera") + (r.hint ? " — " + r.hint : ""), "err");
  } catch (e) { toast(e.message, "err"); }
}
async function stopCamera() {
  try {
    const r = await api("/api/camera/stop", {});
    if (r && r.ok === false) {
      toast((r.error || "Không dừng được camera") + (r.hint ? " — " + r.hint : ""), "err");
      return;
    }
    toast(window.FC_MODE === "demo" ? "Đã dừng camera demo" : "Đã dừng camera — các phiên đang đo được chốt vào SQLite", "ok");
    await tickFast();
    await tickSlow();
  } catch (e) { toast(e.message, "err"); }
}

/* Gắn tên = khai báo danh tính như phím M của cửa sổ lõi (identity_conf "manual").
   Có tên thì lõi mới mở phiên đo và ghi SQLite khi chốt; guest chỉ đo trực tiếp, không lưu. */
async function bindPerson(trackId) {
  const p = ((S.live && S.live.people) || []).find((x) => x.track_id === trackId);
  const cur = p && p.student_id ? (p.name || "") : ((p && p.seat_hint) || "");
  const name = prompt("Tên của em này (ví dụ: Nguyễn Văn A):", cur);
  if (name == null) return;
  if (!name.trim()) { toast("Tên trống — chưa gắn", "err"); return; }
  try {
    const r = await api("/api/camera/bind", {track_id: trackId, name: name.trim()});
    if (r.ok) { toast("Đã gắn tên “" + r.name + "” — đo tiếp rồi bấm Dừng camera để lưu phiên", "ok"); await tickFast(); }
    else toast(r.error || "Không gắn được tên", "err");
  } catch (e) { toast(e.message, "err"); }
}
async function unbindPerson(trackId) {
  if (!confirm("Bỏ tên track này? Khuôn mặt sẽ thành guest và KHÔNG lưu phiên mới cho tới khi gắn tên lại.")) return;
  try {
    const r = await api("/api/camera/unbind", {track_id: trackId});
    if (r.ok) { toast("Đã bỏ tên — track về guest", "ok"); await tickFast(); }
    else toast(r.error || "Không bỏ được tên", "err");
  } catch (e) { toast(e.message, "err"); }
}

function fullscreen(el) {
  if (!el) return;
  if (document.fullscreenElement) document.exitFullscreen();
  else el.requestFullscreen().catch((e) => toast("Không vào được toàn màn hình: " + e.message, "err"));
}

/* Chụp ảnh màn hình bằng quyền trình duyệt (getDisplayMedia) - đặc tả 5.3.
   Đây là ảnh MÀN HÌNH người dùng tự chọn chia sẻ, không phải trích khung mặt từ luồng đo. */
async function screenshot() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
    toast("Trình duyệt không hỗ trợ chụp màn hình (cần Chrome/Edge mới)", "err");
    return;
  }
  try {
    const stream = await navigator.mediaDevices.getDisplayMedia({video: true});
    const track = stream.getVideoTracks()[0];
    const video = document.createElement("video");
    video.srcObject = stream;
    await video.play();
    await new Promise((r) => setTimeout(r, 300));
    const canvas = document.createElement("canvas");
    canvas.width = video.videoWidth || 1280;
    canvas.height = video.videoHeight || 720;
    canvas.getContext("2d").drawImage(video, 0, 0, canvas.width, canvas.height);
    track.stop();
    const a = document.createElement("a");
    a.href = canvas.toDataURL("image/png");
    a.download = "focuschange_manhinh_" + stamp() + ".png";
    a.click();
    toast("Đã lưu ảnh chụp màn hình", "ok");
  } catch (e) { toast("Không chụp được màn hình: " + e.message, "err"); }
}

/* ============================== màn 3: Học sinh ============================== */
async function renderStudents() {
  const canCsv = allows("csv_export");
  const web = window.FC_MODE === "web";
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("users") + "Học sinh</h2>" +
    "<p>" + (web
      ? "Danh sách em đọc từ SQLite của máy này. " + (MEASURE_HREF
          ? "Thêm em và gắn tên làm ngay ở màn Camera — bảng này chỉ đọc để không ghi đè phiên đang đo."
          : "Trang này chỉ đọc; bản dashboard-only không có màn đo để thêm em.")
      : "Thêm/sửa/xóa, nhập xuất danh sách, quản lý đồng ý. Rút đồng ý là xóa vector mặt ngay.") + "</p></div></div>" +
    '<div class="card"><div class="toolbar">' +
    '<input class="search" id="stu-search" placeholder="Tìm theo tên hoặc mã…" value="' + esc(S.studentQuery) + '">' +
    '<span class="spacer"></span>' +
    (web
      ? (MEASURE_HREF
          ? '<a class="btn btn-primary" href="#camera">' + ico("camera") + " Thêm em ở màn Camera</a>"
          : '<span class="muted small">Thêm em ở màn đo của app PC / bản web đầy đủ.</span>')
      : '<button class="btn btn-primary" id="stu-add">' + ico("plus") + " Thêm học sinh</button>" +
        '<button class="btn" id="stu-import">' + ico("upload") + " Nhập CSV</button>") +
    '<button class="btn" id="stu-export"' + (canCsv ? "" : ' disabled title="Cần bậc Pro trở lên — đổi trong Cài đặt"') + ">" +
    ico("download") + " Xuất CSV</button></div>" +
    (canCsv ? "" : '<div class="lock-note">' + ico("lock") + " Xuất CSV cần bậc Pro trở lên (đổi bậc trong Cài đặt).</div>") +
    '<div id="students-body" class="mt"></div></div>';
  $("stu-search").oninput = (e) => { S.studentQuery = e.target.value; fillStudentsTable(); };
  if (!web) {
    $("stu-add").onclick = () => studentModal(null);
    $("stu-import").onclick = importModal;
  }
  $("stu-export").onclick = () => downloadCsv("/api/students/csv", "hoc_sinh_" + stamp() + ".csv");
  await loadStudents();
}

async function loadStudents() {
  try { S.students = await api("/api/students"); } catch (e) { toast(e.message, "err"); S.students = []; }
  fillStudentsTable();
}

function fillStudentsTable() {
  const body = $("students-body");
  if (!body) return;
  const web = window.FC_MODE === "web";
  const q = S.studentQuery.trim().toLowerCase();
  const list = S.students.filter((s) => !q || s.name.toLowerCase().includes(q) || s.student_id.toLowerCase().includes(q));
  if (!list.length) {
    body.innerHTML = '<div class="chart-empty">' + (web
      ? (q ? "Không có em nào khớp tìm kiếm."
           : "Chưa có em nào trong db của máy này. Vào màn Camera, thêm em rồi đo một phiên và bấm Dừng — em sẽ hiện ở đây.")
      : 'Chưa có học sinh nào' + (q ? " khớp tìm kiếm" : "") +
        ". Bấm <b>Thêm học sinh</b> hoặc <b>Nhập CSV</b>.") + "</div>";
    return;
  }
  body.innerHTML = '<table class="tbl"><thead><tr><th>Mã</th><th>Tên</th><th>Năm sinh</th><th>Giới tính</th>' +
    "<th>Đồng ý</th><th>Số phiên</th><th>SEI TB</th><th>Lần đo cuối</th><th></th></tr></thead><tbody>" +
    list.map((s) =>
      "<tr><td><b>" + esc(s.student_id) + "</b></td>" +
      '<td><a href="#analysis" data-sid="' + esc(s.student_id) + '" class="stu-link">' + esc(s.name) + "</a></td>" +
      "<td>" + (s.birth_year || "--") + "</td><td>" + esc(s.gender || "--") + "</td>" +
      (web ? '<td><span class="muted small">không có bảng này</span></td>'
           : '<td><label class="switch"><input type="checkbox" data-consent="' + esc(s.student_id) + '"' +
             (s.consent ? " checked" : "") + '><i></i></label></td>') +
      "<td>" + s.sessions + "</td><td>" + num(s.sei_mean, 1) + "</td>" +
      "<td>" + fmtDateTime(s.last_session_ms) + "</td>" +
      (web ? "<td></td>"
           : '<td style="white-space:nowrap"><button class="btn btn-sm" data-edit="' + esc(s.student_id) + '">' + ico("edit") + "</button> " +
             '<button class="btn btn-sm btn-danger" data-del="' + esc(s.student_id) + '">' + ico("trash") + "</button></td>") +
      "</tr>").join("") +
    "</tbody></table>" +
    '<div class="avg-note mt">' + (web
      ? "Số phiên và SEI TB là số đo thật trong SQLite của máy này. Cột Đồng ý chỉ có ở bản Python cục bộ (bảng student_consent); bản web/app không ghi bảng đó."
      : "Đồng ý = phụ huynh/nhà trường cho phép nhận diện và lưu vector mặt (chỉ lưu vector, không lưu ảnh). " +
        "Rút đồng ý sẽ xóa vector ngay lập tức.") + "</div>";
  for (const el of body.querySelectorAll("[data-consent]")) {
    el.onchange = () => consentModal(el.dataset.consent, el.checked, el);
  }
  for (const el of body.querySelectorAll("[data-edit]")) {
    el.onclick = () => studentModal(S.students.find((s) => s.student_id === el.dataset.edit));
  }
  for (const el of body.querySelectorAll("[data-del]")) {
    el.onclick = () => deleteModal(el.dataset.del);
  }
  for (const el of body.querySelectorAll(".stu-link")) {
    el.onclick = () => { S.analysisSid = el.dataset.sid; };
  }
}

function studentModal(stu) {
  const edit = !!stu;
  openModal("<h3>" + (edit ? "Sửa học sinh" : "Thêm học sinh") + "</h3>" +
    '<div class="field"><label>Mã học sinh (vd. HS01)</label><input id="m-sid" value="' + esc(edit ? stu.student_id : "") + '"' +
    (edit ? " disabled" : "") + '></div>' +
    '<div class="field"><label>Họ và tên</label><input id="m-name" value="' + esc(edit ? stu.name : "") + '"></div>' +
    '<div class="field"><label>Năm sinh</label><input id="m-year" type="number" min="1990" max="2025" value="' +
    (edit && stu.birth_year ? stu.birth_year : "") + '"></div>' +
    '<div class="field"><label>Giới tính</label><select id="m-gender">' +
    ["", "nam", "nữ", "khác"].map((g) => '<option value="' + g + '"' + (edit && stu.gender === g ? " selected" : "") + ">" +
      (g === "" ? "--" : g === "nam" ? "Nam" : g === "nữ" ? "Nữ" : "Khác") + "</option>").join("") + "</select></div>" +
    '<div class="modal-actions"><button class="btn" id="modal-cancel">Hủy</button>' +
    '<button class="btn btn-primary" id="m-save">' + (edit ? "Lưu thay đổi" : "Thêm") + "</button></div>");
  $("modal-cancel").onclick = closeModal;
  $("m-save").onclick = async () => {
    const body = {
      student_id: $("m-sid").value.trim(), name: $("m-name").value.trim(),
      birth_year: $("m-year").value ? parseInt($("m-year").value, 10) : null,
      gender: $("m-gender").value || null};
    if (!body.student_id || !body.name) { toast("Cần cả mã học sinh và tên", "err"); return; }
    try {
      const r = await api(edit ? "/api/students/update" : "/api/students/create", body);
      if (r.ok) { closeModal(); toast(edit ? "Đã lưu" : "Đã thêm học sinh", "ok"); await loadStudents(); tickFast(); }
      else toast(r.error || "Không lưu được", "err");
    } catch (e) { toast(e.message, "err"); }
  };
}

function consentModal(sid, enabling, switchEl) {
  const stu = S.students.find((s) => s.student_id === sid) || {name: sid};
  const title = enabling ? "Xác nhận đồng ý" : "Rút đồng ý";
  const msg = enabling
    ? "Xác nhận phụ huynh/nhà trường đã đồng ý cho em <b>" + esc(stu.name) +
      "</b> được nhận diện khuôn mặt và lưu vector đặc trưng (không lưu ảnh)?"
    : "Rút đồng ý của em <b>" + esc(stu.name) + "</b>? Vector mặt sẽ bị <b>xóa ngay lập tức</b>; " +
      "em vẫn được đo ẩn danh nhưng không còn nhận diện tự động.";
  openModal("<h3>" + title + "</h3><p>" + msg + "</p>" +
    '<div class="field"><label>Ghi chú (ai đồng ý, hình thức)</label><input id="m-note" value="' +
    esc(enabling ? "Phụ huynh đã đồng ý" : "Rút đồng ý") + '"></div>' +
    '<div class="modal-actions"><button class="btn" id="modal-cancel">Hủy</button>' +
    '<button class="btn ' + (enabling ? "btn-primary" : "btn-danger") + '" id="m-ok">' + title + "</button></div>");
  $("modal-cancel").onclick = () => { switchEl.checked = !enabling; closeModal(); };
  $("m-ok").onclick = async () => {
    try {
      const r = await api("/api/consent", {student_id: sid, consent: enabling ? 1 : 0, note: $("m-note").value});
      if (r.ok) { closeModal(); toast(enabling ? "Đã ghi nhận đồng ý" : "Đã rút đồng ý và xóa vector mặt", "ok"); await loadStudents(); }
      else { switchEl.checked = !enabling; toast(r.error || "Lỗi", "err"); }
    } catch (e) { switchEl.checked = !enabling; toast(e.message, "err"); }
  };
}

function deleteModal(sid) {
  const stu = S.students.find((s) => s.student_id === sid) || {name: sid};
  confirmModal("Xóa học sinh",
    "<p>Xóa <b>" + esc(stu.name) + " (" + esc(sid) + ")</b> và <b>toàn bộ dữ liệu liên quan</b>: " +
    "phiên đo, chỉ số, cảnh báo, baseline, đồng ý và vector mặt trong roster.</p>" +
    "<p>Hành động này <b>không hoàn tác</b> (đặc tả: chuyển trường / yêu cầu xóa dữ liệu).</p>",
    async () => {
      try {
        const r = await api("/api/students/delete", {student_id: sid});
        if (r.ok) { toast("Đã xóa toàn bộ dữ liệu của em", "ok"); await loadStudents(); tickFast(); }
        else toast(r.error || "Không xóa được", "err");
      } catch (e) { toast(e.message, "err"); }
    }, {danger: true, label: "Xóa vĩnh viễn"});
}

function importModal() {
  openModal("<h3>Nhập danh sách từ CSV</h3>" +
    '<div class="field"><label>Chọn file CSV (cột: student_id, name, birth_year, gender)</label>' +
    '<input type="file" id="m-file" accept=".csv,text/csv"></div>' +
    '<div class="field"><label>Hoặc dán nội dung CSV</label><textarea id="m-csv" placeholder="student_id,name,birth_year,gender&#10;HS01,Nguyễn Văn A,2010,nam"></textarea></div>' +
    '<div class="modal-actions"><button class="btn" id="modal-cancel">Hủy</button>' +
    '<button class="btn btn-primary" id="m-import">Nhập</button></div>');
  $("modal-cancel").onclick = closeModal;
  $("m-file").onchange = (e) => {
    const f = e.target.files[0];
    if (!f) return;
    const reader = new FileReader();
    reader.onload = () => { $("m-csv").value = reader.result; };
    reader.readAsText(f, "utf-8");
  };
  $("m-import").onclick = async () => {
    const text = $("m-csv").value.trim();
    if (!text) { toast("Chưa có nội dung CSV", "err"); return; }
    try {
      const r = await api("/api/students/import", {csv: text});
      if (r.ok) { closeModal(); toast("Đã nhập: thêm " + r.added + ", cập nhật " + r.updated, "ok"); await loadStudents(); tickFast(); }
      else toast(r.error || "Không nhập được", "err");
    } catch (e) { toast(e.message, "err"); }
  };
}

/* ============================== màn 4: Phân tích AI ============================== */
async function renderAnalysis() {
  if (!S.students.length) { try { S.students = await api("/api/students"); } catch (e) { S.students = []; } }
  if (!S.analysisSid && S.students.length) S.analysisSid = S.students[0].student_id;
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("chart") + "Phân tích AI</h2>" +
    "<p>Biểu đồ theo từng em, so với baseline của chính em (không so với bạn khác).</p></div></div>" +
    '<div class="card"><div class="toolbar">' +
    '<select id="an-student">' + S.students.map((s) =>
      '<option value="' + esc(s.student_id) + '"' + (s.student_id === S.analysisSid ? " selected" : "") + ">" +
      esc(s.name) + " · " + esc(s.student_id) + "</option>").join("") + "</select>" +
    '<div class="tabs" style="margin:0">' +
    '<button class="tab' + (S.analysisScope === "buoi" ? " active" : "") + '" data-scope="buoi">Theo buổi</button>' +
    '<button class="tab' + (S.analysisScope === "tiet" ? " active" : "") + '" data-scope="tiet">Theo tiết (phút)</button></div>' +
    '<span class="spacer"></span>' +
    '<button class="btn" disabled title="Tính năng Ultra - giai đoạn 6">' + ico("lock") + " Dự báo xu hướng</button>" +
    '<button class="btn" disabled title="Tính năng Ultra - giai đoạn 6">' + ico("lock") + " Gợi ý nghỉ giữa tiết</button>" +
    "</div>" +
    '<div class="lock-note">' + ico("lock") + " Dự báo xu hướng, gợi ý thời điểm nghỉ và tiết dễ mất chú ý là tính năng bậc Ultra (giai đoạn 6), chưa phát hành.</div>" +
    '<div id="analysis-body" class="mt"></div></div>';
  $("an-student").onchange = (e) => { S.analysisSid = e.target.value; loadAnalysis(); };
  for (const t of $("view").querySelectorAll("[data-scope]")) {
    t.onclick = () => { S.analysisScope = t.dataset.scope; renderAnalysis(); };
  }
  await loadAnalysis();
}

/* Thẻ lịch sử dấu hiệu Tic (focusedge/tic_watch.py::tic_history, tài liệu 1).
   Ba điều bắt buộc trên màn hình: (1) không bao giờ là chẩn đoán - disclaimer in thẳng trong thẻ;
   (2) mốc 4 tuần chỉ có nghĩa khi đọc từ NHIỀU NGÀY quan sát, nên thẻ hiện mẫu số (ngày quan sát)
   cạnh tử số (ngày có mẫu hình); (3) tài liệu tham khảo y khoa đi kèm để người xem tự đối chiếu. */
function ticHistoryCard(h) {
  const head = '<div class="card mt"><h3>' + ico("warn") + " Theo dõi dấu hiệu Tic (tham khảo y khoa)</h3>";
  const disc = (h && h.disclaimer)
    ? '<div class="lock-note mt">' + ico("lock") + " " + esc(h.disclaimer) + "</div>" : "";
  if (!h) {
    return head + '<div class="muted small">Cần dashboard cục bộ để đọc lịch sử nhiều phiên từ SQLite — bản web-only chưa có tuyến này.</div></div>';
  }
  if (!h.sessions_observed) {
    return head + '<div class="muted small">Chưa có phiên đo nào của em này. Dấu hiệu Tic chỉ đánh giá được từ nhiều ngày quan sát, không từ một phiên.</div>' + disc + "</div>";
  }
  const chip = h.recommend_exam ? ["Nên cân nhắc đi khám chuyên khoa", "bad"]
    : h.level >= 1 ? ["Có mẫu hình - tiếp tục theo dõi", "warn"]
    : ["Chưa thấy mẫu hình lặp lại", ""];
  const refs = (h.references || []).map((r) =>
    '<div class="list-row"><span class="list-text"><a href="' + esc(r.url) + '" target="_blank" rel="noopener">' + esc(r.title) + "</a></span></div>").join("");
  const miles = (h.milestones || []).map((m) =>
    '<div class="sys-row"><span>' + esc(m.range) + "</span><b style=\"font-weight:500;text-align:right\">" + esc(m.meaning) + "</b></div>").join("");
  return head +
    '<div class="ctx-row"><span class="sit-chip ' + chip[1] + '">' + chip[0] + "</span>" +
    (h.demo ? '<span class="sit-chip warn">DEMO</span>' : "") + "</div>" +
    (h.label ? '<div class="avg-note">' + esc(h.label) + "</div>" : "") +
    '<div class="sys-row"><span>Số ngày đã quan sát</span><b>' + h.days_observed + "</b></div>" +
    '<div class="sys-row"><span>Ngày có mẫu hình giật lặp</span><b>' + h.days_with_pattern + "</b></div>" +
    '<div class="sys-row"><span>Kéo dài từ phiên đầu đến cuối</span><b>' + h.span_days + " ngày</b></div>" +
    '<div class="sys-row"><span>Số phiên có theo dõi Tic</span><b>' + h.sessions_observed + "</b></div>" +
    (h.note ? '<div class="avg-note mt">' + esc(h.note) + "</div>" : "") +
    (miles ? '<h3 class="mt">Mốc thời gian tham khảo (DSM-5)</h3>' + miles : "") +
    disc +
    (refs ? '<h3 class="mt">Tài liệu tham khảo</h3>' + refs : "") +
    "</div>";
}

async function loadAnalysis() {
  const body = $("analysis-body");
  if (!body) return;
  if (!S.analysisSid) {
    body.innerHTML = '<div class="chart-empty">' + (window.FC_MODE === "web"
      ? "Chưa có học sinh trong db của máy này. " + measureHint(", gắn tên cho một em rồi đo một phiên.")
      : "Chưa có học sinh. Thêm em ở màn Học sinh trước.") + "</div>";
    return;
  }
  body.innerHTML = '<div class="muted small">Đang tải…</div>';
  try {
    const [data, detail, ticHist] = await Promise.all([
      api("/api/analysis?id=" + encodeURIComponent(S.analysisSid) + "&scope=" + S.analysisScope),
      api("/api/student?id=" + encodeURIComponent(S.analysisSid)),
      // Tuyến đọc dọc nhiều phiên (tài liệu Tic, mốc 4 tuần). Bản web-only chưa có tuyến này:
      // null -> thẻ nói thật là cần dashboard cục bộ, không bịa lịch sử.
      api("/api/student/tic-history?id=" + encodeURIComponent(S.analysisSid)).catch(() => null)]);
    const stu = S.students.find((s) => s.student_id === S.analysisSid) || {name: S.analysisSid};
    let band = null, baseHtml = '<div class="muted small">' + (window.FC_MODE === "web"
      ? "Chưa có baseline cá nhân trong db — cần đủ phiên đo của chính em để dựng khoảng tin cậy."
      : "Chưa có baseline cá nhân (cần bậc Pro và đủ phiên đo).") + "</div>";
    if (detail.baseline) {
      try {
        const fj = JSON.parse(detail.baseline.features_json || "{}");
        const sei = (fj.features || {}).sei;
        if (sei) {
          band = {lo: sei.min != null ? sei.min : sei.typical - (sei.spread || 8),
                  hi: sei.max != null ? sei.max : sei.typical + (sei.spread || 8)};
          baseHtml = '<div class="sys-row"><span>SEI thường thấy</span><b>' + num(sei.typical, 0) + "</b></div>" +
            '<div class="sys-row"><span>Khoảng tin cậy</span><b>' + num(band.lo, 0) + " – " + num(band.hi, 0) + "</b></div>" +
            '<div class="sys-row"><span>Cửa sổ</span><b>' + detail.baseline.days + " ngày · " + detail.baseline.sessions_used + " phiên</b></div>" +
            '<div class="sys-row"><span>Trạng thái</span><b>' + esc(detail.baseline.status) + "</b></div>";
        }
      } catch (e) { /* features_json hỏng: hiện phần còn lại */ }
    }
    let chart;
    if (S.analysisScope === "tiet") {
      const pts = data.points || [];
      chart = pts.length
        ? lineChart(pts.map((p) => p.sei), pts.map((p) => fmtTime(p.at_ms)), {yMin: 0, yMax: 100, band})
        : '<div class="chart-empty">' + (window.FC_MODE === "web"
            ? "Bản web/app không ghi chuỗi phút (bảng metrics) — db chỉ có SEI trung bình của mỗi phiên. Chọn tab <b>Theo buổi</b> để xem số đã đo."
            : "Phiên gần nhất chưa có chuỗi phút (bảng metrics). Lõi camera sẽ ghi chuỗi này ở bước nối tiếp theo; dữ liệu mẫu có sẵn để xem hình dạng biểu đồ.") + "</div>";
    } else {
      const pts = data.points || [];
      chart = pts.length
        ? lineChart(pts.map((p) => p.sei), pts.map((p) => fmtDate(p.started_at_wall_ms)), {yMin: 0, yMax: 100, band})
        : '<div class="chart-empty">Em này chưa có phiên đo nào.</div>';
    }
    const sessions = detail.sessions || [];
    body.innerHTML =
      "<h3>" + esc(stu.name) + ' <span class="muted small">· ' +
      (S.analysisScope === "tiet" ? "chuỗi phút của phiên gần nhất" : "mỗi buổi một điểm") + "</span></h3>" +
      chart +
      (band ? '<div class="avg-note">Dải xanh = khoảng SEI thường thấy của chính em (baseline cá nhân).</div>' : "") +
      '<div class="grid grid-2 mt">' +
      '<div class="card"><h3>' + ico("target") + "Baseline cá nhân</h3>" + baseHtml + "</div>" +
      '<div class="card"><h3>' + ico("bell") + "Cảnh báo gần đây của em</h3>" +
      ((detail.alerts || []).length
        ? detail.alerts.slice(0, 8).map((a) =>
          '<div class="list-row"><span class="level-dot level-' + (a.level || 1) + '" style="margin-top:5px"></span>' +
          '<span class="list-time">' + fmtDateTime(a.raised_at_wall_ms) + '</span><span class="list-text">' + esc(a.message) + "</span></div>").join("")
        : '<div class="muted small">Chưa có cảnh báo.</div>') + "</div></div>" +
      '<div class="card mt"><h3>' + ico("report") + "Các phiên đo gần đây</h3>" +
      (sessions.length
        ? '<table class="tbl"><thead><tr><th>Bắt đầu</th><th>Kết thúc</th><th>Phút hợp lệ</th><th>SEI TB</th><th>EAR</th><th>MAR</th><th>Lệch ngữ cảnh</th><th>Nguồn</th></tr></thead><tbody>' +
          sessions.slice(0, 15).map((s) => {
            const f = s.features || {};
            const ce = s.notes && s.notes.context_eval;
            return "<tr><td>" + fmtDateTime(s.started_at_wall_ms) + "</td><td>" + fmtTime(s.ended_at_wall_ms) + "</td>" +
              "<td>" + Math.round(s.valid_ms / 60000) + "</td><td><b>" + num(s.sei_mean, 1) + "</b></td>" +
              "<td>" + num(f.ear && f.ear.mean, 2) + "</td><td>" + num(f.mar && f.mar.mean, 2) + "</td>" +
              "<td>" + (ce ? num(ce.off_task_s, 0) + "s" + (CTX_SEVERITY[ce.severity] ? " · " + CTX_SEVERITY[ce.severity] : "") : "—") + "</td>" +
              "<td>" + (s.notes && s.notes.demo ? '<span class="sit-chip warn">DEMO</span>' : esc(s.device)) + "</td></tr>";
          }).join("") + "</tbody></table>"
        : '<div class="muted small">Chưa có phiên đo.</div>') + "</div>" +
      ticHistoryCard(ticHist);
  } catch (e) { body.innerHTML = '<div class="chart-empty">Lỗi tải phân tích: ' + esc(e.message) + "</div>"; }
}

/* ============================== màn 5: Cảnh báo ============================== */
async function renderAlerts() {
  if (!S.students.length) { try { S.students = await api("/api/students"); } catch (e) { S.students = []; } }
  const f = S.alertFilter;
  const kinds = Object.keys(KIND_LABEL);
  const web = window.FC_MODE === "web";
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("bell") + "Cảnh báo</h2>" +
    "<p>Mức 1 (nhắc nhẹ) · Mức 2 (cần để ý) · Mức 3 (buồn ngủ / nguy hiểm). Lọc và đánh dấu đã xem.</p></div></div>" +
    (web ? '<div class="lock-note">' + ico("info") +
      " Bản web/app đọc bảng <b>alerts</b> trong db nhưng không tự ghi cảnh báo: khung đo chỉ ghi phiên và chỉ số. " +
      "Cảnh báo TRỰC TIẾP vẫn hiện ở màn Camera (dòng đỏ dưới khung hình, kèm âm báo) khi đang đo. " +
      'Danh sách trống nghĩa là chưa có cảnh báo nào được ghi vào db, không phải lỗi. Muốn có cảnh báo thật thì chạy bản Python cục bộ rồi bấm <b>Kết nối LIVE</b>.</div>' : "") +
    '<div class="card"><div class="toolbar">' +
    '<select id="al-student"><option value="">Tất cả học sinh</option>' + S.students.map((s) =>
      '<option value="' + esc(s.student_id) + '"' + (f.student === s.student_id ? " selected" : "") + ">" + esc(s.name) + "</option>").join("") + "</select>" +
    '<select id="al-kind"><option value="">Tất cả loại</option>' + kinds.map((k) =>
      '<option value="' + k + '"' + (f.kind === k ? " selected" : "") + ">" + KIND_LABEL[k] + "</option>").join("") + "</select>" +
    '<select id="al-level"><option value="">Mọi mức</option>' + [3, 2, 1].map((l) =>
      '<option value="' + l + '"' + (String(f.level) === String(l) ? " selected" : "") + ">Mức " + l + "</option>").join("") + "</select>" +
    '<input type="date" id="al-date" value="' + esc(f.date) + '">' +
    '<label class="small" style="display:flex;align-items:center;gap:6px"><input type="checkbox" id="al-unseen"' +
    (f.unseen ? " checked" : "") + "> Chỉ chưa xem</label>" +
    '<label class="small" style="display:flex;align-items:center;gap:6px"><input type="checkbox" id="al-summary"' +
    (S.alertSummary ? " checked" : "") + "> Tóm tắt theo tiết</label>" +
    '<span class="spacer"></span>' +
    '<label class="small" style="display:flex;align-items:center;gap:6px"><input type="checkbox" id="al-sound"' +
    (S.soundOn ? " checked" : "") + "> Âm báo</label>" +
    '<button class="btn" id="al-seen-all"' + (web ? ' disabled title="Bản web chỉ đọc db, không ghi đè lên phiên mà khung đo đang giữ"' : "") + '>' + ico("check") + " Tất cả đã xem</button></div>" +
    '<div id="alerts-list"></div></div>';
  const reload = () => {
    S.alertFilter = {student: $("al-student").value, kind: $("al-kind").value, level: $("al-level").value,
      date: $("al-date").value, unseen: $("al-unseen").checked ? "1" : ""};
    loadAlerts();
  };
  for (const id of ["al-student", "al-kind", "al-level", "al-date", "al-unseen"]) $(id).onchange = reload;
  $("al-sound").onchange = (e) => { S.soundOn = e.target.checked; localStorage.setItem("fc_sound", S.soundOn ? "1" : "0"); };
  $("al-summary").onchange = (e) => {
    S.alertSummary = e.target.checked;
    localStorage.setItem("fc_alert_summary", S.alertSummary ? "1" : "0");
    loadAlerts();
  };
  $("al-seen-all").onclick = async () => {
    try {
      await api("/api/alerts/seen_all", {});
      toast("Đã đánh dấu tất cả là đã xem", "ok");
      await loadAlerts(); tickFast();
    } catch (e) { toast(e.message, "err"); }
  };
  await loadAlerts();
}

async function loadAlerts() {
  const list = $("alerts-list");
  if (!list) return;
  const f = S.alertFilter;
  const qs = Object.entries(f).filter(([, v]) => v).map(([k, v]) => k + "=" + encodeURIComponent(v)).join("&");
  try {
    const alerts = await api("/api/alerts" + (qs ? "?" + qs : ""));
    if (!alerts.length) {
      list.innerHTML = '<div class="chart-empty">' + (window.FC_MODE === "web"
        ? "Db của máy này chưa có cảnh báo nào (bản web/app không ghi bảng alerts). Không có số liệu mẫu để thay vào."
        : "Không có cảnh báo nào khớp bộ lọc.") + "</div>";
      return;
    }
    if (S.alertSummary) {
      // Flood control: one row per session (per kid per lesson) with per-kind counts,
      // instead of hundreds of near-identical event rows. Alerts without a session_row
      // fall back to one group per student per day.
      const groups = new Map();
      for (const a of alerts) {
        const key = a.session_row != null ? "s" + a.session_row
          : (a.student_id || "?") + "|" + fmtDate(a.raised_at_wall_ms);
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(a);
      }
      list.innerHTML = [...groups.values()].map((g) => {
        g.sort((x, y) => x.raised_at_wall_ms - y.raised_at_wall_ms);
        const first = g[0], last = g[g.length - 1];
        const unseen = g.filter((a) => a.seen_at_ms == null).length;
        const maxLevel = Math.max(...g.map((a) => a.level));
        const byKind = {};
        for (const a of g) byKind[a.kind] = (byKind[a.kind] || 0) + 1;
        const kindsHtml = Object.entries(byKind).sort((x, y) => y[1] - x[1])
          .map(([k, n]) => esc(KIND_LABEL[k] || k) + " ×" + n).join(" · ");
        const names = [...new Set(g.map((a) => a.student_name || a.student_id))];
        return '<div class="list-row' + (unseen ? " tint-red" : "") + '">' +
          '<span class="level-dot level-' + maxLevel + '" style="margin-top:5px" title="Mức cao nhất ' + maxLevel + '"></span>' +
          '<span class="list-time">' + fmtTime(first.raised_at_wall_ms) + "–" + fmtTime(last.raised_at_wall_ms) +
          "<br>" + fmtDate(first.raised_at_wall_ms) + "</span>" +
          '<span class="list-text"><b>' + esc(names.join(", ")) + "</b> — " + g.length + " cảnh báo" +
          (unseen ? " (" + unseen + " chưa xem)" : " (đã xem hết)") +
          '<br><span class="muted small">' + kindsHtml + "</span></span></div>";
      }).join("");
      return;
    }
    list.innerHTML = alerts.map((a) => {
      const unseen = a.seen_at_ms == null;
      return '<div class="list-row' + (unseen ? " tint-red" : "") + '">' +
        '<span class="level-dot level-' + a.level + '" style="margin-top:5px" title="Mức ' + a.level + '"></span>' +
        '<span class="list-time">' + fmtTime(a.raised_at_wall_ms) + "<br>" + fmtDate(a.raised_at_wall_ms) + "</span>" +
        '<span class="list-text"><b>' + esc(a.student_name || a.student_id) + "</b> — " + esc(a.message) +
        ' <span class="muted small">(' + (KIND_LABEL[a.kind] || a.kind) + " · mức " + a.level + ")</span></span>" +
        '<span style="margin-left:auto;flex:none">' +
        (unseen ? '<button class="btn btn-sm" data-seen="' + a.alert_id + '">' + ico("check") + " Đã xem</button>"
                : '<span class="muted small">đã xem ' + fmtTime(a.seen_at_ms) + "</span>") +
        "</span></div>";
    }).join("");
    for (const btn of list.querySelectorAll("[data-seen]")) {
      btn.onclick = async () => {
        try {
          await api("/api/alerts/seen", {alert_id: parseInt(btn.dataset.seen, 10)});
          await loadAlerts(); tickFast();
        } catch (e) { toast(e.message, "err"); }
      };
    }
  } catch (e) { list.innerHTML = '<div class="chart-empty">Lỗi: ' + esc(e.message) + "</div>"; }
}

/* Âm báo ngắn bằng WebAudio - không cần file âm thanh ngoài. Trình duyệt chỉ cho phát
   sau tương tác đầu tiên của người dùng; nếu chưa được phép thì bỏ qua lặng lẽ. */
let audioCtx = null;
let _lastBeepWallMs = 0;
function beep() {
  if (!S.soundOn) return;
  const now = Date.now();
  if (_lastBeepWallMs && now - _lastBeepWallMs < 300000) return; // Tuyệt đối 5 phút mới được kêu 1 lần (chống spam)
  _lastBeepWallMs = now;
  try {
    audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
    if (audioCtx.state === "suspended") audioCtx.resume();
    const o = audioCtx.createOscillator(), g = audioCtx.createGain();
    o.frequency.value = 880; g.gain.value = 0.07;
    o.connect(g); g.connect(audioCtx.destination);
    o.start();
    g.gain.exponentialRampToValueAtTime(0.0001, audioCtx.currentTime + 0.35);
    o.stop(audioCtx.currentTime + 0.4);
  } catch (e) { /* chưa được phép phát âm thanh: bỏ qua */ }
}

/* ============================== màn 6: Báo cáo ============================== */
function renderReports() {
  const canCsv = allows("csv_export");
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("report") + "Báo cáo</h2>" +
    "<p>Báo cáo phiên đo (một tiết), theo ngày, theo tuần. In / lưu PDF từ trình duyệt.</p></div></div>" +
    '<div class="card"><div class="toolbar">' +
    '<div class="tabs" style="margin:0">' +
    [["session", "Phiên đo"], ["day", "Theo ngày"], ["week", "Theo tuần"], ["weekstu", "Tuần · từng em"]].map(([v, l]) =>
      '<button class="tab' + (S.reportScope === v ? " active" : "") + '" data-rscope="' + v + '">' + l + "</button>").join("") + "</div>" +
    '<input type="date" id="rp-date" value="' + S.reportDate + '">' +
    '<span class="spacer"></span>' +
    '<button class="btn btn-primary" id="rp-print">' + ico("report") + " In / lưu PDF</button>" +
    '<button class="btn" id="rp-csv"' + (canCsv ? "" : ' disabled title="Cần bậc Pro trở lên"') + ">" + ico("download") + " Xuất CSV</button>" +
    '<button class="btn" id="rp-json"' + (canCsv ? "" : ' disabled title="Cần bậc Pro trở lên"') + ">" + ico("download") + " Xuất JSON</button>" +
    '<button class="btn" id="rp-parent"' + (allows("parent_report") ? "" : ' disabled title="Cần bậc Pro Max (đổi trong Cài đặt)"') + ">" + ico(allows("parent_report") ? "report" : "lock") + " Phiếu phụ huynh</button></div>" +
    (canCsv && allows("parent_report") ? "" : '<div class="lock-note">' + ico("lock") + " Xuất CSV/Excel cần bậc Pro; phiếu nhận xét phụ huynh cần bậc Pro Max (đổi bậc ở Cài đặt).</div>") +
    '<div id="report-body" class="mt"></div>' +
    '<div class="avg-note mt">Số liệu chỉ để hỗ trợ giáo viên, không dùng làm căn cứ kỷ luật duy nhất.</div></div>';
  for (const t of $("view").querySelectorAll("[data-rscope]")) {
    t.onclick = () => { S.reportScope = t.dataset.rscope; renderReports(); };
  }
  $("rp-date").onchange = (e) => { S.reportDate = e.target.value || todayStr(); loadReport(); };
  $("rp-print").onclick = async () => {
    if (S.reportScope !== "weekstu") {
      window.open((window.FC_API_BASE || "") + "/api/reports/print?scope=" + S.reportScope + "&date=" + S.reportDate, "_blank");
      return;
    }
    // Tuần theo từng em được gộp ngay trong trình duyệt từ các phiên đã lưu, nên máy chủ không có tuyến in cho nó.
    try {
      const data = await getReportData();
      const html = window.FC_REPORT.printHtml("weekstu", S.reportDate, data,
        "Gộp từ các phiên đo đã lưu trong tuần (Thứ Hai – Chủ Nhật). SEI của tuần là trung bình có trọng số theo phút hợp lệ.");
      window.open(URL.createObjectURL(new Blob([html], { type: "text/html" })), "_blank");
    } catch (e) { toast("Không in được: " + e.message, "err"); }
  };
  $("rp-csv").onclick = async () => {
    if (S.reportScope !== "weekstu") {
      downloadCsv("/api/reports/csv?scope=" + S.reportScope + "&date=" + S.reportDate,
        "bao_cao_" + S.reportScope + "_" + S.reportDate + ".csv");
      return;
    }
    try {
      const data = await getReportData();
      // BOM UTF-8: Excel đọc tệp không BOM theo bảng mã ANSI và làm hỏng tên có dấu.
      saveBlob(new Blob(["\uFEFF" + window.FC_REPORT.weekStudentsCsv(data.rows)], { type: "text/csv;charset=utf-8" }),
        "bao_cao_weekstu_" + S.reportDate + ".csv");
    } catch (e) { toast("Lỗi xuất CSV: " + e.message, "err"); }
  };
  $("rp-json").onclick = async () => {
    try {
      const data = await getReportData();
      const extra = S.reportScope === "weekstu" ? { week_dates: data.days, sessions: data.sessions } : null;
      saveBlob(new Blob([window.FC_REPORT.reportJson(S.reportScope, S.reportDate, data, extra)], { type: "application/json" }),
        "bao_cao_" + S.reportScope + "_" + S.reportDate + ".json");
    } catch (e) { toast("Lỗi xuất JSON: " + e.message, "err"); }
  };
  $("rp-parent").onclick = () => { if (allows("parent_report")) parentSlipOpen(); else toast("Phiếu phụ huynh cần bậc Pro Max — đổi bậc ở Cài đặt.", "err"); };
  if (!$("slip-host")) { const h = document.createElement("div"); h.id = "slip-host"; $("view").appendChild(h); }
  loadReport();
}

/* Dữ liệu của báo cáo đang chọn. "Tuần · từng em" không có tuyến riêng: 7 lần gọi theo ngày (Thứ Hai → Chủ Nhật)
   rồi gộp ở đây, nên chạy y hệt trên server Python, SQLite trong trình duyệt và bộ mẫu. */
async function getReportData() {
  if (S.reportScope !== "weekstu") return api("/api/reports?scope=" + S.reportScope + "&date=" + S.reportDate);
  const R = window.FC_REPORT;
  const days = R.weekDates(S.reportDate);
  const parts = await Promise.all(days.map((d) => api("/api/reports?scope=session&date=" + d)));
  const seen = new Set();
  const sessions = [];
  parts.forEach((p) => (p.rows || []).forEach((r) => {
    if (r.session_row != null) { if (seen.has(r.session_row)) return; seen.add(r.session_row); }
    sessions.push(r);
  }));
  const rows = R.weekByStudent(sessions);
  return { scope: "weekstu", rows: rows, summary: R.weekSummary(sessions, rows), sessions: sessions, days: days };
}

async function loadReport() {
  const body = $("report-body");
  if (!body) return;
  body.innerHTML = '<div class="muted small">Đang tải…</div>';
  try {
    const data = await getReportData();
    const s = data.summary || {};
    let head =
      '<div class="grid grid-stats">' +
      statCard("blue", "report", "rp-n", s.sessions || 0, "Phiên đo", "rp-n-sub", (s.students || 0) + " em có dữ liệu") +
      statCard("green", "clock", "rp-min", num(s.valid_min, 0), "Phút hợp lệ", "rp-min-sub", "tổng thời gian đo được") +
      statCard("orange", "target", "rp-sei", num(s.sei_mean, 1), "SEI trung bình", "rp-sei-sub", "thang 0–100") +
      statCard("red", "bell", "rp-alerts", s.alerts || 0, "Cảnh báo", "rp-alerts-sub", "trong kỳ báo cáo") +
      "</div>";
    let table;
    if (!data.rows.length) {
      table = '<div class="chart-empty">Không có phiên đo nào trong kỳ này. Chọn ngày khác, bật camera đo, hoặc nạp dữ liệu mẫu ở màn Sao lưu.</div>';
    } else if (data.scope === "session") {
      table = '<table class="tbl mt"><thead><tr><th>Mã HS</th><th>Tên</th><th>Bắt đầu</th><th>Kết thúc</th>' +
        "<th>Phút hợp lệ</th><th>SEI TB</th><th>Cảnh báo</th><th>Nguồn</th></tr></thead><tbody>" +
        data.rows.map((r) => "<tr><td>" + esc(r.student_id) + "</td><td>" + esc(r.student_name) + "</td>" +
          "<td>" + fmtDateTime(r.started_at_wall_ms) + "</td><td>" + fmtTime(r.ended_at_wall_ms) + "</td>" +
          "<td>" + Math.round(r.valid_ms / 60000) + "</td><td><b>" + num(r.sei_mean, 1) + "</b></td>" +
          "<td>" + r.alerts + "</td><td>" + (r.notes && r.notes.demo ? '<span class="sit-chip warn">DEMO</span>' : esc(r.source)) + "</td></tr>").join("") +
        "</tbody></table>";
    } else if (data.scope === "weekstu") {
      table = '<div class="tbl-wrap"><table class="tbl mt"><thead><tr><th>Mã HS</th><th>Tên</th><th>Số phiên</th><th>Số ngày</th>' +
        "<th>Phút hợp lệ</th><th>SEI TB tuần</th><th>SEI thấp nhất</th><th>Cảnh báo</th></tr></thead><tbody>" +
        data.rows.map((r) => "<tr><td>" + esc(r.student_id) + "</td><td>" + esc(r.name) + "</td>" +
          "<td>" + r.sessions + "</td><td>" + r.days + "</td><td>" + Math.round(r.valid_ms / 60000) + "</td>" +
          "<td><b>" + num(r.sei_mean, 1) + "</b></td><td>" + num(r.sei_low, 1) + "</td><td>" + r.alerts + "</td></tr>").join("") +
        '</tbody></table></div><div class="avg-note mt">Gộp từ ' + data.summary.sessions + " phiên đo trong tuần " +
        esc(data.days[0]) + " → " + esc(data.days[6]) + ". SEI tuần là trung bình có trọng số theo phút hợp lệ: " +
        "phiên dài nặng hơn phiên ngắn.</div>";
    } else if (data.scope === "day") {
      table = '<table class="tbl mt"><thead><tr><th>Mã HS</th><th>Tên</th><th>Số phiên</th><th>Phút hợp lệ</th>' +
        "<th>SEI TB</th><th>Cảnh báo</th></tr></thead><tbody>" +
        data.rows.map((r) => "<tr><td>" + esc(r.student_id) + "</td><td>" + esc(r.name) + "</td>" +
          "<td>" + r.sessions + "</td><td>" + Math.round(r.valid_ms / 60000) + "</td>" +
          "<td><b>" + num(r.sei_mean, 1) + "</b></td><td>" + r.alerts + "</td></tr>").join("") + "</tbody></table>";
    } else {
      table = '<table class="tbl mt"><thead><tr><th>Ngày</th><th>Số phiên</th><th>Phút hợp lệ</th>' +
        "<th>SEI TB</th><th>Cảnh báo</th></tr></thead><tbody>" +
        data.rows.map((r) => "<tr><td>" + fmtDate(new Date(r.date + "T00:00:00").getTime()) + "</td>" +
          "<td>" + r.sessions + "</td><td>" + Math.round(r.valid_ms / 60000) + "</td>" +
          "<td><b>" + num(r.sei_mean, 1) + "</b></td><td>" + r.alerts + "</td></tr>").join("") + "</tbody></table>";
    }
    body.innerHTML = head + table;
  } catch (e) { body.innerHTML = '<div class="chart-empty">Lỗi: ' + esc(e.message) + "</div>"; }
}

/* ============================== màn 7: Sao lưu ============================== */
async function renderBackup() {
  const web = window.FC_MODE === "web";
  const st = S.settings;
  const wi = (S.overview && S.overview.web_info) || {};
  const sysDb = (S.system && S.system.database) || {};
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("database") + "Sao lưu & lưu trữ</h2>" +
    "<p>" + (web
      ? "Bản web/app: nút bên phải xuất CHÍNH tệp SQLite đang chứa số đo của máy này và tải xuống trình duyệt. Trang tĩnh nên không có thư mục backups/ trên máy chủ."
      : window.FC_MODE === "demo"
      ? "Bản DEMO trong trình duyệt: “sao lưu” là ảnh chụp bộ số mẫu đang nằm trong tab này, không có file .db trên đĩa và tải lại trang là mất."
      : "Sao lưu/khôi phục bằng API backup của SQLite (an toàn cả khi đang đo). Có thể chép thư mục backups/ ra USB.") + "</p></div></div>" +
    '<div class="grid grid-2">' +
    '<div class="card"><h3>' + ico("save") + "Sao lưu thủ công</h3>" +
    '<button class="btn btn-primary" id="bk-now">' + ico("save") + (web ? " Tải tệp .db ngay" : " Sao lưu ngay") + "</button>" +
    '<div id="backup-list" class="mt"></div></div>' +
    (web
      ? '<div class="card"><h3>' + ico("info") + "Nguồn dữ liệu thật</h3>" +
        '<div class="sys-row"><span>Nơi đọc</span><span>' + esc(wi.where || sysDb.path || "SQLite của máy này") + "</span></div>" +
        '<div class="sys-row"><span>Dung lượng db</span><span>' + fmtBytes(wi.bytes || sysDb.bytes || 0) + "</span></div>" +
        '<div class="sys-row"><span>Đã ghi</span><span>' + (wi.sessions || 0) + " phiên · " + (wi.students || 0) + " em</span></div>" +
        '<div class="lock-note mt">' + ico("lock") + " Trang này chỉ ĐỌC. Thêm em, gắn tên và ngưỡng đo làm ngay ở màn Camera; cảnh báo và đồng ý làm ở bản Python cục bộ; khôi phục từ tệp .db làm ở thẻ bên dưới. Ghi từ đây sẽ đè mất phiên mà khung đo đang giữ.</div>" +
        (MEASURE_HREF ? '<div class="cam-buttons mt"><a class="btn" href="#camera">' + ico("camera") + " Về màn Camera</a>" +
          '<a class="btn" href="' + MEASURE_HREF + '">' + ico("settings") + " Màn đo gốc (test)</a></div>" : "") + "</div>"
      : '<div class="card"><h3>' + ico("settings") + "Tự động & thời gian lưu</h3>" +
        switchRow("bk-auto", "Sao lưu tự động theo lịch", st.auto_backup === "1") +
        '<div class="lock-note mt">' + ico("info") + " Khi bật: dashboard tự sao lưu mỗi 24 giờ trong lúc đang chạy (mọi bậc, không cần Pro). Bản tự động quá thời gian lưu sẽ bị dọn; bản sao lưu tay không bao giờ bị xóa tự động.</div>" +
        '<div class="field mt"><label>Thời gian lưu dữ liệu (ngày) — tự xóa phiên quá hạn</label>' +
        '<input type="number" id="bk-retention" min="1" value="' + esc(st.retention_days || "180") + '"></div>' +
        '<button class="btn" id="bk-retention-save">' + ico("check") + " Lưu thời gian lưu</button>" +
        '<div class="lock-note mt">Dữ liệu quá hạn bị dọn khi lưu cài đặt và mỗi lần mở app. Xóa học sinh (chuyển trường) xóa dây chuyền ngay ở màn Học sinh.</div></div>') +
    "</div>" +
    (web ? restoreCardHtml() :
      '<div class="card mt"><h3>' + ico("info") + "Dữ liệu mẫu (DEMO)</h3>" +
      "<p class=\"muted small\">Bộ số mẫu có gắn nhãn để nghiệm thu giao diện khi chưa đo thật: 5 em \"HS Mẫu\", 6 buổi/em, cảnh báo và biểu đồ 30 phút. " +
      "Mọi dòng mẫu đều đánh dấu DEMO; nút xóa chỉ đụng dấu vết đó, không xóa dữ liệu thật.</p>" +
      '<div class="cam-buttons"><button class="btn" id="bk-seed">' + ico("upload") + " Nạp dữ liệu mẫu</button>" +
      '<button class="btn btn-danger" id="bk-purge">' + ico("trash") + " Xóa dữ liệu mẫu</button></div></div>");
  $("bk-now").onclick = async () => {
    try {
      const r = await api("/api/backup", {});
      if (r.ok) {
        toast((r.downloaded ? "Đã tải tệp .db: " : "Đã sao lưu: ") + r.file + " (" + fmtBytes(r.bytes) + ")", "ok");
        loadBackups();
      } else toast((r.error || "Lỗi sao lưu") + (r.hint ? " — " + r.hint : ""), "err");
    } catch (e) { toast(e.message, "err"); }
  };
  const rf = $("bk-restore-file");
  if (rf) rf.onchange = () => { const f = rf.files && rf.files[0]; rf.value = ""; if (f) restoreFromFile(f); };
  if (!web) {
    const auto = $("bk-auto");
    auto.onchange = async (e) => {
      try {
        const r = await api("/api/settings", {auto_backup: e.target.checked ? "1" : "0"});
        if (r.ok) { S.settings.auto_backup = e.target.checked ? "1" : "0"; toast("Đã lưu", "ok"); }
        else { e.target.checked = !e.target.checked; toast(r.error || "Lỗi", "err"); }
      } catch (err) { e.target.checked = !e.target.checked; toast(err.message, "err"); }
    };
    $("bk-retention-save").onclick = async () => {
      const days = parseInt($("bk-retention").value, 10);
      if (!days || days < 1) { toast("Số ngày phải >= 1", "err"); return; }
      try {
        const r = await api("/api/settings", {retention_days: String(days)});
        if (r.ok) { S.settings.retention_days = String(days); toast("Đã lưu. Phiên quá hạn đã được dọn.", "ok"); tickFast(); }
        else toast(r.error || "Lỗi", "err");
      } catch (e) { toast(e.message, "err"); }
    };
    $("bk-seed").onclick = seedDemo;
    $("bk-purge").onclick = purgeDemo;
  }
  loadBackups();
}

/* ---- Khôi phục từ tệp .db (bản web, IndexedDB của trình duyệt) ---- */
function restoreCardHtml() {
  if (window.fcDb) {
    return '<div class="card mt"><h3>' + ico("refresh") + "Khôi phục từ tệp .db</h3>" +
      '<div class="muted small">Bản app lưu db ở một tệp trên đĩa; hãy thay tệp đó khi app đang đóng.</div></div>';
  }
  return '<div class="card mt"><h3>' + ico("refresh") + "Khôi phục từ tệp .db</h3>" +
    '<p class="muted small">Chọn tệp .db đã tải ở nút “Tải tệp .db ngay” (từ máy này hoặc máy khác) để nạp lại số đo. ' +
    "Bản hiện tại được tự tải xuống trước khi bị thay. Cần dừng camera trước.</p>" +
    '<input type="file" id="bk-restore-file" accept=".db,.sqlite,.sqlite3,application/octet-stream"></div>';
}

function idbPutDb(bytes) {
  return new Promise((resolve, reject) => {
    const r = indexedDB.open("focuschangeai-sql", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("db");
    r.onerror = () => reject(r.error || new Error("IndexedDB"));
    r.onsuccess = () => {
      const d = r.result;
      const tx = d.transaction("db", "readwrite");
      tx.objectStore("db").put(bytes, "focuschange.db");
      tx.oncomplete = () => { d.close(); resolve(); };
      tx.onerror = tx.onabort = () => { d.close(); reject(tx.error || new Error("Không ghi được IndexedDB")); };
    };
  });
}

async function restoreFromFile(file) {
  if (cameraIsRunning()) { toast("Hãy dừng camera trước khi khôi phục.", "err"); return; }
  if (file.size < 1024 || file.size > 200 * 1024 * 1024) { toast("Kích thước tệp không hợp lệ.", "err"); return; }
  let bytes;
  try { bytes = new Uint8Array(await file.arrayBuffer()); } catch (e) { toast("Không đọc được tệp.", "err"); return; }
  const head = String.fromCharCode.apply(null, Array.from(bytes.slice(0, 15)));
  if (head !== "SQLite format 3") { toast("Đây không phải tệp SQLite (.db).", "err"); return; }
  if (!window.FC_WEBDB_VALIDATE) { toast("Chưa sẵn sàng kiểm tra tệp, thử lại sau giây lát.", "err"); return; }
  const v = await window.FC_WEBDB_VALIDATE(bytes);
  if (!v.ok) { toast(v.error, "err"); return; }
  confirmModal("Khôi phục dữ liệu",
    "<p>Thay <b>toàn bộ số đo trên trình duyệt này</b> bằng tệp <b>" + esc(file.name) + "</b> (" +
    v.sessions + " phiên · " + v.students + " em)?</p>" +
    "<p>Bản hiện tại sẽ tự tải xuống trước. Trang sẽ tải lại sau khi khôi phục.</p>",
    async () => {
      try {
        try { await api("/api/backup", {}); } catch (e) { /* db rỗng thì không có gì để lưu */ }
        const fr = $("live-frame");
        if (fr) fr.remove();                       // khung đo tự lưu khi đóng; chờ nó xong rồi mới ghi đè
        await new Promise((res) => setTimeout(res, 1200));
        await idbPutDb(bytes);
        toast("Đã khôi phục. Đang tải lại…", "ok");
        setTimeout(() => location.reload(), 700);
      } catch (e) { toast("Lỗi khôi phục: " + e.message, "err"); }
    }, {danger: true, label: "Khôi phục"});
}

async function loadBackups() {
  const list = $("backup-list");
  if (!list) return;
  try {
    const files = await api("/api/backups");
    const web = window.FC_MODE === "web";
    if (!files.length) {
      list.innerHTML = '<div class="muted small">' + (web
        ? "Chưa tải tệp .db nào trong tab này. Bấm nút bên trên để tải bản sao số đo thật."
        : "Chưa có bản sao lưu nào trong thư mục backups/.") + "</div>";
      return;
    }
    list.innerHTML = '<table class="tbl"><thead><tr><th>File</th><th>Dung lượng</th><th>Lúc tạo</th><th></th></tr></thead><tbody>' +
      files.map((f) => "<tr><td>" + esc(f.file) + "</td><td>" + fmtBytes(f.bytes) + "</td>" +
        "<td>" + fmtDateTime(f.mtime_ms) + "</td>" +
        "<td>" + (web ? '<span class="muted small">đã tải xuống</span>'
          : '<button class="btn btn-sm" data-restore="' + esc(f.file) + '">' + ico("refresh") + " Khôi phục</button>") +
        "</td></tr>").join("") +
      "</tbody></table>";
    for (const btn of list.querySelectorAll("[data-restore]")) {
      btn.onclick = () => confirmModal("Khôi phục dữ liệu",
        "<p>Ghi đè <b>toàn bộ dữ liệu hiện tại</b> bằng bản sao <b>" + esc(btn.dataset.restore) + "</b>?</p>" +
        "<p>Camera đang chạy sẽ bị dừng và các phiên đang đo sẽ chốt trước. Hành động không hoàn tác.</p>",
        async () => {
          try {
            const r = await api("/api/restore", {file: btn.dataset.restore});
            if (r.ok) { toast("Đã khôi phục dữ liệu", "ok"); await tickFast(); await tickSlow(); route(); }
            else toast(r.error || "Lỗi khôi phục", "err");
          } catch (e) { toast(e.message, "err"); }
        }, {danger: true, label: "Khôi phục"});
    }
  } catch (e) { list.innerHTML = '<div class="muted small">Lỗi: ' + esc(e.message) + "</div>"; }
}

async function seedDemo() {
  try {
    const r = await api("/api/demo/seed", {});
    if (r.ok) {
      toast(r.already ? "Dữ liệu mẫu đã có sẵn" : "Đã nạp dữ liệu mẫu: " + r.students + " em, " + r.sessions + " phiên, " + r.alerts + " cảnh báo", "ok");
      await tickFast(); await tickSlow(); route();
    } else toast(r.error || "Lỗi", "err");
  } catch (e) { toast(e.message, "err"); }
}
async function purgeDemo() {
  try {
    const r = await api("/api/demo/purge", {});
    if (r.ok) {
      toast("Đã xóa dữ liệu mẫu (" + r.students + " em, " + r.trend_buckets + " bucket biểu đồ). Dữ liệu thật giữ nguyên.", "ok");
      await tickFast(); await tickSlow(); route();
    } else toast(r.error || "Lỗi", "err");
  } catch (e) { toast(e.message, "err"); }
}

/* ============================== màn 8: Cài đặt ============================== */
/* Bản web/app không có config.yaml, không có bậc sản phẩm và không đếm cảnh báo: hiện đúng những
   gì trang này làm được thay vì vẽ một biểu mẫu cài đặt rồi từ chối từng nút khi bấm. */
function renderWebSettings() {
  const wi = (S.overview && S.overview.web_info) || {};
  const s = LIVE.state;
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("settings") + "Cài đặt</h2>" +
    "<p>Đo trực tiếp trong dashboard và đọc số đo thật từ SQLite của máy này. Ngưỡng cảnh báo và bậc sản phẩm chỉ có ở bản Python cục bộ.</p></div></div>" +
    '<div class="grid grid-2">' +
    '<div class="card"><h3>' + ico("info") + "Bản web chỉnh được gì</h3>" +
    '<div class="sys-row"><span>Camera, số mặt, độ phân giải, bộ xử lý</span><span class="status-ok">Màn Camera</span></div>' +
    '<div class="sys-row"><span>Độ nhạy, tần suất quét, hiệu chuẩn lại</span><span class="status-ok">Màn Camera</span></div>' +
    '<div class="sys-row"><span>Danh sách em, gắn tên, học mặt, nhập/xuất</span><span class="status-ok">Màn Camera</span></div>' +
    '<div class="sys-row"><span>Ngưỡng báo chi tiết (EAR, MAR, PERCLOS…)</span><span>Màn đo gốc / bản Python</span></div>' +
    '<div class="sys-row"><span>Cảnh báo vào db, đồng ý, khôi phục</span><span>Bản Python cục bộ</span></div>' +
    '<div class="sys-row"><span>Bậc sản phẩm</span><span>Không áp dụng cho bản web</span></div>' +
    '<div class="sys-row"><span>Xuất CSV / in báo cáo / tải .db</span><span class="status-ok">Mở, không cần bậc</span></div>' +
    switchRow("set-sound", "Âm cảnh báo", S.soundOn) +
    '<div class="cam-buttons mt">' +
    (MEASURE_HREF ? '<a class="btn btn-primary" href="#camera">' + ico("camera") + " Về màn Camera</a>" +
      '<a class="btn" href="' + MEASURE_HREF + '">' + ico("settings") + " Màn đo gốc (test)</a>" : "") +
    '<button class="btn" id="set-live">' + ico("refresh") + " Kết nối LIVE</button></div></div>" +
    '<div class="card"><h3>' + ico("database") + "Dữ liệu đang đọc</h3>" +
    '<div class="sys-row"><span>Nơi đọc</span><span>' + esc(wi.where || "SQLite của máy này") + "</span></div>" +
    '<div class="sys-row"><span>Số phiên</span><span>' + (wi.sessions || 0) + "</span></div>" +
    '<div class="sys-row"><span>Số em</span><span>' + (wi.students || 0) + "</span></div>" +
    '<div class="sys-row"><span>Khung đo nhúng</span><span>' + (LIVE.boot === "ready"
      ? '<span class="status-ok">Sẵn sàng</span>' : LIVE.boot === "error"
      ? '<span class="status-bad">Lỗi nạp</span>' : '<span class="status-off">' + (LIVE.boot === "loading" ? "Đang nạp…" : "Chưa nạp") + "</span>") + "</span></div>" +
    '<div class="sys-row"><span>Quyền ghi db</span><span>' + (s && s.lease
      ? esc(s.lease.surface === "dashboard" ? "dashboard này" : "màn đo gốc")
      : '<span class="muted">chưa cửa sổ nào giữ</span>') + "</span></div>" +
    '<div class="lock-note mt">' + ico("lock") + " Khung đo giữ cả tệp db trong bộ nhớ và ghi lại sau mỗi phiên đóng; " +
    "trang báo cáo chỉ đọc lại tệp đó. Hai cửa sổ cùng đo sẽ đè mất phiên của nhau, nên chỉ cửa sổ giữ quyền mới được mở camera.</div></div>" +
    "</div>" +
    '<div class="card mt"><h3>' + ico("info") + "Nhật ký thao tác trong tab này</h3>" +
    '<div id="audit-body"></div></div>';
  $("set-live").onclick = connectLive;
  $("set-sound").onchange = (e) => {
    S.soundOn = e.target.checked;
    localStorage.setItem("fc_sound", S.soundOn ? "1" : "0");
    livePillPaint();
  };
  $("audit-body").innerHTML = auditRows();
}

async function renderSettings() {
  if (window.FC_MODE === "web") { renderWebSettings(); return; }
  const st = S.settings;
  const tiers = (S.system && S.system.tiers) || ["standard", "pro", "promax", "ultra"];
  const gates = (S.system && S.system.gates) || DEFAULT_GATES;
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("settings") + "Cài đặt</h2>" +
    "<p>Bậc sản phẩm, ngưỡng cảnh báo, giao diện, nhật ký kiểm toán.</p></div></div>" +
    '<div class="grid grid-2">' +
    '<div class="card"><h3>' + ico("target") + "Bậc sản phẩm</h3>" +
    '<div class="field"><label>Bậc hiện tại (một cờ duy nhất quyết định tính năng mở)</label>' +
    '<select id="set-tier">' + tiers.map((t) => '<option value="' + t + '"' + (st.tier === t ? " selected" : "") + ">" +
      TIER_LABEL[t] + "</option>").join("") + "</select></div>" +
    '<button class="btn btn-primary" id="set-tier-save">' + ico("check") + " Lưu bậc</button>" +
    '<table class="tbl mt"><thead><tr><th>Tính năng</th><th>Bậc tối thiểu</th><th>Hiện tại</th></tr></thead><tbody>' +
    Object.keys(gates).map((g) => "<tr><td>" + (GATE_LABEL[g] || g) + "</td><td>" + TIER_LABEL[gates[g]] + "</td>" +
      "<td>" + (allows(g) ? '<span class="status-ok">' + ico("check") + " Mở</span>"
                          : '<span class="status-off">' + ico("lock") + " Khóa</span>") + "</td></tr>").join("") +
    "</tbody></table></div>" +
    '<div><div class="card"><h3>' + ico("bell") + "Ngưỡng cảnh báo</h3>" +
    '<div class="field"><label>Số lần lặp tối thiểu trước khi báo (mặc định 2)</label>' +
    '<input type="number" id="set-repeat" min="1" value="' + esc(st.alert_repeat || "2") + '"></div>' +
    '<div class="field"><label>Số lần lặp cho ngưỡng cá nhân (4–5)</label>' +
    '<input type="number" id="set-personal" min="1" value="' + esc(st.alert_personal_repeat || "4") + '"></div>' +
    '<div class="field"><label>Thời gian chờ giữa 2 cảnh báo cùng loại (giây, mặc định 300s = 5 phút)</label>' +
    '<input type="number" id="set-cooldown" min="0" value="' + esc(st.alert_cooldown_s || "300") + '"></div>' +
    '<button class="btn btn-primary" id="set-alert-save">' + ico("check") + " Lưu ngưỡng</button>" +
    '<div class="lock-note mt">Ngưỡng lưu trong CSDL để chỉnh được như đặc tả 7.2; lõi đo sẽ đọc các giá trị này ở bước nối tiếp theo (hiện lõi dùng ngưỡng trong mã/config).</div></div>' +
    '<div class="card mt"><h3>' + ico("eye") + "Giao diện & ngôn ngữ</h3>" +
    '<div class="field"><label>Giao diện</label><select id="set-theme">' +
    '<option value="light"' + (st.theme !== "dark" ? " selected" : "") + ">Sáng</option>" +
    '<option value="dark"' + (st.theme === "dark" ? " selected" : "") + ">Tối</option></select></div>" +
    '<div class="field"><label>Ngôn ngữ</label><select id="set-lang"><option value="vi"' + (window.FC_I18N && FC_I18N.lang === "en" ? "" : " selected") + '>Tiếng Việt</option><option value="en"' + (window.FC_I18N && FC_I18N.lang === "en" ? " selected" : "") + '>English</option></select></div>' +
    '<div class="lock-note">Ứng dụng có tiếng Việt và English. Lựa chọn được lưu trong trình duyệt này và đổi luôn khung đo. Chữ nào chưa có bản dịch sẽ giữ tiếng Việt.</div></div></div>' +
    "</div>" +
    '<div class="card mt"><h3>' + ico("users") + "Người dùng & vai trò</h3>" +
    '<div class="lock-note">' + ico("lock") + " Đăng nhập, vai trò (quản trị / GVCN / GV bộ môn), khóa tạm khi sai mật khẩu và 2 bước là hạng mục giai đoạn 1–2 tiếp theo (đặc tả mục 9). " +
    "Bản này chạy offline trên máy giáo viên, server chỉ nghe 127.0.0.1 nên chưa mở ra mạng.</div></div>" +
    '<div class="card mt"><h3>' + ico("clock") + 'Nhật ký kiểm toán <button class="btn btn-sm" id="audit-refresh" style="margin-left:auto">' +
    ico("refresh") + " Làm mới</button></h3>" +
    '<div id="audit-body"></div></div>';
  $("set-tier-save").onclick = async () => {
    try {
      const r = await api("/api/settings", {tier: $("set-tier").value});
      if (r.ok) { await refreshSettings(); toast("Đã đổi bậc sản phẩm", "ok"); await tickSlow(); renderSettings(); }
      else toast(r.error || "Lỗi", "err");
    } catch (e) { toast(e.message, "err"); }
  };
  $("set-alert-save").onclick = async () => {
    const body = {alert_repeat: $("set-repeat").value, alert_personal_repeat: $("set-personal").value,
      alert_cooldown_s: $("set-cooldown").value};
    for (const v of Object.values(body)) {
      if (v === "" || isNaN(parseInt(v, 10)) || parseInt(v, 10) < 0) { toast("Ngưỡng phải là số >= 0", "err"); return; }
    }
    try {
      const r = await api("/api/settings", body);
      if (r.ok) { await refreshSettings(); toast("Đã lưu ngưỡng cảnh báo", "ok"); }
      else toast(r.error || "Lỗi", "err");
    } catch (e) { toast(e.message, "err"); }
  };
  $("set-theme").onchange = async (e) => {
    document.documentElement.dataset.theme = e.target.value;
    try {
      const r = await api("/api/settings", {theme: e.target.value});
      if (r.ok) S.settings.theme = e.target.value;
    } catch (err) { toast(err.message, "err"); }
  };
  $("audit-refresh").onclick = async () => { await tickSlow(); toast("Đã làm mới nhật ký", "ok"); };
  $("audit-body").innerHTML = auditRows();
}

function auditRows() {
  if (!(S.audit || []).length) return '<div class="muted small">Chưa có mục nhật ký nào.</div>';
  return '<table class="tbl"><thead><tr><th>Thời gian</th><th>Người</th><th>Hành động</th><th>Chi tiết</th></tr></thead><tbody>' +
    S.audit.slice(0, 40).map((a) => "<tr><td>" + fmtDateTime(a.at_ms) + "</td><td>" + esc(a.actor) + "</td>" +
      "<td>" + esc(ACTION_LABEL[a.action] || a.action) + "</td><td class=\"muted\">" + esc(auditDetail(a)) + "</td></tr>").join("") +
    "</tbody></table>";
}

async function refreshSettings() {
  try { S.settings = await api("/api/settings"); } catch (e) { /* giữ giá trị cũ */ }
}

/* ============================== phím tắt ============================== */
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") {
    if (document.fullscreenElement) {
      document.exitFullscreen().catch(() => {});
      return;
    }
    if (modalOpen()) { closeModal(); return; }
  }
  const tag = (e.target.tagName || "").toLowerCase();
  if (tag === "input" || tag === "select" || tag === "textarea" || modalOpen()) return;
  const k = e.key.toLowerCase();
  // Bản web: phím tắt đi thẳng xuống khung đo nhúng, đúng các phím mà màn đo gốc vẫn dùng.
  if (window.FC_MODE === "web" && LIVE.host) {
    if (k === "f") { e.preventDefault(); fullscreen(LIVE.layer); return; }
    if (k === "y" || k === "o" || k === "g") {
      e.preventDefault();
      liveToggle(k);
      toast({y: "Hộp người/vật", o: "Số liệu đè khung", g: "Tăng sáng"}[k] + " — đã đổi", "ok");
      return;
    }
    if (k === "n" && S.view === "camera") { e.preventDefault(); liveCmd("next", {}, 8000).then(livePaint); return; }
    if (k === "h" && S.view === "camera") {
      S.hidePeople = !S.hidePeople;
      const sw = $("lv-people"); if (sw) sw.checked = !S.hidePeople;
      LIVE.kidsKey = "";
      livePaint();
      return;
    }
    return;
  }
  if (k === "f") {
    const wrap = S.view === "camera" ? $("cam-wrap") : $("ov-wrap");
    if (wrap) { e.preventDefault(); fullscreen(wrap); }
  } else if (k === "y" || k === "o" || k === "g") {
    const key = {y: "boxes", o: "overlay", g: "brighten"}[k];
    const cur = S.live && S.live.camera && S.live.camera.options;
    const val = !(cur && cur[key]);
    setOption(key, val);
    const sw = $({boxes: "sw-boxes", overlay: "sw-overlay", brighten: "sw-brighten"}[key]);
    if (sw) sw.checked = val;
    toast({boxes: "Hộp người/mặt", overlay: "Số liệu đè khung", brighten: "Tăng sáng"}[key] + (val ? ": bật" : ": tắt"));
  } else if (k === "h" && S.view === "camera") {
    S.hidePeople = !S.hidePeople;
    const sw = $("sw-people"); if (sw) sw.checked = !S.hidePeople;
    updateCameraParts();
  } else if (k === "n" && S.view === "camera") {
    const people = (S.live && S.live.people) || [];
    if (!people.length) return;
    const idx = people.findIndex((p) => p.track_id === S.focusTrack);
    S.focusTrack = people[(idx + 1) % people.length].track_id;
    updateCameraParts();
  }
});

/* ============================== định tuyến & khởi động ============================== */
/* ============================== Buổi học: đường thời gian + tóm tắt tự động + báo cáo một trang ==============================
   Khi camera đo, mức chú ý trung bình lớp được gom thành điểm 10 giây và lưu trong localStorage của trình duyệt này
   (khóa fc_lessons_v1, giữ 20 buổi gần nhất). Dữ liệu này CHỈ là số tổng hợp của lớp, không có ảnh, không có tên em.
   Giáo viên đánh dấu sự kiện (giảng bài, làm nhóm, hỏi đáp...) để so sánh các đoạn. Mọi câu chữ sinh tự động đều viết
   hai ngôn ngữ qua LL(vi, en), và là gợi ý tham khảo, không phải kết luận về từng học sinh. */
const LL = (vi, en) => (window.FC_I18N ? FC_I18N.L(vi, en) : vi);
const LESSON_KEY = "fc_lessons_v1";
const LESSON_BUCKET_MS = 10000;     // một điểm = trung bình 10 giây
const LESSON_REOPEN_MS = 120000;    // camera nháy tắt < 2 phút thì vẫn là cùng một buổi
const LESSON_KEEP = 20;
const LESSON_KINDS = {
  lecture: ["Giảng bài", "Lecture"], group: ["Làm bài nhóm", "Group work"], qa: ["Hỏi đáp", "Q&A"],
  seat: ["Làm bài cá nhân", "Seatwork"], review: ["Ôn tập", "Review"], brk: ["Nghỉ giữa tiết", "Break"],
};
const LESSON = { list: null, cur: null, acc: null, view: null, mode: "one", weekOff: 0 };

function lessonLoad() {
  if (LESSON.list) return LESSON.list;
  let l = [];
  try { l = JSON.parse(localStorage.getItem(LESSON_KEY) || "[]"); } catch (e) { l = []; }
  LESSON.list = Array.isArray(l) ? l.filter((x) => x && Array.isArray(x.buckets)) : [];
  return LESSON.list;
}
function lessonSave() {
  try { localStorage.setItem(LESSON_KEY, JSON.stringify(LESSON.list.slice(-LESSON_KEEP))); } catch (e) { /* hết dung lượng thì bỏ qua */ }
}
function lessonSource() {
  if (window.FC_MODE === "web") {
    const s = LIVE.state || {};
    return { running: !!s.running, hs: liveAverageSources(s).hs };
  }
  const st = S.live || {};
  return {
    running: !!(st.camera && st.camera.running),
    hs: (st.people || []).filter((p) => p && p.student_id).map((p) => ({ sei: p.sei })),
  };
}
function lessonFlush(now) {
  const a = LESSON.acc, c = LESSON.cur;
  if (a && c && a.n >= 3) c.buckets.push({ t: now, sei: Math.round(a.sum / a.n * 10) / 10, n: a.n });
  LESSON.acc = null;
}
function lessonRecord() {
  const { running, hs } = lessonSource();
  const now = Date.now();
  const list = lessonLoad();
  if (!LESSON.cur) {   // sau khi tải lại trang: nối tiếp buổi đang mở nếu còn mới
    const last = list[list.length - 1];
    if (last && !last.demo && !last.endedAt && last.buckets.length && now - last.buckets[last.buckets.length - 1].t < LESSON_REOPEN_MS) LESSON.cur = last;
  }
  if (!running) {
    if (LESSON.cur && !LESSON.cur.endedAt) {
      lessonFlush(now);
      LESSON.cur.endedAt = LESSON.cur.buckets.length ? LESSON.cur.buckets[LESSON.cur.buckets.length - 1].t : now;
      lessonSave(); lessonRefresh();
    }
    return;
  }
  const sei = meanValue(hs.map((h) => h.sei));
  if (sei == null) return;
  if (LESSON.cur && LESSON.cur.endedAt && now - LESSON.cur.endedAt < LESSON_REOPEN_MS) LESSON.cur.endedAt = null;   // camera chỉ nháy tắt
  if (!LESSON.cur || LESSON.cur.endedAt) {
    LESSON.cur = { id: now, startedAt: now, endedAt: null, title: "", buckets: [], events: [], demo: window.FC_MODE === "demo" };   // mô phỏng không bao giờ được coi là số đo thật
    list.push(LESSON.cur);
    if (list.length > LESSON_KEEP) list.shift();
    LESSON.view = LESSON.cur.id;
    lessonRefresh();   // bật ngay các nút đánh dấu sự kiện
  }
  if (!LESSON.acc) LESSON.acc = { start: now, sum: 0, n: 0 };
  LESSON.acc.sum += sei; LESSON.acc.n++;
  if (now - LESSON.acc.start >= LESSON_BUCKET_MS) {
    lessonFlush(now);
    lessonSave();
    lessonRefresh();
  }
}
function lessonAddEvent(kind, text) {
  const c = LESSON.cur;
  if (!c || c.endedAt) { toast(LL("Hãy bật camera đo trước khi đánh dấu sự kiện.", "Start the camera before marking an event."), "err"); return; }
  c.events.push({ t: Date.now(), kind, text: text || "" });
  lessonSave(); lessonPaint();
  toast(LL("Đã đánh dấu: ", "Marked: ") + lessonEvLabel(c.events[c.events.length - 1]), "ok");
}
function lessonEvLabel(e) {
  if (e.kind === "note") return e.text || LL("Ghi chú", "Note");
  const k = LESSON_KINDS[e.kind];
  return k ? LL(k[0], k[1]) : (e.text || e.kind);
}
function lessonSelected() {
  const list = lessonLoad();
  return list.find((x) => x.id === LESSON.view) || LESSON.cur || list[list.length - 1] || null;
}

/* ---- phân tích ---- */
function lessonStats(ls) {
  const b = ls.buckets;
  if (!b || b.length < 3) return null;
  const t0 = ls.startedAt, t1 = ls.endedAt || b[b.length - 1].t;
  const vals = b.map((x) => x.sei);
  const mean = (a) => a.reduce((x, y) => x + y, 0) / a.length;
  const st = {
    t0, t1, durMin: Math.max(1, Math.round((t1 - t0) / 60000)),
    avg: mean(vals), hi: vals.filter((v) => v >= 70).length / vals.length, lo: vals.filter((v) => v < 50).length / vals.length,
  };
  let mi = 0; vals.forEach((v, i) => { if (v < vals[mi]) mi = i; });
  st.min = { v: vals[mi], t: b[mi].t };
  // các đoạn theo sự kiện (đoạn đầu chưa có nhãn)
  const marks = [{ t: t0, ev: null }].concat((ls.events || []).slice().sort((x, y) => x.t - y.t).map((e) => ({ t: e.t, ev: e })));
  st.segs = marks.map((m, i) => {
    const end = i + 1 < marks.length ? marks[i + 1].t : t1 + 1;
    const inb = b.filter((x) => x.t >= m.t && x.t < end);
    return { ev: m.ev, from: m.t, to: Math.min(end, t1), min: Math.max(0, (Math.min(end, t1) - m.t) / 60000), avg: inb.length ? mean(inb.map((x) => x.sei)) : null, n: inb.length };
  }).filter((s) => s.n >= 3);
  // tụt mạnh nhất: trung bình 2 phút sau so với 2 phút trước
  const W = 12; let best = null;
  for (let i = W; i + W <= b.length; i++) {
    const drop = mean(vals.slice(i - W, i)) - mean(vals.slice(i, i + W));
    if (!best || drop > best.drop) best = { drop, t: b[i].t, from: mean(vals.slice(i - W, i)), to: mean(vals.slice(i, i + W)) };
  }
  st.drop = best && best.drop >= 7 ? best : null;
  return st;
}
function lessonSummary(ls, st) {
  const r = (v) => Math.round(v);
  const out = [], tips = [];
  out.push(LL(`Buổi học kéo dài ${st.durMin} phút, mức chú ý trung bình của lớp là ${r(st.avg)}%.`,
    `The lesson lasted ${st.durMin} min and the class average engagement was ${r(st.avg)}%.`));
  out.push(LL(`Khoảng ${r(st.hi * 100)}% thời gian mức chú ý từ 70% trở lên, và ${r(st.lo * 100)}% thời gian dưới 50%.`,
    `About ${r(st.hi * 100)}% of the time engagement was 70% or higher, and ${r(st.lo * 100)}% of the time it was below 50%.`));
  if (st.drop) {
    const at = r((st.drop.t - st.t0) / 60000);
    const prev = (ls.events || []).filter((e) => e.t <= st.drop.t && st.drop.t - e.t <= 6 * 60000).sort((x, y) => y.t - x.t)[0];
    const afterVi = prev ? `, sau khi chuyển sang “${lessonEvLabel(prev)}”` : "";
    const afterEn = prev ? `, after switching to “${lessonEvLabel(prev)}”` : "";
    out.push(LL(`Mức chú ý giảm rõ nhất quanh phút ${at} (từ ${r(st.drop.from)}% xuống ${r(st.drop.to)}%)${afterVi}.`,
      `Engagement dropped most around minute ${at} (from ${r(st.drop.from)}% to ${r(st.drop.to)}%)${afterEn}.`));
  }
  out.push(LL(`Thấp nhất là ${r(st.min.v)}% ở phút ${r((st.min.t - st.t0) / 60000)}.`,
    `The lowest point was ${r(st.min.v)}% at minute ${r((st.min.t - st.t0) / 60000)}.`));
  const labelled = st.segs.filter((s) => s.ev && s.min >= 2);
  if (labelled.length >= 2) {
    const hiS = labelled.reduce((a, b) => (b.avg > a.avg ? b : a)), loS = labelled.reduce((a, b) => (b.avg < a.avg ? b : a));
    if (hiS !== loS) out.push(LL(`Đoạn giữ chú ý tốt nhất: “${lessonEvLabel(hiS.ev)}” (${r(hiS.avg)}%); đoạn thấp nhất: “${lessonEvLabel(loS.ev)}” (${r(loS.avg)}%).`,
      `Best-held segment: “${lessonEvLabel(hiS.ev)}” (${r(hiS.avg)}%); lowest segment: “${lessonEvLabel(loS.ev)}” (${r(loS.avg)}%).`));
  }
  // gợi ý theo luật, nói thận trọng
  if (st.lo >= 0.3) tips.push(LL("Hơn 30% thời gian chú ý dưới 50%: cân nhắc đổi hoạt động hoặc nghỉ ngắn ở các đoạn giảm.",
    "Engagement was below 50% for over 30% of the time: consider changing activity or a short break where it dips."));
  const longLec = labelled.find((s) => s.ev.kind === "lecture" && s.min >= 20 && s.avg < st.avg - 5);
  if (longLec) tips.push(LL("Đoạn giảng liên tục từ 20 phút trở lên có chú ý thấp hơn mức chung: thử xen câu hỏi hoặc thảo luận ngắn.",
    "A lecture block of 20+ minutes ran below the overall level: try mixing in a question or a short discussion."));
  const act = labelled.filter((s) => s.ev.kind === "group" || s.ev.kind === "qa"), lec = labelled.filter((s) => s.ev.kind === "lecture");
  if (act.length && lec.length) {
    const aA = act.reduce((x, s) => x + s.avg, 0) / act.length, lA = lec.reduce((x, s) => x + s.avg, 0) / lec.length;
    if (aA - lA >= 8) tips.push(LL("Hoạt động nhóm/hỏi đáp giữ chú ý cao hơn đoạn giảng bài: có thể tăng tỷ lệ hoạt động tương tác.",
      "Group work / Q&A held engagement better than lecturing: consider more interactive time."));
  }
  if (st.avg >= 75 && st.lo < 0.1) tips.push(LL("Mức chú ý nhìn chung tốt và ổn định.", "Engagement was good and steady overall."));
  if (!tips.length) tips.push(LL("Chưa thấy điểm bất thường cần điều chỉnh rõ ràng.", "No clear issue stands out that needs adjusting."));
  return { out, tips };
}

/* ---- vẽ ---- */
function lessonChart(ls) {
  const b = ls.buckets, W = 720, H = 230, mL = 34, mR = 12, mT = 22, mB = 26;
  const t0 = ls.startedAt, t1 = Math.max(ls.endedAt || 0, b.length ? b[b.length - 1].t : t0, t0 + 60000);
  const X = (t) => mL + (t - t0) / (t1 - t0) * (W - mL - mR);
  const Y = (v) => mT + (100 - Math.max(0, Math.min(100, v))) / 100 * (H - mT - mB);
  const band = (a, c, col) => '<rect x="' + mL + '" y="' + Y(a).toFixed(1) + '" width="' + (W - mL - mR) + '" height="' + (Y(c) - Y(a)).toFixed(1) + '" fill="' + col + '" opacity=".10"/>';
  let s = '<svg viewBox="0 0 ' + W + " " + H + '" role="img" aria-label="' + esc(LL("Mức chú ý trung bình lớp theo thời gian", "Class average engagement over time")) + '">' +
    band(100, 70, "#16a34a") + band(70, 50, "#d97706") + band(50, 0, "#dc2626");
  [0, 25, 50, 75, 100].forEach((v) => {
    s += '<line x1="' + mL + '" x2="' + (W - mR) + '" y1="' + Y(v).toFixed(1) + '" y2="' + Y(v).toFixed(1) + '" style="stroke:var(--line)" stroke-width="1"/>' +
      '<text x="' + (mL - 5) + '" y="' + (Y(v) + 3).toFixed(1) + '" text-anchor="end" font-size="10" fill="currentColor">' + v + "</text>";
  });
  const totalMin = (t1 - t0) / 60000, step = totalMin <= 12 ? 2 : totalMin <= 30 ? 5 : totalMin <= 70 ? 10 : 15;
  for (let m = 0; m <= totalMin; m += step) {
    s += '<text x="' + X(t0 + m * 60000).toFixed(1) + '" y="' + (H - 8) + '" text-anchor="middle" font-size="10" fill="currentColor">' + m + "′</text>";
  }
  if (b.length) {
    s += '<polyline fill="none" style="stroke:var(--blue)" stroke-width="2" stroke-linejoin="round" points="' +
      b.map((p) => X(p.t).toFixed(1) + "," + Y(p.sei).toFixed(1)).join(" ") + '"/>';
  }
  (ls.events || []).forEach((e, i) => {
    const x = X(e.t), right = x > W * 0.78;
    s += '<line x1="' + x.toFixed(1) + '" x2="' + x.toFixed(1) + '" y1="' + (mT - 4) + '" y2="' + (H - mB) + '" stroke="#7c3aed" stroke-width="1.2" stroke-dasharray="4 3"/>' +
      '<text x="' + (x + (right ? -3 : 3)).toFixed(1) + '" y="' + (mT - 8 + (i % 2) * 11) + '" text-anchor="' + (right ? "end" : "start") + '" font-size="10" font-weight="600" fill="#7c3aed">' + esc(lessonEvLabel(e)) + "</text>";
  });
  return s + "</svg>";
}
function lessonTitle(ls) {
  return ls.title || (ls.demo ? LL("Tiết mẫu · Toán 9A", "Sample lesson · Math 9A") : LL("Buổi học", "Lesson"));
}
function lessonFmtTime(ms) {
  const d = new Date(ms), p = (n) => (n < 10 ? "0" : "") + n;
  return p(d.getHours()) + ":" + p(d.getMinutes());
}
function lessonDate(ms) {
  const d = new Date(ms), p = (n) => (n < 10 ? "0" : "") + n;
  return p(d.getDate()) + "/" + p(d.getMonth() + 1) + "/" + d.getFullYear();
}
function lessonReportHtml(ls) {
  const st = lessonStats(ls);
  const title = lessonTitle(ls);
  let h = '<div class="lesson-head"><div><h3 class="lesson-title">' + esc(title) + (ls.demo ? ' <span class="sit-chip warn">DEMO</span>' : "") + "</h3>" +
    '<div class="muted small">' + esc(lessonDate(ls.startedAt)) + " · " + esc(lessonFmtTime(ls.startedAt)) +
    (ls.endedAt ? "–" + esc(lessonFmtTime(ls.endedAt)) : " · " + esc(LL("đang ghi", "recording"))) + "</div></div>" +
    '<div class="muted small lesson-brand">FocusChange AI</div></div>';
  if (!st) {
    return h + '<div class="chart-empty">' + esc(LL("Chưa đủ dữ liệu (cần ít nhất vài chục giây đo). Cứ để camera chạy, đường thời gian sẽ hiện dần.",
      "Not enough data yet (at least a few tens of seconds are needed). Keep the camera running and the timeline will fill in.")) + "</div>";
  }
  const sm = lessonSummary(ls, st);
  const kpi = (v, l) => '<div class="lesson-kpi"><b>' + v + "</b><span>" + esc(l) + "</span></div>";
  h += '<div class="lesson-kpis">' +
    kpi(Math.round(st.avg) + "%", LL("Chú ý trung bình lớp", "Class average engagement")) +
    kpi(Math.round(st.hi * 100) + "%", LL("Thời gian ≥ 70%", "Time at ≥ 70%")) +
    kpi(Math.round(st.lo * 100) + "%", LL("Thời gian < 50%", "Time below 50%")) +
    kpi(Math.round(st.min.v) + "%", LL("Thấp nhất (phút ", "Lowest (minute ") + Math.round((st.min.t - st.t0) / 60000) + ")") + "</div>";
  h += '<div class="lesson-chart">' + lessonChart(ls) + '<div class="lesson-legend muted small"><i style="background:#16a34a"></i>≥ 70% <i style="background:#d97706"></i>50–70% <i style="background:#dc2626"></i>&lt; 50% <i class="ev"></i>' + esc(LL("sự kiện do giáo viên đánh dấu", "event marked by the teacher")) + "</div></div>";
  h += '<h4 class="lesson-h">' + esc(LL("Tóm tắt tự động", "Automatic summary")) + "</h4><ul class=\"lesson-list\">" + sm.out.map((x) => "<li>" + esc(x) + "</li>").join("") + "</ul>";
  h += '<h4 class="lesson-h">' + esc(LL("Gợi ý (tham khảo)", "Suggestions (for reference)")) + "</h4><ul class=\"lesson-list\">" + sm.tips.map((x) => "<li>" + esc(x) + "</li>").join("") + "</ul>";
  const segs = st.segs.filter((s) => s.min >= 1);
  if (segs.length > 1 || (segs[0] && segs[0].ev)) {
    h += '<h4 class="lesson-h">' + esc(LL("Các đoạn trong buổi", "Segments of the lesson")) + '</h4><div class="tablewrap"><table class="lesson-table"><thead><tr><th>' +
      esc(LL("Từ phút", "From min")) + "</th><th>" + esc(LL("Hoạt động", "Activity")) + "</th><th>" + esc(LL("Dài (phút)", "Length (min)")) + "</th><th>" + esc(LL("Chú ý TB", "Avg engagement")) + "</th></tr></thead><tbody>" +
      segs.map((s) => "<tr><td>" + Math.round((s.from - st.t0) / 60000) + "</td><td>" + esc(s.ev ? lessonEvLabel(s.ev) : LL("(đầu buổi)", "(start)")) + "</td><td>" + Math.round(s.min) +
        "</td><td>" + Math.round(s.avg) + "%</td></tr>").join("") + "</tbody></table></div>";
  }
  h += '<div class="lesson-foot muted small">' + esc(LL(
    "Số liệu là chỉ số hỗ trợ tổng hợp cho cả lớp (không có hình ảnh, không có tên học sinh), do thuật toán tự động tính từ camera. Dùng để điều chỉnh cách dạy, không dùng làm căn cứ kỷ luật.",
    "These are aggregate support indicators for the whole class (no images, no student names), computed automatically from the camera. Use them to adjust teaching, never as grounds for discipline.")) + "</div>";
  return h;
}

/* ---- Xuất dữ liệu & báo cáo tuần ---- */
function lessonDownload(name, text, type) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = document.createElement("a");
  a.href = url; a.download = name; document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}
const lessonCsv = (v) => { const s = String(v == null ? "" : v); return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s; };
function lessonStamp() { const d = new Date(), p = (n) => (n < 10 ? "0" : "") + n; return d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()); }
function lessonExportJson() {
  const l = lessonLoad();
  if (!l.length) { toast(LL("Chưa có buổi học để xuất.", "No lesson to export yet."), "err"); return; }
  lessonDownload("focuschange_lessons_" + lessonStamp() + ".json",
    JSON.stringify({ app: "FocusChange AI", exportedAt: new Date().toISOString(), note: "Aggregate class-level data only: no images, no student names.", lessons: l }, null, 2), "application/json");
}
function lessonExportPoints() {
  const ls = lessonSelected();
  if (!ls || !ls.buckets.length) { toast(LL("Chưa có dữ liệu để xuất.", "No data to export yet."), "err"); return; }
  const evs = (ls.events || []).slice().sort((a, b) => a.t - b.t);
  const rows = ls.buckets.map((b) => {
    let cur = ""; evs.forEach((e) => { if (e.t <= b.t) cur = lessonEvLabel(e); });
    return [Math.round((b.t - ls.startedAt) / 1000), lessonFmtTime(b.t), b.sei, b.n, cur].map(lessonCsv).join(",");
  });
  lessonDownload("focuschange_lesson_points_" + lessonStamp() + ".csv",
    "\ufeff" + ["elapsed_s", "clock_time", "avg_attention_sei", "samples", "active_event"].join(",") + "\n" + rows.join("\n"), "text/csv;charset=utf-8");
}
function lessonExportSummary() {
  const l = lessonLoad().filter((x) => x.buckets.length >= 3);
  if (!l.length) { toast(LL("Chưa có buổi học để xuất.", "No lesson to export yet."), "err"); return; }
  const rows = l.map((ls) => {
    const st = lessonStats(ls);
    return [lessonDate(ls.startedAt), lessonFmtTime(ls.startedAt), lessonTitle(ls), ls.demo ? "demo" : "real", st.durMin, st.avg.toFixed(1),
      (st.hi * 100).toFixed(0), (st.lo * 100).toFixed(0), st.min.v.toFixed(1), Math.round((st.min.t - st.t0) / 60000), (ls.events || []).length].map(lessonCsv).join(",");
  });
  lessonDownload("focuschange_lessons_summary_" + lessonStamp() + ".csv",
    "\ufeff" + ["date", "start", "title", "kind", "duration_min", "avg_attention_sei", "pct_time_ge70", "pct_time_lt50", "lowest_sei", "lowest_at_min", "events"].join(",") + "\n" + rows.join("\n"), "text/csv;charset=utf-8");
}
function lessonWeekRange(off) {
  const d = new Date(), day = (d.getDay() + 6) % 7;
  const from = new Date(d.getFullYear(), d.getMonth(), d.getDate() - day + off * 7);
  const to = new Date(from.getFullYear(), from.getMonth(), from.getDate() + 7);
  return { from: from.getTime(), to: to.getTime(), last: to.getTime() - 86400000 };
}
function lessonWeekHtml(off) {
  const r = lessonWeekRange(off);
  const inWeek = lessonLoad().filter((x) => x.buckets.length >= 3 && x.startedAt >= r.from && x.startedAt < r.to);
  const real = inWeek.filter((x) => !x.demo);
  const use = real.length ? real : inWeek;
  const head = '<div class="lesson-head"><div><h3 class="lesson-title">' + esc(LL("Báo cáo tuần", "Weekly report")) + "</h3>" +
    '<div class="muted small">' + esc(lessonDate(r.from) + " – " + lessonDate(r.last)) + "</div></div>" +
    '<div class="muted small lesson-brand">FocusChange AI</div></div>';
  if (!use.length) {
    return head + '<div class="chart-empty">' + esc(LL("Tuần này chưa có buổi học nào được ghi. Bật camera đo trong giờ học, hoặc nạp buổi mẫu để xem thử.",
      "No lesson was recorded this week. Start the camera during class, or load a sample lesson to try it.")) + "</div>";
  }
  const pack = use.map((ls) => ({ ls, st: lessonStats(ls) })).filter((x) => x.st).sort((a, b) => a.ls.startedAt - b.ls.startedAt);
  const nB = pack.reduce((a, x) => a + ls_n(x.ls), 0);
  function ls_n(ls) { return ls.buckets.length; }
  const wavg = pack.reduce((a, x) => a + x.st.avg * ls_n(x.ls), 0) / nB;
  const hi = pack.reduce((a, x) => a + x.st.hi * ls_n(x.ls), 0) / nB, lo = pack.reduce((a, x) => a + x.st.lo * ls_n(x.ls), 0) / nB;
  const totalMin = pack.reduce((a, x) => a + x.st.durMin, 0);
  const r0 = (v) => Math.round(v);
  const kpi = (v, l) => '<div class="lesson-kpi"><b>' + v + "</b><span>" + esc(l) + "</span></div>";
  let h = head + (!real.length ? '<div class="lesson-note-demo">' + esc(LL("Tuần này chỉ có buổi mẫu (DEMO), số liệu dưới đây không phải đo thật.", "This week only has sample (DEMO) lessons; the numbers below are not real measurements.")) + "</div>" : "") +
    '<div class="lesson-kpis">' + kpi(pack.length, LL("Số buổi", "Lessons")) + kpi(totalMin + " " + LL("phút", "min"), LL("Tổng thời gian đo", "Total measured time")) +
    kpi(r0(wavg) + "%", LL("Chú ý trung bình", "Average engagement")) + kpi(r0(lo * 100) + "%", LL("Thời gian < 50%", "Time below 50%")) + "</div>";
  // biểu đồ cột: mức chú ý TB từng buổi
  const W = 720, H = 190, mL = 34, mR = 10, mT = 14, mB = 34, bw = Math.min(46, (W - mL - mR) / pack.length - 8);
  let svg = '<svg viewBox="0 0 ' + W + " " + H + '" role="img" aria-label="' + esc(LL("Chú ý trung bình từng buổi trong tuần", "Average engagement of each lesson this week")) + '">';
  [0, 50, 100].forEach((v) => { const y = mT + (100 - v) / 100 * (H - mT - mB); svg += '<line x1="' + mL + '" x2="' + (W - mR) + '" y1="' + y + '" y2="' + y + '" style="stroke:var(--line)"/><text x="' + (mL - 5) + '" y="' + (y + 3) + '" text-anchor="end" font-size="10" fill="currentColor">' + v + "</text>"; });
  pack.forEach((x, i) => {
    const cx = mL + (i + 0.5) * (W - mL - mR) / pack.length, hh = x.st.avg / 100 * (H - mT - mB), col = x.st.avg >= 70 ? "#16a34a" : x.st.avg >= 50 ? "#d97706" : "#dc2626";
    svg += '<rect x="' + (cx - bw / 2).toFixed(1) + '" y="' + (H - mB - hh).toFixed(1) + '" width="' + bw.toFixed(1) + '" height="' + hh.toFixed(1) + '" rx="3" fill="' + col + '" opacity=".85"/>' +
      '<text x="' + cx.toFixed(1) + '" y="' + (H - mB - hh - 4).toFixed(1) + '" text-anchor="middle" font-size="10" font-weight="700" fill="currentColor">' + r0(x.st.avg) + "</text>" +
      '<text x="' + cx.toFixed(1) + '" y="' + (H - 18) + '" text-anchor="middle" font-size="10" fill="currentColor">' + esc(lessonDate(x.ls.startedAt).slice(0, 5)) + "</text>" +
      '<text x="' + cx.toFixed(1) + '" y="' + (H - 6) + '" text-anchor="middle" font-size="9" fill="currentColor">' + esc(lessonFmtTime(x.ls.startedAt)) + "</text>";
  });
  h += '<div class="lesson-chart">' + svg + "</svg></div>";
  // theo loại hoạt động, gộp mọi buổi
  const byKind = {};
  pack.forEach((x) => x.st.segs.forEach((s) => {
    if (!s.ev || s.ev.kind === "note") return;
    const k = byKind[s.ev.kind] || (byKind[s.ev.kind] = { sum: 0, n: 0, min: 0 });
    k.sum += s.avg * s.n; k.n += s.n; k.min += s.min;
  }));
  const kinds = Object.keys(byKind).map((k) => ({ k, avg: byKind[k].sum / byKind[k].n, min: byKind[k].min })).filter((x) => x.min >= 2).sort((a, b) => b.avg - a.avg);
  const out = [], tips = [];
  const best = pack.reduce((a, x) => (x.st.avg > a.st.avg ? x : a)), worst = pack.reduce((a, x) => (x.st.avg < a.st.avg ? x : a));
  out.push(LL(`Tuần này ghi ${pack.length} buổi, tổng ${totalMin} phút, chú ý trung bình ${r0(wavg)}%.`, `This week recorded ${pack.length} lesson(s), ${totalMin} min in total, with an average engagement of ${r0(wavg)}%.`));
  if (pack.length > 1) out.push(LL(`Buổi cao nhất: “${lessonTitle(best.ls)}” ${lessonDate(best.ls.startedAt).slice(0, 5)} (${r0(best.st.avg)}%); thấp nhất: “${lessonTitle(worst.ls)}” ${lessonDate(worst.ls.startedAt).slice(0, 5)} (${r0(worst.st.avg)}%).`,
    `Highest lesson: “${lessonTitle(best.ls)}” ${lessonDate(best.ls.startedAt).slice(0, 5)} (${r0(best.st.avg)}%); lowest: “${lessonTitle(worst.ls)}” ${lessonDate(worst.ls.startedAt).slice(0, 5)} (${r0(worst.st.avg)}%).`));
  if (pack.length >= 3) {
    const d = pack[pack.length - 1].st.avg - pack[0].st.avg;
    if (Math.abs(d) >= 5) out.push(d > 0 ? LL(`Xu hướng trong tuần đi lên (+${r0(d)} điểm từ buổi đầu đến buổi cuối).`, `The trend over the week is upward (+${r0(d)} points from the first to the last lesson).`)
      : LL(`Xu hướng trong tuần đi xuống (${r0(d)} điểm từ buổi đầu đến buổi cuối).`, `The trend over the week is downward (${r0(d)} points from the first to the last lesson).`));
  }
  if (kinds.length >= 2) {
    const kb = kinds[0], kw = kinds[kinds.length - 1];
    out.push(LL(`Loại hoạt động giữ chú ý tốt nhất: “${LL(LESSON_KINDS[kb.k][0], LESSON_KINDS[kb.k][1])}” (${r0(kb.avg)}%); thấp nhất: “${LL(LESSON_KINDS[kw.k][0], LESSON_KINDS[kw.k][1])}” (${r0(kw.avg)}%).`,
      `Activity type that held engagement best: “${LESSON_KINDS[kb.k][1]}” (${r0(kb.avg)}%); lowest: “${LESSON_KINDS[kw.k][1]}” (${r0(kw.avg)}%).`));
    if (kb.avg - kw.avg >= 8) tips.push(LL(`Chênh ${r0(kb.avg - kw.avg)} điểm giữa hai loại hoạt động: cân nhắc điều chỉnh tỷ lệ giữa chúng trong tuần sau.`, `There is a ${r0(kb.avg - kw.avg)}-point gap between the two activity types: consider adjusting their mix next week.`));
  }
  if (lo >= 0.3) tips.push(LL("Hơn 30% thời gian cả tuần chú ý dưới 50%: xem lại các buổi thấp nhất để tìm đoạn cần đổi nhịp.", "Engagement was below 50% for over 30% of the week: review the lowest lessons to find the parts that need a change of pace."));
  if (wavg >= 75 && lo < 0.1) tips.push(LL("Tuần này mức chú ý nhìn chung tốt và ổn định.", "This week's engagement was good and steady overall."));
  if (!tips.length) tips.push(LL("Chưa thấy điểm bất thường cần điều chỉnh rõ ràng.", "No clear issue stands out that needs adjusting."));
  h += '<h4 class="lesson-h">' + esc(LL("Tóm tắt tự động", "Automatic summary")) + '</h4><ul class="lesson-list">' + out.map((x) => "<li>" + esc(x) + "</li>").join("") + "</ul>";
  h += '<h4 class="lesson-h">' + esc(LL("Gợi ý (tham khảo)", "Suggestions (for reference)")) + '</h4><ul class="lesson-list">' + tips.map((x) => "<li>" + esc(x) + "</li>").join("") + "</ul>";
  h += '<h4 class="lesson-h">' + esc(LL("Các buổi trong tuần", "Lessons this week")) + '</h4><div class="tablewrap"><table class="lesson-table"><thead><tr><th>' +
    [LL("Ngày", "Date"), LL("Buổi", "Lesson"), LL("Dài (phút)", "Length (min)"), LL("Chú ý TB", "Avg"), "≥ 70%", "< 50%", LL("Thấp nhất", "Lowest")].map(esc).join("</th><th>") + "</th></tr></thead><tbody>" +
    pack.map((x) => "<tr><td>" + esc(lessonDate(x.ls.startedAt).slice(0, 5) + " " + lessonFmtTime(x.ls.startedAt)) + "</td><td>" + esc(lessonTitle(x.ls)) + "</td><td>" + x.st.durMin + "</td><td>" + r0(x.st.avg) + "%</td><td>" +
      r0(x.st.hi * 100) + "%</td><td>" + r0(x.st.lo * 100) + "%</td><td>" + r0(x.st.min.v) + "%</td></tr>").join("") + "</tbody></table></div>";
  if (kinds.length) h += '<h4 class="lesson-h">' + esc(LL("Theo loại hoạt động (gộp cả tuần)", "By activity type (whole week)")) + '</h4><div class="tablewrap"><table class="lesson-table"><thead><tr><th>' +
    [LL("Hoạt động", "Activity"), LL("Tổng phút", "Total min"), LL("Chú ý TB", "Avg engagement")].map(esc).join("</th><th>") + "</th></tr></thead><tbody>" +
    kinds.map((x) => "<tr><td>" + esc(LL(LESSON_KINDS[x.k][0], LESSON_KINDS[x.k][1])) + "</td><td>" + r0(x.min) + "</td><td>" + r0(x.avg) + "%</td></tr>").join("") + "</tbody></table></div>";
  h += '<div class="lesson-foot muted small">' + esc(LL("Số liệu là chỉ số hỗ trợ tổng hợp cho cả lớp (không có hình ảnh, không có tên học sinh). Dùng để điều chỉnh cách dạy, không dùng làm căn cứ kỷ luật.",
    "These are aggregate support indicators for the whole class (no images, no student names). Use them to adjust teaching, never as grounds for discipline.")) + "</div>";
  return h;
}

/* ---- màn hình ---- */
function lessonRefresh() { if (S.view === "lesson") lessonPaint(); }
function lessonPaint() {
  const box = $("lesson-body");
  if (!box) return;
  const week = LESSON.mode === "week";
  document.querySelectorAll(".lesson-one-only").forEach((e) => { e.hidden = week; });
  document.querySelectorAll(".lesson-week-only").forEach((e) => { e.hidden = !week; });
  document.querySelectorAll(".lesson-tab").forEach((t) => t.classList.toggle("on", t.dataset.mode === LESSON.mode));
  if (week) {
    const r = lessonWeekRange(LESSON.weekOff), lab = $("lesson-week-label");
    if (lab) lab.textContent = lessonDate(r.from) + " – " + lessonDate(r.last);
    box.innerHTML = lessonWeekHtml(LESSON.weekOff);
    return;
  }
  const ls = lessonSelected();
  const sel = $("lesson-select");
  const list = lessonLoad();
  if (sel) {
    sel.innerHTML = list.slice().reverse().map((x) => '<option value="' + x.id + '"' + (ls && x.id === ls.id ? " selected" : "") + ">" +
      esc(lessonTitle(x) + " · " + lessonDate(x.startedAt) + " " + lessonFmtTime(x.startedAt) + (x.demo ? " · DEMO" : "")) + "</option>").join("");
  }
  const live = LESSON.cur && !LESSON.cur.endedAt;
  const stat = $("lesson-status");
  if (stat) stat.innerHTML = live
    ? '<span class="sit-chip bad">● ' + esc(LL("Đang ghi", "Recording")) + "</span> " + esc(Math.max(0, Math.round((Date.now() - LESSON.cur.startedAt) / 60000)) + " " + LL("phút", "min"))
    : '<span class="muted small">' + esc(LL("Camera đang tắt: buổi học tự ghi khi bạn bật đo.", "Camera is off: a lesson records automatically when you start measuring.")) + "</span>";
  const editable = ls && LESSON.cur && ls.id === LESSON.cur.id && !LESSON.cur.endedAt;
  document.querySelectorAll(".lesson-ev").forEach((e) => { e.disabled = !editable; });
  const ti = $("lesson-title"); if (ti && ls && document.activeElement !== ti) ti.value = ls.title || "";
  if (ti) ti.disabled = !ls;
  box.innerHTML = ls ? lessonReportHtml(ls)
    : '<div class="chart-empty">' + esc(LL("Chưa có buổi học nào. Bật camera ở màn Camera và bấm Bắt đầu đo: buổi học tự được ghi. Hoặc nạp một buổi mẫu để xem thử.",
      "No lesson yet. Start the camera on the Camera screen and click Start measuring: a lesson is recorded automatically. Or load a sample lesson to try it.")) + "</div>";
}
function lessonDemo() {
  const now = Date.now(), start = now - 45 * 60000;
  let seed = 7; const rnd = () => { seed = (seed * 16807) % 2147483647; return seed / 2147483647 - 0.5; };
  // (phút bắt đầu, mức đầu, mức cuối)
  const plan = [[0, 80, 68], [12, 82, 84], [22, 80, 50], [29, 50, 56], [34, 70, 74], [38, 68, 76], [45, 76, 76]];
  const buckets = [];
  for (let t = 5; t < 45 * 60; t += 10) {
    const m = t / 60; let seg = plan[0];
    for (let i = 0; i < plan.length - 1; i++) if (m >= plan[i][0] && m < plan[i + 1][0]) seg = plan[i];
    const nx = plan[plan.indexOf(seg) + 1], k = (m - seg[0]) / (nx[0] - seg[0]);
    buckets.push({ t: start + t * 1000, sei: Math.round((seg[1] + (seg[2] - seg[1]) * k + rnd() * 6) * 10) / 10, n: 40 });
  }
  const ev = [[0, "lecture"], [12, "group"], [22, "lecture"], [34, "qa"], [38, "seat"]].map(([m, kind]) => ({ t: start + m * 60000, kind, text: "" }));
  const ls = { id: now, startedAt: start, endedAt: start + 45 * 60000, title: "", buckets, events: ev, demo: true };
  lessonLoad().push(ls);
  if (LESSON.list.length > LESSON_KEEP) LESSON.list.shift();
  LESSON.view = ls.id; lessonSave(); lessonPaint();
}
function lessonPrint() {
  if (LESSON.mode !== "week" && !lessonSelected()) { toast(LL("Chưa có buổi học để in.", "No lesson to print yet."), "err"); return; }
  document.body.classList.add("print-lesson");
  const done = () => { document.body.classList.remove("print-lesson"); window.removeEventListener("afterprint", done); };
  window.addEventListener("afterprint", done);
  window.print();
  setTimeout(done, 1500);
}
function renderLesson() {
  const kinds = Object.keys(LESSON_KINDS).map((k) => '<button class="btn lesson-ev" data-kind="' + k + '" type="button">' + esc(LL(LESSON_KINDS[k][0], LESSON_KINDS[k][1])) + "</button>").join("");
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("clock") + esc(LL("Buổi học", "Lesson")) + "</h2>" +
    "<p>" + esc(LL("Đường thời gian mức chú ý của cả lớp theo từng phút, kèm tóm tắt tự động, báo cáo một trang và báo cáo tuần để in hoặc lưu PDF.",
      "Whole-class engagement over time, with an automatic summary, a one-page report and a weekly report to print or save as PDF.")) + "</p></div></div>" +
    '<div class="card no-print"><div class="lesson-tabs">' +
    '<button class="lesson-tab" data-mode="one" type="button">' + esc(LL("Một buổi", "One lesson")) + "</button>" +
    '<button class="lesson-tab" data-mode="week" type="button">' + esc(LL("Báo cáo tuần", "Weekly report")) + "</button></div>" +
    '<div class="lesson-bar">' +
    '<label class="lesson-field lesson-one-only">' + esc(LL("Buổi học", "Lesson")) + '<select id="lesson-select"></select></label>' +
    '<label class="lesson-field grow lesson-one-only">' + esc(LL("Tên buổi (hiện trong báo cáo)", "Lesson name (shown in the report)")) + '<input id="lesson-title" type="text" maxlength="80" placeholder="' + esc(LL("Ví dụ: Toán 9A, tiết 2", "e.g. Math 9A, period 2")) + '"></label>' +
    '<div class="lesson-week-only lesson-weeknav" hidden><button class="btn" id="lesson-wprev" type="button" aria-label="' + esc(LL("Tuần trước", "Previous week")) + '">‹</button>' +
    '<b id="lesson-week-label"></b><button class="btn" id="lesson-wnext" type="button" aria-label="' + esc(LL("Tuần sau", "Next week")) + '">›</button>' +
    '<button class="btn" id="lesson-wnow" type="button">' + esc(LL("Tuần này", "This week")) + "</button></div>" +
    '<button class="btn btn-primary" id="lesson-print" type="button">' + ico("report") + " " + esc(LL("In / Lưu PDF", "Print / Save PDF")) + "</button>" +
    '<button class="btn lesson-one-only" id="lesson-demo" type="button">' + esc(LL("Nạp buổi mẫu", "Load sample lesson")) + "</button>" +
    '<button class="btn lesson-one-only" id="lesson-del" type="button">' + esc(LL("Xóa buổi này", "Delete this lesson")) + "</button></div>" +
    '<div class="lesson-export"><span class="muted small">' + esc(LL("Xuất dữ liệu:", "Export data:")) + "</span>" +
    '<button class="btn" id="lesson-xjson" type="button">JSON</button>' +
    '<button class="btn lesson-one-only" id="lesson-xpts" type="button">' + esc(LL("CSV từng điểm 10 giây", "CSV per 10 s")) + "</button>" +
    '<button class="btn" id="lesson-xsum" type="button">' + esc(LL("CSV tóm tắt các buổi", "CSV lesson summary")) + "</button></div>" +
    '<div class="lesson-one-only"><div id="lesson-status" class="lesson-status"></div>' +
    '<div class="lesson-evbar"><span class="muted small">' + esc(LL("Đánh dấu sự kiện khi đang ghi:", "Mark an event while recording:")) + "</span>" + kinds +
    '<input id="lesson-note" type="text" maxlength="40" placeholder="' + esc(LL("Ghi chú ngắn…", "Short note…")) + '">' +
    '<button class="btn lesson-ev" id="lesson-note-add" type="button">' + esc(LL("Thêm ghi chú", "Add note")) + "</button></div></div></div>" +
    '<div class="card mt" id="lesson-report"><div id="lesson-body"></div></div>';
  $("lesson-select").onchange = (e) => { LESSON.view = Number(e.target.value); lessonPaint(); };
  $("lesson-title").oninput = (e) => { const ls = lessonSelected(); if (ls) { ls.title = e.target.value; lessonSave(); const t = document.querySelector(".lesson-title"); if (t) t.firstChild.nodeValue = lessonTitle(ls); } };
  document.querySelectorAll(".lesson-tab").forEach((t) => { t.onclick = () => { LESSON.mode = t.dataset.mode; lessonPaint(); }; });
  $("lesson-wprev").onclick = () => { LESSON.weekOff--; lessonPaint(); };
  $("lesson-wnext").onclick = () => { if (LESSON.weekOff < 0) { LESSON.weekOff++; lessonPaint(); } };
  $("lesson-wnow").onclick = () => { LESSON.weekOff = 0; lessonPaint(); };
  $("lesson-print").onclick = lessonPrint;
  $("lesson-xjson").onclick = lessonExportJson;
  $("lesson-xpts").onclick = lessonExportPoints;
  $("lesson-xsum").onclick = lessonExportSummary;
  $("lesson-demo").onclick = lessonDemo;
  $("lesson-del").onclick = () => {
    const ls = lessonSelected(); if (!ls) return;
    confirmModal(LL("Xóa buổi học", "Delete lesson"), "<p>" + esc(LL("Xóa buổi học này khỏi trình duyệt? Hành động không hoàn tác.", "Delete this lesson from the browser? This cannot be undone.")) + "</p>", () => {
      const l = lessonLoad(), i = l.findIndex((x) => x.id === ls.id);
      if (i >= 0) l.splice(i, 1);
      if (LESSON.cur && LESSON.cur.id === ls.id) LESSON.cur = null;
      LESSON.view = null; lessonSave(); lessonPaint();
    }, { danger: true, label: LL("Xóa", "Delete") });
  };
  document.querySelectorAll(".lesson-ev[data-kind]").forEach((b) => { b.onclick = () => lessonAddEvent(b.dataset.kind); });
  $("lesson-note-add").onclick = () => { const v = $("lesson-note").value.trim(); if (!v) return; lessonAddEvent("note", v); $("lesson-note").value = ""; };
  lessonPaint();
}
setInterval(() => { if (S.view === "lesson" && LESSON.cur && !LESSON.cur.endedAt) lessonPaint(); }, 15000);

/* ============================== Sơ đồ lớp — quy tắc "liếc nhìn 3 giây" ==============================
   Mỗi em một ô, đủ to và có chữ để đọc từ xa. Màu: XANH tập trung tốt, VÀNG hơi mất tập trung, ĐỎ cần nhắc
   (buồn ngủ / cúi đầu quá lâu / quay đi lâu / chú ý thấp kéo dài), XÁM chưa có số liệu. Mỗi màu còn kèm KÝ HIỆU
   (✓ ! ▲ –) để người khó phân biệt màu vẫn đọc được. Ngưỡng bên dưới là quy tắc gợi ý do nhóm đặt, chưa kiểm chứng
   bằng dữ liệu có nhãn; số chỉ để giáo viên nhắc nhở kịp thời, không phải căn cứ kỷ luật. */
const SEAT_KEY = "fc_seatmap_v1";
const SEAT_LOW_SEI = 50, SEAT_OK_SEI = 70;      // dưới 50 kéo dài = đỏ; 50–70 = vàng
const SEAT_LOW_MS = 10000;                       // chú ý thấp phải kéo dài 10 giây mới thành đỏ (tránh nháy)
const SEAT_DOWN_LONG_MS = 60000;                 // cúi đầu liên tục quá 60 giây = đỏ (ghi bài ngắn hơn mức này vẫn bình thường)
const SEAT = { cfg: null, st: {}, edit: false, pick: null, rosterCache: null, last: 0 };
function seatCfg() {
  if (SEAT.cfg) return SEAT.cfg;
  let c = null;
  try { c = JSON.parse(localStorage.getItem(SEAT_KEY) || "null"); } catch (e) { c = null; }
  SEAT.cfg = c && typeof c === "object" ? c : {};
  if (!SEAT.cfg.cols) SEAT.cfg.cols = 6;
  if (!Array.isArray(SEAT.cfg.order)) SEAT.cfg.order = [];
  return SEAT.cfg;
}
function seatSave() { try { localStorage.setItem(SEAT_KEY, JSON.stringify(SEAT.cfg)); } catch (e) { /* bỏ qua */ } }
function seatGiven(name) {   // tên riêng: từ cuối của họ tên Việt; tên kiểu "HS Mẫu 01" thì giữ hai từ cuối cho khỏi chỉ còn "01"
  const p = String(name || "").trim().split(/\s+/);
  if (!p.length || !p[0]) return "";
  return /^\d+$/.test(p[p.length - 1]) && p.length > 1 ? p.slice(-2).join(" ") : p[p.length - 1];
}

/* Chuẩn hoá một em từ cả ba nguồn: bản web (kids/h), DEMO và LIVE (people) về cùng một dạng. */
function seatNormalize(name, id, h, p) {
  const sitIdx = (typeof SIT_IDX !== "undefined" && h && h.situation != null) ? SIT_IDX[h.situation] : null;
  const sit = p ? p.situation : sitIdx;
  const sei = p ? (typeof p.sei === "number" ? p.sei : null) : (h && typeof h.sei === "number" && isFinite(h.sei) ? h.sei : null);
  return {
    name, id, sei, sit,
    drowsy: !!(h && h.drowsy) || sit === "DOZING",
    away: !!(h && h.lookAwayLong) || sit === "TURNED",
    down: sit === "DOWN",
    drift: !!(h && h.driftLong), yawn: !!(h && h.yawnConfirmed), close: !!(h && h.tooClose),
  };
}
function seatStatus(n, now) {
  const k = n.id || n.name;
  const st = SEAT.st[k] || (SEAT.st[k] = { downSince: 0, lowSince: 0 });
  st.downSince = n.down ? (st.downSince || now) : 0;
  st.lowSince = (n.sei != null && n.sei < SEAT_LOW_SEI) ? (st.lowSince || now) : 0;
  if (n.drowsy) return { c: "red", why: "drowsy" };
  if (n.away) return { c: "red", why: "away" };
  if (n.down && now - st.downSince >= SEAT_DOWN_LONG_MS) return { c: "red", why: "down" };
  if (st.lowSince && now - st.lowSince >= SEAT_LOW_MS) return { c: "red", why: "low" };
  if (n.sei == null && !n.down && !n.yawn && !n.drift) return { c: "gray", why: "nodata" };
  if ((n.sei != null && n.sei < SEAT_OK_SEI) || n.down || n.yawn || n.drift || n.close || st.lowSince) return { c: "yellow", why: "mid" };
  return { c: "green", why: "ok" };
}
const SEAT_WHY = {
  drowsy: ["Buồn ngủ", "Drowsy"], away: ["Quay đi lâu", "Looking away"], down: ["Cúi đầu lâu", "Head down long"], low: ["Chú ý thấp", "Low engagement"],
  mid: ["Hơi mất tập trung", "Slightly distracted"], ok: ["Tập trung tốt", "Engaged"], nodata: ["Chưa có số liệu", "No data"],
};
const SEAT_ICON = { green: "✓", yellow: "!", red: "▲", gray: "–" };

async function seatRoster() {
  if (window.FC_MODE === "web") {
    return ((LIVE.state && LIVE.state.students) || []).map((r) => ({ id: String(r.id), name: r.name }));
  }
  if (SEAT.rosterCache) return SEAT.rosterCache;
  try {
    const r = await api("/api/students");
    const rows = Array.isArray(r) ? r : (r && (r.students || r.rows)) || [];
    SEAT.rosterCache = rows.map((x) => ({ id: String(x.student_id || x.id), name: x.name }));
  } catch (e) { SEAT.rosterCache = []; }
  return SEAT.rosterCache;
}
function seatPresent() {   // những em đang có số đo: [{name,id,sei,...}]
  const out = [];
  if (window.FC_MODE === "web") {
    const s = LIVE.state || {};
    if (!s.running) return out;
    (s.kids || []).forEach((k) => { if (k && k.name) out.push(seatNormalize(k.name, String(k.id != null ? k.id : k.name), k.h || null, null)); });
  } else {
    const st = S.live || {};
    if (!(st.camera && st.camera.running)) return out;
    (st.people || []).forEach((p) => { if (p && p.student_id) out.push(seatNormalize(p.name, String(p.student_id), null, p)); });
  }
  return out;
}
function seatMapCardHtml() {
  return '<div class="card seat-card" id="seatmap-card"><div class="seat-head"><h3>' + ico("users") + '<span id="seat-title"></span></h3>' +
    '<div class="seat-tools no-print"><label class="seat-cols"><span id="seat-cols-lab"></span><select id="seat-cols"></select></label>' +
    '<button class="btn" id="seat-edit" type="button"></button></div></div>' +
    '<div id="seat-summary" class="seat-summary" aria-live="polite"></div>' +
    '<div id="seat-grid" class="seat-grid"></div>' +
    '<div id="seat-legend" class="seat-legend muted small"></div></div>';
}
let seatBusy = false;
async function seatMapPaint(force) {
  const grid = $("seat-grid");
  if (!grid || seatBusy) return;
  const now = Date.now();
  if (!force && now - SEAT.last < 1000) return;
  SEAT.last = now; seatBusy = true;
  try {
    const cfg = seatCfg();
    const roster = await seatRoster();
    const present = seatPresent();
    const byId = {}; present.forEach((n) => { byId[n.id] = n; });
    const byName = {}; present.forEach((n) => { byName[n.name] = n; });
    // danh sách ô: toàn bộ danh sách lớp, cộng em đang đo nhưng chưa có trong danh sách
    const people = roster.map((r) => ({ id: r.id, name: r.name }));
    present.forEach((n) => { if (!people.find((x) => x.id === n.id || x.name === n.name)) people.push({ id: n.id, name: n.name }); });
    // thứ tự theo sơ đồ đã lưu; em mới nối vào cuối
    const rank = (x) => { const i = cfg.order.indexOf(x.id); return i < 0 ? 1e6 + people.indexOf(x) : i; };
    people.sort((a, b) => rank(a) - rank(b));
    const tiles = people.map((x) => {
      const n = byId[x.id] || byName[x.name] || null;
      const s = n ? seatStatus(n, now) : { c: "gray", why: "nodata" };
      return { x, n, s };
    });
    const cnt = { green: 0, yellow: 0, red: 0, gray: 0 }; tiles.forEach((t) => { cnt[t.s.c]++; });
    const reds = tiles.filter((t) => t.s.c === "red");
    $("seat-title").textContent = LL("Sơ đồ lớp — liếc 3 giây", "Class map — 3-second glance");
    $("seat-cols-lab").textContent = LL("Số cột", "Columns");
    const colSel = $("seat-cols");
    if (!colSel.options.length) { for (let c = 2; c <= 10; c++) colSel.add(new Option(String(c), String(c))); colSel.onchange = () => { cfg.cols = Number(colSel.value); seatSave(); seatMapPaint(true); }; }
    colSel.value = String(cfg.cols);
    const eb = $("seat-edit");
    eb.textContent = SEAT.edit ? LL("Xong", "Done") : LL("Xếp chỗ ngồi", "Arrange seats");
    eb.onclick = () => { SEAT.edit = !SEAT.edit; SEAT.pick = null; seatMapPaint(true); };
    $("seat-summary").innerHTML = !tiles.length
      ? '<span class="muted">' + esc(LL("Chưa có học sinh trong danh sách lớp. Thêm em ở màn Camera hoặc Học sinh.", "No students in the class list yet. Add them on the Camera or Students screen.")) + "</span>"
      : '<span class="seat-count green"><i></i><b>' + cnt.green + "</b> " + esc(LL("tốt", "good")) + "</span>" +
        '<span class="seat-count yellow"><i></i><b>' + cnt.yellow + "</b> " + esc(LL("hơi mất tập trung", "slightly distracted")) + "</span>" +
        '<span class="seat-count red"><i></i><b>' + cnt.red + "</b> " + esc(LL("cần nhắc", "need a nudge")) + "</span>" +
        '<span class="seat-count gray"><i></i><b>' + cnt.gray + "</b> " + esc(LL("chưa có số liệu", "no data")) + "</span>" +
        (reds.length ? '<span class="seat-nudge">' + esc(LL("Cần nhắc: ", "Nudge: ")) + reds.map((t) => "<b>" + esc(seatGiven(t.x.name) || t.x.name) + "</b>").join(", ") + "</span>" : "");
    grid.style.setProperty("--seat-cols", String(cfg.cols));
    grid.classList.toggle("editing", SEAT.edit);
    grid.innerHTML = tiles.map((t) => {
      const w = SEAT_WHY[t.s.why];
      const sei = t.n && t.n.sei != null ? Math.round(t.n.sei) : null;
      return '<button type="button" class="seat-tile ' + t.s.c + (SEAT.pick === t.x.id ? " pick" : "") + '" data-id="' + esc(t.x.id) + '" title="' + esc(t.x.name + " · " + LL(w[0], w[1])) + '">' +
        '<span class="seat-ico" aria-hidden="true">' + SEAT_ICON[t.s.c] + "</span>" +
        '<span class="seat-name">' + esc(seatGiven(t.x.name) || t.x.name) + "</span>" +
        '<span class="seat-why">' + esc(LL(w[0], w[1])) + "</span>" +
        (sei != null ? '<span class="seat-sei">' + sei + "</span>" : "") + "</button>";
    }).join("");
    grid.querySelectorAll(".seat-tile").forEach((b) => {
      b.onclick = () => {
        if (!SEAT.edit) return;
        const id = b.dataset.id;
        if (!SEAT.pick) { SEAT.pick = id; seatMapPaint(true); return; }
        if (SEAT.pick !== id) {   // đổi chỗ hai em trong thứ tự đã lưu
          const ids = tiles.map((t) => t.x.id), i = ids.indexOf(SEAT.pick), j = ids.indexOf(id);
          if (i >= 0 && j >= 0) { const tmp = ids[i]; ids[i] = ids[j]; ids[j] = tmp; cfg.order = ids; seatSave(); }
        }
        SEAT.pick = null; seatMapPaint(true);
      };
    });
    $("seat-legend").innerHTML =
      '<span class="lg green">✓ ' + esc(LL("Tập trung tốt", "Engaged")) + "</span>" +
      '<span class="lg yellow">! ' + esc(LL("Hơi mất tập trung", "Slightly distracted")) + "</span>" +
      '<span class="lg red">▲ ' + esc(LL("Cần nhắc: buồn ngủ, cúi đầu lâu, quay đi lâu", "Nudge: drowsy, head down long, looking away")) + "</span>" +
      '<span class="lg gray">– ' + esc(LL("Chưa có số liệu", "No data")) + "</span>" +
      (SEAT.edit ? '<div class="seat-hint">' + esc(LL("Chạm một em rồi chạm em khác để đổi chỗ.", "Tap one student, then another, to swap seats.")) + "</div>"
        : '<div class="seat-hint">' + esc(LL("Ngưỡng: SEI < 50 trong 10 giây hoặc cúi đầu liên tục > 60 giây là đỏ. Chỉ để nhắc nhở kịp thời, không phải căn cứ kỷ luật.",
          "Thresholds: Student Engagement Index (SEI) < 50 for 10 s, or head down continuously > 60 s, turns red. For timely reminders only, never grounds for discipline.")) + "</div>");
  } finally { seatBusy = false; }
}

/* ============================== Phiếu nhận xét gửi phụ huynh (tuần) ==============================
   Mỗi em một trang A4, lời lẽ xây dựng: nêu điểm tích cực trước; chỉ nêu "điều thầy/cô nhận thấy" khi số liệu thật sự cho
   thấy, bằng giọng nhẹ nhàng ("có dấu hiệu mỏi hoặc giảm tập trung"), KHÔNG dùng nhãn tiêu cực như "lơ là", "kém".
   Chỉ dùng số liệu có trong db (SEI từng phiên, giờ phiên, số ngày). Không suy đoán nguyên nhân y khoa: câu về giấc ngủ/mắt
   chỉ là lời khuyên chung. Giáo viên phải đọc lại, có thể thêm lời nhắn, và phải xác nhận phụ huynh đã đồng ý trước khi in. */
const PERIOD_KEY = "fc_periods_v1";
const PERIOD_DEFAULT = "Tiết 1, 07:00, 07:45\nTiết 2, 07:50, 08:35\nTiết 3, 08:55, 09:40\nTiết 4, 09:45, 10:30\nTiết 5, 10:35, 11:20\nTiết 6, 13:00, 13:45\nTiết 7, 13:50, 14:35\nTiết 8, 14:40, 15:25";
const SLIP = { data: null, prev: null, opts: null };
function periodsLoad() {
  let t = PERIOD_DEFAULT;
  try { t = localStorage.getItem(PERIOD_KEY) || PERIOD_DEFAULT; } catch (e) { /* mặc định */ }
  return t.split("\n").map((l) => l.split(",").map((x) => x.trim())).filter((a) => a.length >= 3 && /^\d{1,2}:\d{2}$/.test(a[1]) && /^\d{1,2}:\d{2}$/.test(a[2]))
    .map((a) => { const m = (s) => { const q = s.split(":"); return Number(q[0]) * 60 + Number(q[1]); }; return { name: a[0], from: m(a[1]), to: m(a[2]) }; });
}
function slipWhen(session, lang) {
  const mid = new Date((session.started_at_wall_ms + (session.ended_at_wall_ms || session.started_at_wall_ms)) / 2);
  const mins = mid.getHours() * 60 + mid.getMinutes();
  const per = periodsLoad().find((p) => mins >= p.from - 3 && mins <= p.to + 3);
  const day = new Date(session.started_at_wall_ms);
  const dd = (lang === "en" ? ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"] : ["CN", "Thứ 2", "Thứ 3", "Thứ 4", "Thứ 5", "Thứ 6", "Thứ 7"])[day.getDay()] + " " + lessonDate(session.started_at_wall_ms).slice(0, 5);
  if (per) { const nm = lang === "en" ? per.name.replace(/^Tiết\s*/i, "Period ") : per.name; return nm + " (" + dd + ")"; }
  return (lang === "en" ? "around " : "khoảng ") + lessonFmtTime(session.started_at_wall_ms) + " (" + dd + ")";
}
function slipFacts(row, sessions, prevRows) {
  const mine = sessions.filter((s) => String(s.student_id) === String(row.student_id) && typeof s.sei_mean === "number" && isFinite(s.sei_mean));
  const best = mine.length ? mine.reduce((a, b) => (b.sei_mean > a.sei_mean ? b : a)) : null;
  const worst = mine.length ? mine.reduce((a, b) => (b.sei_mean < a.sei_mean ? b : a)) : null;
  const prev = (prevRows || []).find((r) => String(r.student_id) === String(row.student_id));
  return { row, mine, best, worst, avg: row.sei_mean, minutes: Math.round(row.valid_ms / 60000),
    diff: prev && typeof prev.sei_mean === "number" && typeof row.sei_mean === "number" ? row.sei_mean - prev.sei_mean : null };
}
/* Lời nhận xét ở một ngôn ngữ cụ thể (lang = "vi" | "en"), độc lập với ngôn ngữ giao diện. */
function slipTexts(f, lang) {
  const T = (vi, en) => (lang === "en" ? en : vi), r0 = (v) => Math.round(v);
  const good = [], obs = [], tips = [];
  good.push(T(`Em tham gia ${f.row.sessions} buổi học trong tuần (${f.row.days} ngày), tổng thời gian ghi nhận ${f.minutes} phút.`,
    `Your child took part in ${f.row.sessions} lesson(s) over ${f.row.days} day(s), with ${f.minutes} minutes recorded this week.`));
  if (typeof f.avg === "number") {
    if (f.avg >= 70) good.push(T(`Mức tập trung của em trong tuần tốt (chỉ số tập trung SEI trung bình ${r0(f.avg)}/100).`, `Your child's engagement this week was good (average Student Engagement Index, SEI, ${r0(f.avg)}/100).`));
    else if (f.avg >= 55) good.push(T(`Em có nhiều quãng tập trung ổn định trong tuần (SEI trung bình ${r0(f.avg)}/100).`, `Your child had many steady, focused stretches this week (average SEI ${r0(f.avg)}/100).`));
    else good.push(T("Em tham gia học tập đều đặn; thầy/cô sẽ cùng em tìm cách để việc tập trung dễ dàng hơn.", "Your child took part steadily, and we will work together to make focusing easier."));
  }
  if (f.diff != null && f.diff >= 5) good.push(T(`Có tiến bộ so với tuần trước (tăng ${r0(f.diff)} điểm).`, `Progress compared with last week (up ${r0(f.diff)} points).`));
  else if (f.diff != null && Math.abs(f.diff) < 5 && f.avg >= 70) good.push(T("Em giữ vững phong độ so với tuần trước.", "Your child kept up the good level from last week."));
  if (f.mine.length >= 2 && f.best && f.best.sei_mean >= 65) good.push(T(`Thời điểm em tập trung tốt nhất là ${slipWhen(f.best, "vi")} (${r0(f.best.sei_mean)}/100).`, `Your child's most focused time was ${slipWhen(f.best, "en")} (${r0(f.best.sei_mean)}/100).`));
  const dip = f.worst && f.mine.length >= 2 && f.worst.sei_mean < 55 && (f.avg - f.worst.sei_mean) >= 8;
  if (dip) obs.push(T(`Vào ${slipWhen(f.worst, "vi")}, em có dấu hiệu mỏi mắt hoặc giảm tập trung (${r0(f.worst.sei_mean)}/100). Điều này thường gặp khi em mệt hoặc ngủ chưa đủ.`,
    `Around ${slipWhen(f.worst, "en")}, your child showed signs of eye strain or reduced focus (${r0(f.worst.sei_mean)}/100). This is common when a child is tired or has not slept enough.`));
  if (typeof f.avg === "number" && f.avg < 55) obs.push(T(`Chỉ số tập trung trung bình tuần này chưa cao (${r0(f.avg)}/100); thầy/cô sẽ quan tâm và trao đổi thêm với em.`, `The average engagement this week was not yet high (${r0(f.avg)}/100); we will pay extra attention and talk with your child.`));
  if (!obs.length) obs.push(T("Tuần này chưa có điều gì đáng lưu ý thêm.", "Nothing further to note this week."));
  if (dip || (typeof f.avg === "number" && f.avg < 55)) {
    tips.push(T("Gia đình có thể giúp em ngủ đủ giấc và đi ngủ đúng giờ.", "At home, please help your child sleep enough and keep regular bedtimes."));
    tips.push(T("Khi học ở nhà, nên cho em nghỉ mắt 20 giây sau mỗi 20 phút nhìn gần (quy tắc 20-20-20).", "When studying at home, give your child a 20-second eye rest after every 20 minutes of close work (the 20-20-20 rule)."));
    tips.push(T("Nếu em thường xuyên mệt hoặc mỏi mắt, nên trao đổi với giáo viên và chuyên gia y tế/nhãn khoa.", "If your child is often tired or has eye strain, please talk with the teacher and a health or eye-care professional."));
  } else tips.push(T("Gia đình có thể khen ngợi sự cố gắng của em và giữ nếp học hiện tại.", "Please praise your child's effort and keep the current study routine."));
  return { good, obs, tips };
}
function slipBlock(f, lang, o) {
  const T = (vi, en) => (lang === "en" ? en : vi), t = slipTexts(f, lang), li = (a) => a.map((x) => "<li>" + esc(x) + "</li>").join("");
  return '<div class="slip-col">' +
    '<div class="slip-top"><div><div class="slip-brand">FocusChange AI</div><h2 class="slip-title">' + esc(T("Phiếu nhận xét tuần", "Weekly Learning Note")) + "</h2></div>" +
    '<div class="slip-meta">' + esc(o.className) + "<br>" + esc(o.week) + "</div></div>" +
    '<div class="slip-student">' + esc(T("Học sinh", "Student")) + ": <b>" + esc(f.row.name || f.row.student_id) + "</b></div>" +
    '<h3 class="slip-h good">' + esc(T("Điểm tích cực", "Strengths")) + "</h3><ul>" + li(t.good) + "</ul>" +
    '<h3 class="slip-h obs">' + esc(T("Điều thầy/cô nhận thấy", "What we noticed")) + "</h3><ul>" + li(t.obs) + "</ul>" +
    '<h3 class="slip-h tip">' + esc(T("Gợi ý cho gia đình", "Suggestions for home")) + "</h3><ul>" + li(t.tips) + "</ul>" +
    (o.notes[f.row.student_id] ? '<h3 class="slip-h">' + esc(T("Lời nhắn của giáo viên", "Teacher's note")) + '</h3><p class="slip-note">' + esc(o.notes[f.row.student_id]) + "</p>" : "") +
    '<div class="slip-foot">' + esc(T(
      "Chỉ số do máy tính tổng hợp từ camera lớp học để hỗ trợ giáo viên; đây không phải đánh giá năng lực, hạnh kiểm hay chẩn đoán y khoa. Hệ thống xử lý ngay tại máy của lớp và không lưu hình ảnh.",
      "These indicators are compiled by computer from the classroom camera to support the teacher; they are not an assessment of ability or conduct, nor a medical diagnosis. The system runs on the classroom computer and stores no images.")) + "</div>" +
    '<div class="slip-sign"><div>' + esc(T("Giáo viên", "Teacher")) + (o.teacher ? ": " + esc(o.teacher) : "") + '<span></span></div><div>' + esc(T("Phụ huynh ký nhận", "Parent's signature")) + "<span></span></div></div></div>";
}
function slipPage(f, o) {
  const langs = o.lang === "bi" ? ["vi", "en"] : [o.lang];
  return '<section class="slip' + (langs.length > 1 ? " bi" : "") + '">' + langs.map((l) => slipBlock(f, l, o)).join("") + "</section>";
}
function slipYmd(d) { const p = (n) => (n < 10 ? "0" : "") + n; return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate()); }
async function slipWeek(dateStr) {
  const R = window.FC_REPORT, days = R.weekDates(dateStr);
  const parts = await Promise.all(days.map((d) => api("/api/reports?scope=session&date=" + d)));
  const seen = new Set(), sessions = [];
  parts.forEach((p) => (p.rows || []).forEach((r) => { if (r.session_row != null) { if (seen.has(r.session_row)) return; seen.add(r.session_row); } sessions.push(r); }));
  return { days, sessions, rows: R.weekByStudent(sessions) };
}
function slipPanelHtml() {
  const lang = window.FC_I18N ? FC_I18N.lang : "vi";
  return '<div class="card mt" id="slip-card"><div class="slip-bar no-print"><h3>' + ico("report") + esc(LL("Phiếu nhận xét gửi phụ huynh", "Parent note")) + "</h3>" +
    '<button class="btn" id="slip-close" type="button">' + esc(LL("Đóng", "Close")) + "</button></div>" +
    '<p class="muted small no-print">' + esc(LL("Mỗi em một trang A4, lời lẽ xây dựng, dựa trên số liệu của tuần chứa ngày đang chọn. Thầy/cô hãy đọc lại từng phiếu trước khi gửi.",
      "One A4 page per student, constructive wording, based on the week containing the selected date. Please read each note before sending.")) + "</p>" +
    '<div class="lesson-bar no-print"><label class="lesson-field">' + esc(LL("Ngôn ngữ phiếu", "Note language")) + '<select id="slip-lang"><option value="vi"' + (lang === "vi" ? " selected" : "") + ">Tiếng Việt</option><option value=\"en\"" + (lang === "en" ? " selected" : "") + ">English</option><option value=\"bi\">" + esc(LL("Song ngữ Việt – Anh", "Bilingual Vietnamese – English")) + "</option></select></label>" +
    '<label class="lesson-field">' + esc(LL("Lớp", "Class")) + '<input id="slip-class" type="text" maxlength="40" value="' + esc(LL("Lớp học", "Class")) + '"></label>' +
    '<label class="lesson-field">' + esc(LL("Giáo viên", "Teacher")) + '<input id="slip-teacher" type="text" maxlength="40"></label></div>' +
    '<details class="no-print"><summary>' + esc(LL("Giờ các tiết (chỉnh theo trường)", "Period times (adjust to your school)")) + '</summary><textarea id="slip-periods" rows="6" spellcheck="false"></textarea>' +
    '<div class="muted small">' + esc(LL("Mỗi dòng: tên tiết, giờ bắt đầu, giờ kết thúc. Dùng để ghi \"Tiết 4\" thay vì giờ cụ thể.", "One per line: period name, start time, end time. Used to write \"Period 4\" instead of a clock time.")) + "</div></details>" +
    '<div id="slip-list" class="mt no-print"></div>' +
    '<label class="slip-consent no-print"><input type="checkbox" id="slip-consent"> ' + esc(LL("Tôi xác nhận phụ huynh đã đồng ý nhận các chỉ số này và tôi đã đọc lại từng phiếu.", "I confirm that parents agreed to receive these indicators and that I have read each note.")) + "</label>" +
    '<div class="lesson-bar no-print"><button class="btn" id="slip-preview" type="button">' + esc(LL("Xem trước", "Preview")) + '</button><button class="btn btn-primary" id="slip-print" type="button" disabled>' + ico("report") + " " + esc(LL("In / Lưu PDF", "Print / Save PDF")) + "</button></div>" +
    '<div id="slip-pages" class="slip-pages"></div></div>';
}
async function parentSlipOpen() {
  const host = $("slip-host"); if (!host) return;
  host.innerHTML = slipPanelHtml();
  $("slip-close").onclick = () => { host.innerHTML = ""; };
  let periods = PERIOD_DEFAULT; try { periods = localStorage.getItem(PERIOD_KEY) || PERIOD_DEFAULT; } catch (e) { /* mặc định */ }
  $("slip-periods").value = periods;
  $("slip-periods").onchange = (e) => { try { localStorage.setItem(PERIOD_KEY, e.target.value); } catch (x) { /* bỏ qua */ } };
  $("slip-list").innerHTML = '<span class="muted small">' + esc(LL("Đang tải số liệu tuần…", "Loading the week's data…")) + "</span>";
  host.scrollIntoView({ behavior: "smooth", block: "start" });
  try {
    const cur = await slipWeek(S.reportDate);
    const d0 = new Date(cur.days[0] + "T00:00:00"); d0.setDate(d0.getDate() - 7);
    let prev = { rows: [] }; try { prev = await slipWeek(slipYmd(d0)); } catch (e) { /* không có tuần trước thì bỏ qua so sánh */ }
    SLIP.data = cur; SLIP.prev = prev;
    if (!cur.rows.length) { $("slip-list").innerHTML = '<div class="chart-empty">' + esc(LL("Tuần này chưa có phiên đo nào để lập phiếu.", "No measurement sessions this week to build notes from.")) + "</div>"; return; }
    $("slip-list").innerHTML = '<div class="tbl-wrap"><table class="tbl"><thead><tr><th></th><th>' + esc(LL("Học sinh", "Student")) + "</th><th>SEI</th><th>" + esc(LL("Phiên", "Sessions")) + "</th><th>" + esc(LL("Lời nhắn riêng (không bắt buộc)", "Personal note (optional)")) + "</th></tr></thead><tbody>" +
      cur.rows.map((r) => '<tr><td><input type="checkbox" class="slip-pick" data-id="' + esc(r.student_id) + '" checked></td><td>' + esc(r.name || r.student_id) + "</td><td>" + (r.sei_mean == null ? "--" : Math.round(r.sei_mean)) + "</td><td>" + r.sessions +
        '</td><td><input type="text" class="slip-note-in" data-id="' + esc(r.student_id) + '" maxlength="140" placeholder="' + esc(LL("Ví dụ: Em phát biểu rất tốt ở môn Toán.", "e.g. Spoke up very well in Math.")) + '"></td></tr>').join("") + "</tbody></table></div>";
    const refresh = () => { $("slip-print").disabled = !$("slip-consent").checked || !document.querySelector(".slip-pick:checked"); };
    $("slip-consent").onchange = refresh; document.querySelectorAll(".slip-pick").forEach((c) => { c.onchange = refresh; });
    $("slip-lang").onchange = () => {   // tên lớp mặc định đi theo ngôn ngữ phiếu (nếu giáo viên chưa tự sửa)
      const c = $("slip-class"), v = c.value.trim(), l = $("slip-lang").value;
      if (v === "Lớp học" || v === "Class" || v === "Lớp học / Class") c.value = l === "en" ? "Class" : l === "bi" ? "Lớp học / Class" : "Lớp học";
      slipRender();
    };
    $("slip-preview").onclick = slipRender;
    $("slip-print").onclick = () => {
      slipRender();
      document.body.classList.add("print-slips");
      const done = () => { document.body.classList.remove("print-slips"); window.removeEventListener("afterprint", done); };
      window.addEventListener("afterprint", done); window.print(); setTimeout(done, 1500);
    };
    slipRender();
  } catch (e) { $("slip-list").innerHTML = '<div class="chart-empty">' + esc(LL("Không tải được số liệu: ", "Could not load data: ") + e.message) + "</div>"; }
}
function slipRender() {
  if (!SLIP.data) return;
  const picked = new Set([...document.querySelectorAll(".slip-pick:checked")].map((c) => c.dataset.id));
  const notes = {}; document.querySelectorAll(".slip-note-in").forEach((i) => { if (i.value.trim()) notes[i.dataset.id] = i.value.trim(); });
  const lang = $("slip-lang").value, d = SLIP.data.days;
  const o = { lang, notes, className: $("slip-class").value.trim() || LL("Lớp học", "Class"), teacher: $("slip-teacher").value.trim(), week: (lang === "en" ? "Week " : "Tuần ") + lessonDate(new Date(d[0] + "T00:00:00").getTime()) + " – " + lessonDate(new Date(d[6] + "T00:00:00").getTime()) };
  try { localStorage.setItem(PERIOD_KEY, $("slip-periods").value); } catch (e) { /* bỏ qua */ }
  $("slip-pages").innerHTML = SLIP.data.rows.filter((r) => picked.has(String(r.student_id))).map((r) => slipPage(slipFacts(r, SLIP.data.sessions, SLIP.prev ? SLIP.prev.rows : []), o)).join("");
}

const RENDER = {
  overview: renderOverview, camera: renderCamera, students: renderStudents, analysis: renderAnalysis,
  alerts: renderAlerts, lesson: renderLesson, reports: renderReports, backup: renderBackup, settings: renderSettings,
};

function route() {
  const v = (location.hash || "#overview").slice(1);
  S.view = RENDER[v] ? v : "overview";
  buildNav();
  RENDER[S.view]();
  // Màn mới bắt đầu từ đầu trang: ô camera nằm trên cùng, để nguyên vết cuộn của màn cũ thì khung đo
  // (và nút Bắt đầu đo của nó) bị đẩy lên ngoài màn hình.
  const view = $("view");
  if (view) view.scrollTop = 0;
  // Ô giữ chỗ của màn mới nằm ở chỗ khác: căn lại khung đo ngay, đừng chờ nhịp 250 ms.
  LIVE.lastRect = "";
  livePlaceSoon();
}

window.addEventListener("fc-lang", () => { try { route(); } catch (e) { /* chưa khởi động xong */ } });

async function init() {
  // Bản web hosted: hosted-bridge.js thăm dò server cục bộ trước khi quyết định chế độ
  // LIVE (gọi về 127.0.0.1:8730) hay DEMO (mock trong bộ nhớ). Bản cục bộ không có
  // FC_READY nên bỏ qua nhịp chờ này, chạy như cũ.
  if (window.FC_READY) { try { await window.FC_READY; } catch (e) { /* bridge lỗi: chạy như bản cục bộ */ } }
  buildShell();
  // Khung đo nạp song song với phần còn lại của dashboard: model nặng nên không chặn màn đầu tiên.
  liveBoot();
  await refreshSettings();
  document.documentElement.dataset.theme = S.settings.theme === "dark" ? "dark" : "light";
  await tickFast();
  await loadClasses();
  route();
  window.addEventListener("hashchange", route);
  tickSlow();
  setInterval(tickFast, 5000);    // số liệu sống: tổng quan, camera, huy hiệu cảnh báo
  setInterval(tickSlow, 30000);   // biểu đồ 30 phút, trạng thái hệ thống, nhật ký
  // ?autostart=1: cầu nối DEMO chuyển hướng sang LIVE khi người dùng bấm Bắt đầu
  // mà dashboard cục bộ đang chạy — tới nơi thì bật camera thật ngay, khỏi bấm lần nữa.
  if (window.FC_MODE === "live" && new URLSearchParams(location.search).get("autostart") === "1") {
    startCamera();
  }
}

init();
