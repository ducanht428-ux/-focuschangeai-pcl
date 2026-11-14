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
  const res = await fetch(path, opt);
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
    const res = await fetch(url);
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

function toast(msg, kind) {
  const el = document.createElement("div");
  el.className = "toast" + (kind ? " " + kind : "");
  el.textContent = msg;
  $("toast-root").appendChild(el);
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
};
function ico(name) {
  return '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' + (ICON_PATHS[name] || "") + "</svg>";
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
  soundOn: localStorage.getItem("fc_sound") !== "0",
  lastUnseen: null,
};

const TIER_LABEL = {standard: "Standard", pro: "Pro", promax: "Pro Max", ultra: "Ultra"};
const GATE_LABEL = {
  csv_export: "Xuất CSV/Excel", auto_backup: "Sao lưu tự động", personal_baseline: "Baseline cá nhân",
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

function allows(feature) {
  const gates = (S.system && S.system.gates) || {
    csv_export: "pro", auto_backup: "pro", personal_baseline: "pro", multi_class: "promax",
    parent_report: "promax", yolo_context: "promax", predict: "ultra", assistant: "ultra", parent_portal: "ultra"};
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

/* Biểu đồ 30 phút của Tổng quan: cửa sổ cố định now-30' .. now, mốc 5 phút. */
function trendChart(rows) {
  const now = Date.now(), start = now - 30 * 60000;
  const byBucket = {};
  for (const r of rows) byBucket[r.bucket_ms] = r;
  const vals = [], labels = [];
  for (let m = 30; m >= 0; m--) {
    const bucket = Math.floor((now - m * 60000) / 60000) * 60000;
    const r = byBucket[bucket];
    vals.push(r && r.mean_sei != null ? r.mean_sei : null);
    labels.push(m % 5 === 0 ? fmtTime(bucket) : "");
  }
  if (vals.every((v) => v == null)) {
    return '<div class="chart-empty">Chưa có dữ liệu 30 phút gần nhất.<br>Bật camera để đo, hoặc nạp dữ liệu mẫu ở màn Sao lưu.</div>';
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
  ["reports", "Báo cáo", "report"],
  ["backup", "Sao lưu", "database"],
  ["settings", "Cài đặt", "settings"],
];

function buildNav() {
  const unseen = S.overview ? S.overview.alerts_unseen : 0;
  $("nav").innerHTML = VIEWS.map(([id, label, icon]) =>
    '<button class="nav-item' + (S.view === id ? " active" : "") + '" data-view="' + id + '">' +
    ico(icon) + "<span>" + label + "</span>" +
    (id === "alerts" ? '<span class="nav-badge" id="nav-alert-badge"' + (unseen ? "" : " hidden") + ">" + unseen + "</span>" : "") +
    "</button>").join("");
  for (const btn of $("nav").querySelectorAll(".nav-item")) {
    btn.onclick = () => { location.hash = "#" + btn.dataset.view; };
  }
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
  $("chip-lan").innerHTML = ico("wifioff") + "<span>Mạng lớp: Chưa kết nối</span>";
  $("modal-root").addEventListener("click", (e) => { if (e.target === $("modal-root")) closeModal(); });
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

function updateChips() {
  const ready = S.system && S.system.database && S.system.database.ready;
  $("chip-db").innerHTML = ico("database") +
    '<span><i class="dot ' + (ready ? "dot-green" : "dot-red") + '"></i>SQLite: ' +
    (ready ? "Sẵn sàng" : "Lỗi") + "</span>";
  const banner = $("demo-banner");
  const demo = S.overview && S.overview.demo;
  banner.hidden = !demo;
  if (demo) {
    banner.innerHTML = ico("warn") +
      "<span><b>DỮ LIỆU MẪU (DEMO)</b> đang được hiển thị để nghiệm thu giao diện — không phải số đo thật.</span>" +
      '<span class="spacer" style="flex:1"></span><button class="btn btn-sm" id="demo-banner-purge">Xóa dữ liệu mẫu</button>';
    $("demo-banner-purge").onclick = purgeDemo;
  }
  const badge = $("nav-alert-badge");
  if (badge && S.overview) {
    badge.textContent = S.overview.alerts_unseen;
    badge.hidden = !S.overview.alerts_unseen;
  }
}

/* ============================== polling ============================== */
async function tickFast() {
  try {
    const [ov, live] = await Promise.all([
      api("/api/overview?date=" + encodeURIComponent(S.date)),
      api("/api/live")]);
    S.overview = ov; S.live = live;
    // Âm báo khi có cảnh báo CHƯA XEM mới (đặc tả 5.7). Lần đầu chỉ ghi mốc, không kêu.
    if (S.lastUnseen != null && ov.alerts_unseen > S.lastUnseen) {
      beep();
      toast("Có cảnh báo mới (" + (ov.alerts_unseen - S.lastUnseen) + ")", "err");
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
      const t = $("ov-trend"); if (t) t.innerHTML = trendChart(S.trend);
      const sy = $("ov-system"); if (sy) sy.innerHTML = systemRows();
      const ac = $("ov-activity"); if (ac) ac.innerHTML = activityRows();
    }
    if (S.view === "settings") { const a = $("audit-body"); if (a) a.innerHTML = auditRows(); }
  } catch (e) { /* bỏ qua nhịp này */ }
}

/* ============================== mảnh ghép Tổng quan ============================== */
function avgRow(icon, label, value, digits, scale, color, unit) {
  const pct = value == null ? 0 : Math.max(0, Math.min(100, value / scale * 100));
  return '<div class="avg-row">' + ico(icon) +
    '<div class="avg-label">' + label + ' — <b>' + num(value, digits) + (unit || "") + "</b></div>" +
    '<div class="avg-bar"><div class="avg-fill" style="width:' + pct.toFixed(0) + "%;background:" + color + '"></div></div></div>';
}

function systemRows() {
  const st = S.system;
  if (!st) return '<div class="muted small">Đang tải trạng thái…</div>';
  const row = (label, ok, text, bad) =>
    '<div class="sys-row"><span>' + label + '</span><span class="' +
    (bad ? "status-bad" : ok ? "status-ok" : "status-off") + '"><i class="dot ' +
    (bad ? "dot-red" : ok ? "dot-green" : "dot-grey") + '"></i>' + text + "</span></div>";
  const d = st.deps || {};
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
    if (!img.getAttribute("src")) img.src = "/api/camera/stream";
    empty.hidden = true;
    if (fps) { fps.hidden = false; fps.textContent = num(S.live.fps, 1) + " FPS"; }
  } else {
    img.removeAttribute("src");
    // Không có src thì Chrome vẫn vẽ alt + icon ảnh hỏng cho img đã đặt cỡ bằng CSS,
    // nên phải ẩn hẳn thẻ img khi camera dừng (luật [hidden] trong app.css lo phần còn lại).
    img.hidden = true;
    empty.hidden = false;
    if (fps) fps.hidden = true;
    let msg = "Camera chưa bật. Bấm <b>Bắt đầu</b> để đo bằng webcam máy này.";
    if (cam && cam.error) msg = "<b>Camera gặp lỗi:</b> " + esc(cam.error);
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
    empty.innerHTML = "<div>" + ico("camera") + "<div>" + msg + "</div></div>";
  }
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
    '<div class="grid grid-stats">' +
    statCard("blue", "users", "ov-students", "--", "Học sinh", "ov-students-sub", "trong lớp") +
    statCard("green", "camera", "ov-camera", "--", "Camera", "ov-camera-sub", "") +
    statCard("orange", "activity", "ov-ai", "--", "Trạng thái hoạt động", "ov-ai-sub", "MediaPipe + AI") +
    statCard("red", "bell", "ov-alerts", "--", "Cảnh báo hôm nay", "ov-alerts-sub", "") +
    "</div>" +
    '<div class="grid grid-overview">' +
    '<div class="card"><h3>' + ico("camera") + 'Camera thời gian thực</h3>' +
    '<div class="cam-wrap" id="ov-wrap"><img id="ov-stream" alt="Luồng camera" hidden>' +
    '<div class="cam-empty" id="ov-empty"></div><span class="fps-chip" id="ov-fps" hidden></span></div>' +
    cameraButtons("ov") + '<div class="avg-note" id="ov-cam-note"></div></div>' +
    '<div class="card"><h3>' + ico("target") + 'Chỉ số trung bình lớp</h3><div id="ov-avgs"></div>' +
    '<div class="avg-note" id="ov-avg-note"></div></div>' +
    '<div class="card"><h3>' + ico("chart") + 'Xu hướng chú ý 30 phút</h3><div id="ov-trend"></div>' +
    '<div class="avg-note">Mức chú ý trung bình lớp (SEI 0–100), mốc 5 phút.</div></div>' +
    "</div>" +
    '<div class="grid grid-overview-2">' +
    '<div class="card"><h3>' + ico("clock") + 'Hoạt động gần đây</h3><div id="ov-activity"></div></div>' +
    '<div class="card"><h3>' + ico("info") + 'Trạng thái hệ thống</h3><div id="ov-system"></div></div>' +
    "</div>";
  $("ov-date").onchange = (e) => { S.date = e.target.value || todayStr(); tickFast(); };
  wireCameraButtons("ov", "ov-wrap");
  updateOverviewParts();
  $("ov-trend").innerHTML = trendChart(S.trend);
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
  $("ov-camera").textContent = camOn ? "1/1" : "0/1";
  $("ov-camera-sub").textContent = ov.camera.note;
  const aiText = {active: "Đang chạy", ready: "Sẵn sàng", missing: "Chưa đủ"}[ov.ai.state] || ov.ai.state;
  $("ov-ai").textContent = aiText;
  $("ov-ai-sub").textContent = ov.ai.note;
  $("ov-alerts").textContent = ov.alerts_today;
  $("ov-alerts-sub").textContent = ov.alerts_unseen + " chưa xem";
  $("ov-alerts").closest(".stat").classList.toggle("danger", ov.alerts_unseen > 0);
  const a = ov.averages || {};
  $("ov-avgs").innerHTML =
    avgRow("eye", "EAR (mở mắt)", a.ear, 2, 0.5, "var(--blue)") +
    avgRow("mouth", "MAR (miệng)", a.mar, 2, 1.0, "var(--green)") +
    avgRow("angle", "Góc đầu", a.angle, 1, 45, "var(--orange)", "°") +
    avgRow("target", "Mức chú ý", a.sei, 0, 100, "var(--purple)", "%");
  $("ov-avg-note").textContent = "Nguồn: " + (a.label || "chưa có dữ liệu") +
    (a.n ? " · " + a.n + " em" : "") + ". Số chỉ để hỗ trợ, không dùng làm căn cứ kỷ luật duy nhất.";
  $("ov-cam-note").textContent = ov.camera.note;
  syncStream("ov-stream", "ov-empty", "ov-fps");
}

/* ============================== màn 2: Camera ============================== */
function renderCamera() {
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
    '<div><div class="card"><h3>' + ico("settings") + 'Cài đặt camera</h3>' +
    '<div class="field"><label>Nguồn camera</label><select id="cam-source">' +
    '<option value="0" selected>Webcam máy này (index 0)</option>' +
    '<option value="phone" disabled>Điện thoại ghép nối — giai đoạn 3</option></select></div>' +
    '<div class="field"><label>Độ phân giải xử lý</label>' + sel("cam-width", [[640, "640×480"], [480, "480×360"], [320, "320×240"]], cc.process_width) + "</div>" +
    '<div class="field"><label>Số mặt đo cùng lúc</label>' + sel("cam-faces", [[1, "1"], [2, "2"], [3, "3"], [4, "4"], [10, "10 (tối đa)"]], cc.max_active_faces) + "</div>" +
    '<div class="field"><label>Tần suất quét (khung/giây)</label>' + sel("cam-fps-set", [[4, "4"], [8, "8"], [12, "12"], [15, "15"]], cc.detect_fps) + "</div>" +
    '<div class="field"><label>Độ nhạy</label>' + sel("cam-sens", [[0, "Thấp"], [1, "Vừa"], [2, "Cao"], [3, "Rất cao"]], cc.sensitivity) + "</div>" +
    '<button class="btn btn-primary" id="cam-save">' + ico("save") + " Lưu cài đặt camera</button>" +
    '<div class="lock-note mt">Thông số lưu vào config.yaml, áp dụng từ lần bật camera kế tiếp. ' +
    "Hiệu chuẩn khoảng cách và gắn tên thủ công (phím M) hiện nằm ở cửa sổ camera lõi (main.py).</div></div>" +
    '<div class="card mt"><h3>' + ico("eye") + 'Tuỳ chọn hiển thị</h3>' +
    switchRow("sw-boxes", "Hộp người / hộp mặt (Y)", opt.boxes) +
    switchRow("sw-overlay", "Số liệu đè khung (O)", opt.overlay) +
    switchRow("sw-brighten", "Tăng sáng (G)", opt.brighten) +
    switchRow("sw-people", "Hiện thẻ từng em (H)", !S.hidePeople) +
    "</div>" +
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
  updateCameraParts();
}

function switchRow(id, label, checked) {
  return '<div class="sys-row"><span>' + label + '</span><label class="switch">' +
    '<input type="checkbox" id="' + id + '"' + (checked ? " checked" : "") + "><i></i></label></div>";
}

function updateCameraParts() {
  if (!$("cam-people")) return;
  syncStream("cam-stream", "cam-empty", "cam-fps");
  const cam = (S.live && S.live.camera) || {};
  $("cam-status-note").textContent = cam.running
    ? "Đang chạy · " + cam.frames + " khung đã xử lý · " + num(cam.processing_ms, 0) + " ms/khung · " + cam.people + " người trong khung."
    : (cam.error ? "Lỗi: " + cam.error : "Camera chưa bật.");
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
      (p.alert ? '<div class="person-alert">' + ico("warn") + " " + esc(p.alert) + "</div>" : "") +
      "</div>";
  }).join("");
}

async function saveCameraSettings() {
  const body = {camera: {
    process_width: parseInt($("cam-width").value, 10),
    max_active_faces: parseInt($("cam-faces").value, 10),
    detect_fps: parseInt($("cam-fps-set").value, 10),
    sensitivity: parseInt($("cam-sens").value, 10)}};
  try {
    const r = await api("/api/settings", body);
    if (r.ok) { toast("Đã lưu vào config.yaml — áp dụng từ lần bật camera sau", "ok"); await tickSlow(); }
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
    if (r.ok) { toast("Đã bật camera", "ok"); await tickFast(); }
    else toast((r.error || "Không bật được camera") + (r.hint ? " — " + r.hint : ""), "err");
  } catch (e) { toast(e.message, "err"); }
}
async function stopCamera() {
  try {
    await api("/api/camera/stop", {});
    toast("Đã dừng camera — các phiên đang đo được chốt vào SQLite", "ok");
    await tickFast();
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
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("users") + "Học sinh</h2>" +
    "<p>Thêm/sửa/xóa, nhập xuất danh sách, quản lý đồng ý. Rút đồng ý là xóa vector mặt ngay.</p></div></div>" +
    '<div class="card"><div class="toolbar">' +
    '<input class="search" id="stu-search" placeholder="Tìm theo tên hoặc mã…" value="' + esc(S.studentQuery) + '">' +
    '<span class="spacer"></span>' +
    '<button class="btn btn-primary" id="stu-add">' + ico("plus") + " Thêm học sinh</button>" +
    '<button class="btn" id="stu-import">' + ico("upload") + " Nhập CSV</button>" +
    '<button class="btn" id="stu-export"' + (canCsv ? "" : ' disabled title="Cần bậc Pro trở lên — đổi trong Cài đặt"') + ">" +
    ico("download") + " Xuất CSV</button></div>" +
    (canCsv ? "" : '<div class="lock-note">' + ico("lock") + " Xuất CSV cần bậc Pro trở lên (đổi bậc trong Cài đặt).</div>") +
    '<div id="students-body" class="mt"></div></div>';
  $("stu-search").oninput = (e) => { S.studentQuery = e.target.value; fillStudentsTable(); };
  $("stu-add").onclick = () => studentModal(null);
  $("stu-import").onclick = importModal;
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
  const q = S.studentQuery.trim().toLowerCase();
  const list = S.students.filter((s) => !q || s.name.toLowerCase().includes(q) || s.student_id.toLowerCase().includes(q));
  if (!list.length) {
    body.innerHTML = '<div class="chart-empty">Chưa có học sinh nào' + (q ? " khớp tìm kiếm" : "") +
      ". Bấm <b>Thêm học sinh</b> hoặc <b>Nhập CSV</b>.</div>";
    return;
  }
  body.innerHTML = '<table class="tbl"><thead><tr><th>Mã</th><th>Tên</th><th>Năm sinh</th><th>Giới tính</th>' +
    "<th>Đồng ý</th><th>Số phiên</th><th>SEI TB</th><th>Lần đo cuối</th><th></th></tr></thead><tbody>" +
    list.map((s) =>
      "<tr><td><b>" + esc(s.student_id) + "</b></td>" +
      '<td><a href="#analysis" data-sid="' + esc(s.student_id) + '" class="stu-link">' + esc(s.name) + "</a></td>" +
      "<td>" + (s.birth_year || "--") + "</td><td>" + esc(s.gender || "--") + "</td>" +
      '<td><label class="switch"><input type="checkbox" data-consent="' + esc(s.student_id) + '"' +
      (s.consent ? " checked" : "") + '><i></i></label></td>' +
      "<td>" + s.sessions + "</td><td>" + num(s.sei_mean, 1) + "</td>" +
      "<td>" + fmtDateTime(s.last_session_ms) + "</td>" +
      '<td style="white-space:nowrap"><button class="btn btn-sm" data-edit="' + esc(s.student_id) + '">' + ico("edit") + "</button> " +
      '<button class="btn btn-sm btn-danger" data-del="' + esc(s.student_id) + '">' + ico("trash") + "</button></td></tr>").join("") +
    "</tbody></table>" +
    '<div class="avg-note mt">Đồng ý = phụ huynh/nhà trường cho phép nhận diện và lưu vector mặt (chỉ lưu vector, không lưu ảnh). ' +
    "Rút đồng ý sẽ xóa vector ngay lập tức.</div>";
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

async function loadAnalysis() {
  const body = $("analysis-body");
  if (!body) return;
  if (!S.analysisSid) { body.innerHTML = '<div class="chart-empty">Chưa có học sinh. Thêm em ở màn Học sinh trước.</div>'; return; }
  body.innerHTML = '<div class="muted small">Đang tải…</div>';
  try {
    const [data, detail] = await Promise.all([
      api("/api/analysis?id=" + encodeURIComponent(S.analysisSid) + "&scope=" + S.analysisScope),
      api("/api/student?id=" + encodeURIComponent(S.analysisSid))]);
    const stu = S.students.find((s) => s.student_id === S.analysisSid) || {name: S.analysisSid};
    let band = null, baseHtml = '<div class="muted small">Chưa có baseline cá nhân (cần bậc Pro và đủ phiên đo).</div>';
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
        : '<div class="chart-empty">Phiên gần nhất chưa có chuỗi phút (bảng metrics). Lõi camera sẽ ghi chuỗi này ở bước nối tiếp theo; dữ liệu mẫu có sẵn để xem hình dạng biểu đồ.</div>';
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
        ? '<table class="tbl"><thead><tr><th>Bắt đầu</th><th>Kết thúc</th><th>Phút hợp lệ</th><th>SEI TB</th><th>EAR</th><th>MAR</th><th>Nguồn</th></tr></thead><tbody>' +
          sessions.slice(0, 15).map((s) => {
            const f = s.features || {};
            return "<tr><td>" + fmtDateTime(s.started_at_wall_ms) + "</td><td>" + fmtTime(s.ended_at_wall_ms) + "</td>" +
              "<td>" + Math.round(s.valid_ms / 60000) + "</td><td><b>" + num(s.sei_mean, 1) + "</b></td>" +
              "<td>" + num(f.ear && f.ear.mean, 2) + "</td><td>" + num(f.mar && f.mar.mean, 2) + "</td>" +
              "<td>" + (s.notes && s.notes.demo ? '<span class="sit-chip warn">DEMO</span>' : esc(s.device)) + "</td></tr>";
          }).join("") + "</tbody></table>"
        : '<div class="muted small">Chưa có phiên đo.</div>') + "</div>";
  } catch (e) { body.innerHTML = '<div class="chart-empty">Lỗi tải phân tích: ' + esc(e.message) + "</div>"; }
}

/* ============================== màn 5: Cảnh báo ============================== */
async function renderAlerts() {
  if (!S.students.length) { try { S.students = await api("/api/students"); } catch (e) { S.students = []; } }
  const f = S.alertFilter;
  const kinds = Object.keys(KIND_LABEL);
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("bell") + "Cảnh báo</h2>" +
    "<p>Mức 1 (nhắc nhẹ) · Mức 2 (cần để ý) · Mức 3 (buồn ngủ / nguy hiểm). Lọc và đánh dấu đã xem.</p></div></div>" +
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
    '<span class="spacer"></span>' +
    '<label class="small" style="display:flex;align-items:center;gap:6px"><input type="checkbox" id="al-sound"' +
    (S.soundOn ? " checked" : "") + "> Âm báo</label>" +
    '<button class="btn" id="al-seen-all">' + ico("check") + " Tất cả đã xem</button></div>" +
    '<div id="alerts-list"></div></div>';
  const reload = () => {
    S.alertFilter = {student: $("al-student").value, kind: $("al-kind").value, level: $("al-level").value,
      date: $("al-date").value, unseen: $("al-unseen").checked ? "1" : ""};
    loadAlerts();
  };
  for (const id of ["al-student", "al-kind", "al-level", "al-date", "al-unseen"]) $(id).onchange = reload;
  $("al-sound").onchange = (e) => { S.soundOn = e.target.checked; localStorage.setItem("fc_sound", S.soundOn ? "1" : "0"); };
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
      list.innerHTML = '<div class="chart-empty">Không có cảnh báo nào khớp bộ lọc.</div>';
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
function beep() {
  if (!S.soundOn) return;
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
    [["session", "Phiên đo"], ["day", "Theo ngày"], ["week", "Theo tuần"]].map(([v, l]) =>
      '<button class="tab' + (S.reportScope === v ? " active" : "") + '" data-rscope="' + v + '">' + l + "</button>").join("") + "</div>" +
    '<input type="date" id="rp-date" value="' + S.reportDate + '">' +
    '<span class="spacer"></span>' +
    '<button class="btn btn-primary" id="rp-print">' + ico("report") + " In / lưu PDF</button>" +
    '<button class="btn" id="rp-csv"' + (canCsv ? "" : ' disabled title="Cần bậc Pro trở lên"') + ">" + ico("download") + " Xuất CSV</button>" +
    '<button class="btn" id="rp-parent" disabled title="Cần bậc Pro Max - giai đoạn 5">' + ico("lock") + " Mẫu phụ huynh</button></div>" +
    (canCsv ? "" : '<div class="lock-note">' + ico("lock") + " Xuất CSV/Excel cần bậc Pro; mẫu báo cáo phụ huynh cần bậc Pro Max (giai đoạn 5).</div>") +
    '<div id="report-body" class="mt"></div>' +
    '<div class="avg-note mt">Số liệu chỉ để hỗ trợ giáo viên, không dùng làm căn cứ kỷ luật duy nhất.</div></div>';
  for (const t of $("view").querySelectorAll("[data-rscope]")) {
    t.onclick = () => { S.reportScope = t.dataset.rscope; renderReports(); };
  }
  $("rp-date").onchange = (e) => { S.reportDate = e.target.value || todayStr(); loadReport(); };
  $("rp-print").onclick = () => window.open("/api/reports/print?scope=" + S.reportScope + "&date=" + S.reportDate, "_blank");
  $("rp-csv").onclick = () => downloadCsv("/api/reports/csv?scope=" + S.reportScope + "&date=" + S.reportDate,
    "bao_cao_" + S.reportScope + "_" + S.reportDate + ".csv");
  $("rp-parent").onclick = () => toast("Mẫu báo cáo phụ huynh cần bậc Pro Max — phát hành ở giai đoạn 5", "err");
  loadReport();
}

async function loadReport() {
  const body = $("report-body");
  if (!body) return;
  body.innerHTML = '<div class="muted small">Đang tải…</div>';
  try {
    const data = await api("/api/reports?scope=" + S.reportScope + "&date=" + S.reportDate);
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
  const canAuto = allows("auto_backup");
  const st = S.settings;
  $("view").innerHTML =
    '<div class="page-head"><div><h2>' + ico("database") + "Sao lưu & lưu trữ</h2>" +
    "<p>Sao lưu/khôi phục bằng API backup của SQLite (an toàn cả khi đang đo). Có thể chép thư mục backups/ ra USB.</p></div></div>" +
    '<div class="grid grid-2">' +
    '<div class="card"><h3>' + ico("save") + "Sao lưu thủ công</h3>" +
    '<button class="btn btn-primary" id="bk-now">' + ico("save") + " Sao lưu ngay</button>" +
    '<div id="backup-list" class="mt"></div></div>' +
    '<div class="card"><h3>' + ico("settings") + "Tự động & thời gian lưu</h3>" +
    switchRow("bk-auto", "Sao lưu tự động theo lịch (Pro)", st.auto_backup === "1") +
    (canAuto ? "" : '<div class="lock-note mt">' + ico("lock") + " Sao lưu tự động cần bậc Pro trở lên. Bản standard sao lưu thủ công ở nút bên.</div>") +
    '<div class="field mt"><label>Thời gian lưu dữ liệu (ngày) — tự xóa phiên quá hạn</label>' +
    '<input type="number" id="bk-retention" min="1" value="' + esc(st.retention_days || "180") + '"></div>' +
    '<button class="btn" id="bk-retention-save">' + ico("check") + " Lưu thời gian lưu</button>" +
    '<div class="lock-note mt">Dữ liệu quá hạn bị dọn khi lưu cài đặt và mỗi lần mở app. Xóa học sinh (chuyển trường) xóa dây chuyền ngay ở màn Học sinh.</div></div>' +
    "</div>" +
    '<div class="card mt"><h3>' + ico("info") + "Dữ liệu mẫu (DEMO)</h3>" +
    "<p class=\"muted small\">Bộ số mẫu có gắn nhãn để nghiệm thu giao diện khi chưa đo thật: 5 em \"HS Mẫu\", 6 buổi/em, cảnh báo và biểu đồ 30 phút. " +
    "Mọi dòng mẫu đều đánh dấu DEMO; nút xóa chỉ đụng dấu vết đó, không xóa dữ liệu thật.</p>" +
    '<div class="cam-buttons"><button class="btn" id="bk-seed">' + ico("upload") + " Nạp dữ liệu mẫu</button>" +
    '<button class="btn btn-danger" id="bk-purge">' + ico("trash") + " Xóa dữ liệu mẫu</button></div></div>";
  $("bk-now").onclick = async () => {
    try {
      const r = await api("/api/backup", {});
      if (r.ok) { toast("Đã sao lưu: " + r.file + " (" + fmtBytes(r.bytes) + ")", "ok"); loadBackups(); }
      else toast(r.error || "Lỗi sao lưu", "err");
    } catch (e) { toast(e.message, "err"); }
  };
  const auto = $("bk-auto");
  if (!canAuto) auto.disabled = true;
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
  loadBackups();
}

async function loadBackups() {
  const list = $("backup-list");
  if (!list) return;
  try {
    const files = await api("/api/backups");
    if (!files.length) { list.innerHTML = '<div class="muted small">Chưa có bản sao lưu nào trong thư mục backups/.</div>'; return; }
    list.innerHTML = '<table class="tbl"><thead><tr><th>File</th><th>Dung lượng</th><th>Lúc tạo</th><th></th></tr></thead><tbody>' +
      files.map((f) => "<tr><td>" + esc(f.file) + "</td><td>" + fmtBytes(f.bytes) + "</td>" +
        "<td>" + fmtDateTime(f.mtime_ms) + "</td>" +
        '<td><button class="btn btn-sm" data-restore="' + esc(f.file) + '">' + ico("refresh") + " Khôi phục</button></td></tr>").join("") +
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
async function renderSettings() {
  const st = S.settings;
  const tiers = (S.system && S.system.tiers) || ["standard", "pro", "promax", "ultra"];
  const gates = (S.system && S.system.gates) || {};
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
    '<div class="field"><label>Thời gian chờ giữa 2 cảnh báo cùng loại (giây)</label>' +
    '<input type="number" id="set-cooldown" min="0" value="' + esc(st.alert_cooldown_s || "60") + '"></div>' +
    '<button class="btn btn-primary" id="set-alert-save">' + ico("check") + " Lưu ngưỡng</button>" +
    '<div class="lock-note mt">Ngưỡng lưu trong CSDL để chỉnh được như đặc tả 7.2; lõi đo sẽ đọc các giá trị này ở bước nối tiếp theo (hiện lõi dùng ngưỡng trong mã/config).</div></div>' +
    '<div class="card mt"><h3>' + ico("eye") + "Giao diện & ngôn ngữ</h3>" +
    '<div class="field"><label>Giao diện</label><select id="set-theme">' +
    '<option value="light"' + (st.theme !== "dark" ? " selected" : "") + ">Sáng</option>" +
    '<option value="dark"' + (st.theme === "dark" ? " selected" : "") + ">Tối</option></select></div>" +
    '<div class="field"><label>Ngôn ngữ</label><select id="set-lang"><option value="vi" selected>Tiếng Việt</option></select></div>' +
    '<div class="lock-note">Tiếng Việt là ngôn ngữ đầu tiên; kiến trúc chuỗi nhãn sẵn sàng cho việc thêm ngôn ngữ khác.</div></div></div>' +
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
  if (e.key === "Escape" && modalOpen()) { closeModal(); return; }
  const tag = (e.target.tagName || "").toLowerCase();
  if (tag === "input" || tag === "select" || tag === "textarea" || modalOpen()) return;
  const k = e.key.toLowerCase();
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
const RENDER = {
  overview: renderOverview, camera: renderCamera, students: renderStudents, analysis: renderAnalysis,
  alerts: renderAlerts, reports: renderReports, backup: renderBackup, settings: renderSettings,
};

function route() {
  const v = (location.hash || "#overview").slice(1);
  S.view = RENDER[v] ? v : "overview";
  buildNav();
  RENDER[S.view]();
}

async function init() {
  buildShell();
  await refreshSettings();
  document.documentElement.dataset.theme = S.settings.theme === "dark" ? "dark" : "light";
  await tickFast();
  await loadClasses();
  route();
  window.addEventListener("hashchange", route);
  tickSlow();
  setInterval(tickFast, 5000);    // số liệu sống: tổng quan, camera, huy hiệu cảnh báo
  setInterval(tickSlow, 30000);   // biểu đồ 30 phút, trạng thái hệ thống, nhật ký
}

init();