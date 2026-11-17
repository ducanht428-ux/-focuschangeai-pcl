/* FocusChangeAI - cau noi cho ban web hosted.
   Ba che do, quyet dinh TRUOC khi app.js khoi dong (app.js cho FC_READY):
   - LIVE: may nguoi xem dang chay dashboard cuc bo (127.0.0.1:8730). Moi loi goi API
     chuyen thang ve do; server cuc bo da mo CORS cho cac web host.
   - WEB (mac dinh khi khong thay server cuc bo): webdb-bridge.js doc chinh tep SQLite 10 bang
     ma man do / app da ghi tren may nay (IndexedDB hoac userData/focuschange.db). So that,
     hoac trang thai trong trung thuc - khong bom so mau.
   - DEMO: chi khi goi tuong minh ?mode=demo. Mock toan bo API tu fixture co nhan DEMO.
   Ban cuc bo khong co file nay: FC_API_BASE / FC_READY undefined => hanh vi nhu cu.
   ?mode=live ep LIVE, ?mode=demo ep DEMO, ?mode=web ep doc SQLite bo qua do server cuc bo. */
(function () {
  "use strict";
  var LOCAL = "http://127.0.0.1:8730";
  var forced = new URLSearchParams(location.search).get("mode");
  // A simple GET from https to loopback is denied without prompting; a non-simple header forces
  // a preflight so Chrome shows its one-time local-network Allow prompt instead of a silent block.
  var PROBE_HEADERS = { "Content-Type": "application/json" };

  window.FC_READY = (async function () {
    var live = false;
    if (forced === "live") live = true;
    else if (forced !== "demo" && forced !== "web") {
      try {
        var ctl = new AbortController();
        var t = setTimeout(function () { ctl.abort(); }, 1500);
        var r = await fetch(LOCAL + "/api/system", { signal: ctl.signal, headers: PROBE_HEADERS });
        clearTimeout(t);
        var j = await r.json();
        live = !!(j && j.app);
      } catch (e) { live = false; }
    }
    if (live) {
      window.FC_MODE = "live";
      window.FC_API_BASE = LOCAL;
    } else if (forced === "demo") {
      window.FC_MODE = "demo";
      installDemo();
    } else {
      // No Python dashboard on this machine: read the SQLite file the measurement screen / installed
      // app already wrote here. Real rows or an honest empty state - never the fixture.
      window.FC_MODE = "web";
      await window.FC_WEBDB.install();
    }
    // app.js init() chay ngay sau khi FC_READY resolve (microtask), nen setTimeout 0
    // dam bao buildShell() da dung thanh tren xong roi moi chen chip che do.
    setTimeout(paintHostedChip, 0);
  })();

  function paintHostedChip() {
    var top = document.querySelector(".top-right");
    if (!top || document.getElementById("hosted-chip")) return;
    var mode = window.FC_MODE;
    var live = mode === "live";
    var wi = window.FC_WEBDB_INFO || {};
    var sessions = (wi.counts && wi.counts.observation_sessions) || 0;
    var real = mode === "web" && !!wi.ok && sessions > 0;
    var dot = live || real ? "dot-green" : "dot-grey";
    var label = live ? "Bản web · LIVE máy này"
      : mode === "web" ? (real ? "Bản web · SỐ ĐO THẬT" : "Bản web · CHƯA CÓ DỮ LIỆU")
      : "Bản web · DEMO";
    var title = live ? "Trang web đang đọc số liệu thật từ dashboard chạy trên máy này."
      : mode === "web" ? (sessions ? "Đọc " + sessions + " phiên đo thật từ " + (wi.where || "SQLite máy này") + "."
        : "Chưa có phiên đo nào trên máy này. Mở màn đo để đo thật; trang không hiển thị số mẫu.")
      : "Không thấy dashboard cục bộ: đang hiển thị dữ liệu mẫu trong trình duyệt.";
    var chip = document.createElement("span");
    chip.id = "hosted-chip";
    chip.className = "chip-db";
    chip.title = title;
    chip.innerHTML = '<i class="dot ' + dot + '"></i>' + label;
    top.insertBefore(chip, top.firstChild);
    var modeChip = document.querySelector(".chip-mode");
    if (modeChip) modeChip.innerHTML = '<i class="dot ' + dot + '"></i> Chế độ: <b>' +
      (live ? "LIVE (máy cục bộ)" : mode === "web" ? "Web · SQLite máy này" : "Xem trước trên web") + "</b>";
    var banner = document.getElementById("demo-banner");
    if (banner && mode === "demo") {
      // updateChips() vẽ lại banner mỗi nhịp; observer đổi lời dẫn sang bản web và
      // gắn lại nút kết nối / mở bản cục bộ (giữ nút "Xóa dữ liệu mẫu" của app.js).
      var decorate = function () {
        var b = banner.querySelector("span b");
        if (b && b.textContent.indexOf("DỮ LIỆU MẪU") === 0) {
          b.parentNode.innerHTML = "<b>BẢN XEM TRƯỚC WEB (DEMO)</b> — lớp học mô phỏng trong trình duyệt, không phải số đo thật. Bấm <b>Bắt đầu</b> để chạy thử camera và mọi chức năng. Mọi thay đổi (thêm em, sửa cài đặt, sao lưu) chỉ sống trong tab này: <b>tải lại trang là về bộ số mẫu</b>.";
        }
        if (document.getElementById("demo-banner-connect")) return;
        var purge = document.getElementById("demo-banner-purge");
        var connect = document.createElement("button");
        connect.className = "btn btn-sm"; connect.id = "demo-banner-connect";
        connect.textContent = "Kết nối LIVE";
        connect.title = "Dò dashboard đang chạy trên máy này (127.0.0.1:8730) để chuyển sang LIVE";
        connect.onclick = async function () {
          connect.disabled = true;
          try {
            var ctl = new AbortController();
            var t = setTimeout(function () { ctl.abort(); }, 2500);
            var r = await fetch(LOCAL + "/api/system", { signal: ctl.signal, headers: PROBE_HEADERS });
            clearTimeout(t);
            var j = await r.json();
            if (j && j.app) {
              if (typeof toast === "function") toast("Đã thấy máy cục bộ — chuyển sang LIVE…", "ok");
              setTimeout(function () { location.reload(); }, 600);
              return;
            }
            throw new Error("no server");
          } catch (e) {
            if (typeof toast === "function") toast("Chưa nối được máy cục bộ. Nếu trình duyệt hỏi quyền truy cập mạng cục bộ, bấm Cho phép rồi thử lại; hoặc mở bản cục bộ đầy đủ.", "err");
          }
          connect.disabled = false;
        };
        var openLocal = document.createElement("a");
        openLocal.className = "btn btn-sm"; openLocal.id = "demo-banner-local";
        openLocal.href = LOCAL; openLocal.target = "_blank"; openLocal.rel = "noopener";
        openLocal.textContent = "Mở bản cục bộ";
        banner.insertBefore(connect, purge);
        banner.insertBefore(openLocal, purge);
      };
      new MutationObserver(decorate).observe(banner, { childList: true, subtree: true, characterData: true });
      decorate();
    }
  }

  /* ================================ mock DEMO ================================ */
  function installDemo() {
    var FX = window.FC_FIXTURE;
    if (!FX) return;
    // Chi dich cac moc THOI DIEM; khong dich duration (blink_ms, processing_ms...).
    var MS_KEYS = ["bucket_ms", "raised_at_wall_ms", "at_ms", "started_at_wall_ms",
      "ended_at_wall_ms", "started_ms", "seen_at_ms", "created_at_ms", "last_seen_ms"];
    function rebase(node, delta) {
      if (Array.isArray(node)) { node.forEach(function (n) { rebase(n, delta); }); return; }
      if (node && typeof node === "object") {
        Object.keys(node).forEach(function (k) {
          var v = node[k];
          if (MS_KEYS.indexOf(k) >= 0 && typeof v === "number" && v > 0) node[k] = v + delta;
          else rebase(v, delta);
        });
      }
    }
    function fresh() {
      var d = JSON.parse(JSON.stringify(FX.data));
      rebase(d, Date.now() - FX.generated_at);
      // Biểu đồ 30 phút: dời bucket CUỐI về đúng phút hiện tại (làm tròn xuống phút),
      // nếu không các bucket lệch phút sẽ không khớp lưới của trendChart -> đồ thị trống.
      if (Array.isArray(d.trend) && d.trend.length) {
        var maxB = Math.max.apply(null, d.trend.map(function (r) { return r.bucket_ms; }));
        var sh = Math.floor(Date.now() / 60000) * 60000 - maxB;
        d.trend.forEach(function (r) { r.bucket_ms = Math.floor((r.bucket_ms + sh) / 60000) * 60000; });
      }
      d.demoOn = true;
      return d;
    }
    var D = fresh();
    var SNAPSHOTS = {};   // demo "backup files": in-memory snapshots keyed by file name

    /* ================= engine MÔ PHỎNG lớp học (chỉ ở chế độ DEMO) =================
       Bản web không chạy được Python/MediaPipe trong trình duyệt, nên DEMO dựng một
       lớp học mô phỏng: camera vẽ khung canvas, số liệu từng em nhảy mỗi giây, cảnh
       báo nổ theo trạng thái (nhắm mắt/ngáp/quay đầu), trend cập nhật theo phút.
       Mọi nơi đều gắn nhãn DEMO/MÔ PHỎNG — không bao giờ trình bày như số đo thật. */
    var DISP = Object.assign({ boxes: true, overlay: false, brighten: false },
      ((D.live || {}).camera || {}).options || {});
    var FIX_AVG = JSON.parse(JSON.stringify(D.overview.averages || null));
    var SIM = { running: false, started_ms: 0, frames: 0, people: [], lastAlert: {}, advisories: [],
      voice: { context: "UNKNOWN", source: null, last_speech: "", listening: false, at: Date.now(), next: 0 } };

    /* Khẩu lệnh giáo viên ở DEMO: mô phỏng trong trình duyệt, KHÔNG thu âm. Cùng hợp đồng JSON với
       /api/teacher-voice của server Python để app.js chạy một đường code cho cả LIVE và DEMO.
       Mặc định UNKNOWN - không có khẩu lệnh thì không có ngữ cảnh áp đặt, đúng như bản thật. */
    var VOICE_PHRASES = [
      ["cả lớp chú ý nhìn lên bảng", "LECTURE"],
      ["các em ghi bài vào vở", "STUDY_NOTE"],
      ["bắt đầu thảo luận nhóm đôi", "GROUP_WORK"],
      ["các em tự làm bài nhé", "SEATWORK"],
      ["ai trả lời câu hỏi này", "Q_AND_A"],
      ["hết giờ thảo luận", "LECTURE"]
    ];
    var VOICE_VI = { UNKNOWN: "", LECTURE: "Nghe giảng", STUDY_NOTE: "Đọc hoặc viết", GROUP_WORK: "Thảo luận nhóm",
      SEATWORK: "Làm bài tập", Q_AND_A: "Hỏi – đáp" };

    /* Đánh giá ngữ cảnh từng em ở DEMO: bản gương gọn của focusedge/context_eval.py (cùng bảng
       ngưỡng, cùng nguyên tắc KHÔNG khẩu lệnh / không đo được thì NEUTRAL). Chạy trên số liệu
       mô phỏng của SIM, gắn nhãn DEMO khắp nơi - không phải số đo thật. */
    var CTX_CRITERIA = {
      LECTURE: { yaw: 25, pmin: -15, pmax: 20, win: 7000, vi: "nghe giảng", on: "Đang nghe giảng", off: "Không nhìn lên bảng" },
      STUDY_NOTE: { yaw: 30, pmin: -42, pmax: -10, win: 15000, vi: "ghi bài", on: "Đang ghi/học bài", off: "Không chép bài / nhìn linh tinh" },
      SEATWORK: { yaw: 30, pmin: -42, pmax: 15, win: 20000, vi: "làm bài tập", on: "Đang làm bài / suy nghĩ", off: "Không làm bài / nhìn linh tinh" },
      Q_AND_A: { yaw: 25, pmin: -15, pmax: 20, win: 7000, vi: "hỏi – đáp", on: "Đang hướng về GV / bạn trả lời", off: "Cúi gằm tránh né / làm việc riêng" },
      GROUP_WORK: { win: 10000, vi: "thảo luận nhóm" }
    };
    var CTX_STATE_VI = { NEUTRAL: "Chưa đánh giá theo ngữ cảnh", ON_TASK: "Đang tập trung theo ngữ cảnh",
      WATCH: "Đang theo dõi (lệch nhẹ)", OFF_TASK: "Mất tập trung theo ngữ cảnh" };
    var TIC_LABEL = { 1: "Dấu hiệu giật lặp lại - nên theo dõi (DEMO)",
      2: "Dấu hiệu giật lặp lại dày đặc / kéo dài - nên trao đổi với phụ huynh (DEMO)" };
    var TIC_DISCLAIMER = "Đây KHÔNG phải chẩn đoán y khoa. Camera chỉ ghi nhận mẫu hình cử động lặp lại; " +
      "chẩn đoán rối loạn tíc thuộc về chuyên khoa Thần kinh / Tâm thần nhi. (DEMO: số liệu mô phỏng trong trình duyệt)";
    var TIC_REFS = [
      { title: "DSM-5: Tourette's disorder and other tic disorders (PMC3038221)", url: "https://pmc.ncbi.nlm.nih.gov/articles/PMC3038221/" },
      { title: "Bảng phân loại rối loạn tíc theo DSM-5 (PMC6690237)", url: "https://pmc.ncbi.nlm.nih.gov/articles/PMC6690237/" },
      { title: "Thang mức độ nghiêm trọng tíc toàn cầu Yale YGTSS (PMC10687404)", url: "https://pmc.ncbi.nlm.nih.gov/articles/PMC10687404/" },
      { title: "BV Nhi Trung ương: Hiểu đúng về rối loạn Tic", url: "https://benhviennhitrunguong.gov.vn/hieu-dung-cham-tot-hieu-dung-ve-roi-loan-tic-dau-hieu-nguyen-nhan-va-khi-nao-can-can-thiep.html" }
    ];
    var TIC_MILESTONES = [
      { range: "Dưới 4 tuần", meaning: "Co giật cơ sinh lý tạm thời - thường do thiếu ngủ, mỏi mắt, căng thẳng; có thể tự hết khi nghỉ ngơi." },
      { range: "Từ 4 tuần đến dưới 1 năm", meaning: "Khoảng theo dõi rối loạn tíc nhất thời - cần theo dõi sát tần suất và yếu tố kích phát." },
      { range: "Trên 1 năm, gần như mỗi ngày", meaning: "Khoảng rối loạn tíc mạn tính / Hội chứng Tourette - chỉ chuyên khoa mới kết luận được." }
    ];
    function pushAdvisory(p, kind, fields) {
      var a = { kind: kind, student_id: p.student_id, student_name: p.name, track_id: p.track_id, wall_ms: Date.now() };
      Object.keys(fields || {}).forEach(function (k) { a[k] = fields[k]; });
      SIM.advisories.push(a);
      if (SIM.advisories.length > 50) SIM.advisories.shift();
    }
    function simContext(p, now) {
      var ctx = SIM.voice.context, c = CTX_CRITERIA[ctx], st = p.ctx;
      if (!c) { st.state = "NEUTRAL"; st.offStart = 0; st.reason = ""; st.ctx = ctx; return; }
      if (ctx !== st.ctx) { st.offStart = 0; st.ctx = ctx; }  // đổi ngữ cảnh: reset đồng hồ liên tục
      var on, reason;
      if (p.pitch < -45) { on = false; reason = "Gục mặt / tư thế xấu"; }
      else if (ctx === "GROUP_WORK") {
        if (p.mar > 0.25) st.lastTalk = now;
        var talk = now - st.lastTalk <= 15000;
        var facing = (Math.abs(p.yaw) >= 20 && Math.abs(p.yaw) <= 75) || (p.pitch >= -40 && p.pitch <= -12);
        on = facing && talk;
        reason = on ? "Tích cực hoạt động nhóm" : (!facing ? "Tách nhóm / nhìn chỗ khác" : "Thụ động / không trao đổi");
      } else {
        on = Math.abs(p.yaw) <= c.yaw && p.pitch >= c.pmin && p.pitch <= c.pmax;
        reason = on ? c.on : c.off;
      }
      st.reason = reason;
      if (on) { st.state = "ON_TASK"; st.offStart = 0; return; }
      if (!st.offStart) st.offStart = now;
      st.total += 1000;  // simTick chạy mỗi 1 giây
      if (now - st.offStart >= c.win) {
        if (st.state !== "OFF_TASK") { st.episodes += 1; pushAdvisory(p, "CONTEXT_OFF_TASK", { context: ctx, reason: reason }); }
        st.state = "OFF_TASK";
      } else st.state = "WATCH";
    }
    function ctxView(p, now) {
      var st = p.ctx, ctx = SIM.voice.context, c = CTX_CRITERIA[ctx];
      var offS = st.offStart && (st.state === "WATCH" || st.state === "OFF_TASK") ? (now - st.offStart) / 1000 : 0;
      var totalS = st.total / 1000;
      return { context: c ? ctx : "UNKNOWN", context_vi: c ? c.vi : "", state: st.state,
        state_vi: CTX_STATE_VI[st.state], reason: st.reason, evidence: [],
        off_s: Math.round(offS * 10) / 10, off_total_s: Math.round(totalS * 10) / 10,
        window_s: c ? c.win / 1000 : 0, severity: totalS >= 600 ? "nang" : totalS >= 180 ? "trung_binh" : "",
        episodes: st.episodes, talk_recent: false, facing_group: false, group_close: false };
    }
    function ticView(p, now) {
      return { level: p.tic_level, label: TIC_LABEL[p.tic_level] || "", eye_bursts: p.tic_level ? 5 : 0,
        mouth_jerks: 0, eye_pattern: p.tic_level >= 1, mouth_pattern: false, mouth_is_weak_signal: true,
        dense: false, observed_s: Math.round((now - SIM.started_ms) / 1000),
        disclaimer: TIC_DISCLAIMER, references: p.tic_level >= 1 ? TIC_REFS : [] };
    }
    function demoTicHistory(sid) {
      // Lịch sử DỌC mô phỏng: 12 ngày quan sát, 3 ngày có mẫu hình, chưa đủ mốc 4 tuần
      // nên chưa khuyến nghị đi khám - đúng luật tic_history() của bản thật.
      var days = [], pattern = { 2: 1, 6: 1, 10: 1 };
      for (var i = 11; i >= 0; i--) {
        var d = new Date(Date.now() - i * 86400000);
        var ds = d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0") + "-" + String(d.getDate()).padStart(2, "0");
        days.push({ day: ds, level: pattern[i] || 0 });
      }
      return { student_id: sid, level: 1, label: TIC_LABEL[1], sessions_observed: 12,
        days_observed: 12, days_with_pattern: 3, span_days: 12, recommend_exam: false,
        note: "Đã ghi nhận mẫu hình giật lặp lại ở 3 ngày quan sát (DEMO). Tiếp tục theo dõi; nếu kéo dài trên 4 tuần hoặc dày lên khi căng thẳng/mệt mỏi, nên trao đổi với phụ huynh và cân nhắc đi khám.",
        disclaimer: TIC_DISCLAIMER, references: TIC_REFS, milestones: TIC_MILESTONES, days: days, demo: true };
    }
    function voiceStatus() {
      var v = SIM.voice, now = Date.now();
      if (v.context !== "UNKNOWN" && now - v.at > 180000) {
        v.context = "UNKNOWN"; v.source = null; v.last_speech = ""; v.at = now;
      }
      return { context: v.context, last_speech: v.last_speech,
        elapsed_s: Math.round((now - v.at) / 100) / 10, is_running: v.listening,
        has_command: v.context !== "UNKNOWN", source: v.source,
        mic_available: true, sends_audio_offsite: true, auto_reset_seconds: 180, last_error: "",
        privacy_note: "DEMO: micro mô phỏng, không thu âm thật.", demo: true };
    }
    function voiceSimTick() {
      var v = SIM.voice, now = Date.now();
      if (!v.listening || !SIM.running || now < v.next) return;
      var p = VOICE_PHRASES[Math.floor(Math.random() * VOICE_PHRASES.length)];
      v.context = p[1]; v.source = "speech"; v.last_speech = p[0] + " (DEMO)";
      v.at = now; v.next = now + rnd(14000, 28000);
    }
    function rnd(a, b) { return a + Math.random() * (b - a); }
    function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
    function mean(arr) { return arr.length ? arr.reduce(function (x, y) { return x + y; }, 0) / arr.length : null; }
    function emptyLive() {
      return { board: null, age_s: null, presence: [],
        camera: { running: false, error: "", people: 0, fps: 0, processing_ms: 0, started_ms: 0, frames: 0, options: DISP },
        people: [], fps: 0, activity: "" };
    }
    function initPeople() {
      SIM.people = D.students.filter(function (s) { return s.consent !== 0; }).slice(0, 4).map(function (s, i) {
        var now = Date.now();
        return { track_id: i + 1, student_id: s.student_id, name: s.name,
          ear: 0.28, mar: 0.1, yaw: rnd(-6, 6), pitch: rnd(-4, 4), sei: rnd(62, 88),
          perclos: rnd(4, 12), distance_cm: rnd(45, 62), quality: rnd(78, 96),
          blink_at: now + rnd(1500, 5000), blink_until: 0,
          yawn_at: now + rnd(18000, 60000), yawn_until: 0,
          away_at: now + rnd(12000, 40000), away_until: 0,
          doze_at: now + rnd(25000, 80000), doze_until: 0,
          situation: "FORWARD", alert: "", identity_conf: "demo",
          ctx: { state: "NEUTRAL", offStart: 0, total: 0, episodes: 0, lastTalk: 0, reason: "", ctx: "UNKNOWN" },
          // Một em (track 2) được hẹn giờ nổ mẫu hình Tic mức 1 để DEMO cho thấy huy hiệu + khuyến nghị.
          tic_at: i === 1 ? now + rnd(30000, 60000) : 0, tic_level: 0,
          shirt: ["#2f6fb2", "#b2542f", "#2f9e6e", "#7a4fbf"][i % 4],
          skin: ["#e8b98a", "#c98d5e", "#f0c9a0", "#a86e42"][i % 4] };
      });
    }
    function pushAlert(p, kind, level, msg) {
      var now = Date.now();
      if (SIM.lastAlert[p.track_id] && now - SIM.lastAlert[p.track_id] < 40000) return;
      SIM.lastAlert[p.track_id] = now;
      D.alerts.unshift({ alert_id: "al_web_" + now + "_" + p.track_id, student_id: p.student_id,
        student_name: p.name, kind: kind, level: level, message: msg,
        raised_at_wall_ms: now, seen_at_ms: null });
      D.alerts = D.alerts.slice(0, 250);
      p.alert = msg;
      syncOverview();
    }
    function simTick() {
      if (!SIM.running) return;
      var now = Date.now();
      var cfg = (D.system || {}).camera_cfg || {};
      SIM.frames += cfg.detect_fps || 8;
      SIM.people.forEach(function (p) {
        if (now > p.blink_at) { p.blink_until = now + 260; p.blink_at = now + rnd(2500, 6500); }
        if (now > p.yawn_at) { p.yawn_until = now + 1600; p.yawn_at = now + rnd(45000, 110000);
          pushAlert(p, "YAWN", 2, "Ngáp dài — có thể đang mệt."); }
        if (now > p.away_at) { p.away_until = now + rnd(2500, 5000); p.away_at = now + rnd(25000, 70000);
          p.away_dir = Math.random() < 0.5 ? -1 : 1;
          pushAlert(p, "LOOK_AWAY", 2, "Đầu đang quay lệch khỏi màn hình."); }
        if (now > p.doze_at) { p.doze_until = now + rnd(3000, 6000); p.doze_at = now + rnd(60000, 140000);
          pushAlert(p, "DROWSY", 3, "Mắt nhắm quá lâu — có thể đang buồn ngủ!"); }
        var blinking = now < p.blink_until, dozing = now < p.doze_until,
          away = now < p.away_until, yawing = now < p.yawn_until;
        var earT = blinking || dozing ? 0.06 : 0.26 + 0.06 * Math.sin(now / 4000 + p.track_id);
        var marT = yawing ? 0.62 : 0.08 + 0.05 * Math.sin(now / 5000 + p.track_id * 2);
        var yawT = away ? 34 * (p.away_dir || 1) : 7 * Math.sin(now / 6000 + p.track_id);
        var pitchT = dozing ? 18 : 6 * Math.sin(now / 7000 + p.track_id * 3);
        p.ear += (earT - p.ear) * 0.5; p.mar += (marT - p.mar) * 0.5;
        p.yaw += (yawT - p.yaw) * 0.4; p.pitch += (pitchT - p.pitch) * 0.4;
        p.perclos = clamp(p.perclos + (p.ear < 0.15 ? 3 : -1), 0, 45);
        p.distance_cm = clamp(p.distance_cm + rnd(-0.6, 0.6), 35, 75);
        p.quality = clamp(p.quality + rnd(-1.5, 1.5), 55, 98);
        p.situation = dozing && p.ear < 0.12 ? "DOZING" : Math.abs(p.yaw) > 18 ? "TURNED" : p.pitch > 12 ? "DOWN" : "FORWARD";
        var seiT = 84 - (Math.abs(p.yaw) > 14 ? 18 : 0) - (p.pitch > 12 ? 14 : 0) -
          (p.ear < 0.15 ? 22 : 0) - (p.mar > 0.5 ? 10 : 0) - p.perclos * 0.3 + rnd(-3, 3);
        p.sei += (clamp(seiT, 5, 98) - p.sei) * 0.35;
        if (!blinking && !dozing && !away && !yawing) p.alert = "";
        simContext(p, now);
        if (p.tic_at && now > p.tic_at) {
          p.tic_at = 0; p.tic_level = 1;
          pushAdvisory(p, "TIC_WATCH", { level: 1, label: TIC_LABEL[1] });
        }
      });
      var n = SIM.people.length;
      var fps = (cfg.detect_fps || 8) + rnd(-0.4, 0.4);
      D.live = { board: { advisories: SIM.advisories.slice(-50) }, age_s: Math.round((now - SIM.started_ms) / 1000),
        presence: SIM.people.map(function (p) {
          return { student_id: p.student_id, name: p.name, sei: Math.round(p.sei), situation: p.situation, last_seen_ms: now }; }),
        camera: { running: true, error: "", people: n, fps: fps, processing_ms: rnd(85, 140),
          started_ms: SIM.started_ms, frames: SIM.frames, options: DISP },
        people: SIM.people.map(function (p) {
          return { track_id: p.track_id, student_id: p.student_id, name: p.name, ear: p.ear, mar: p.mar,
            yaw: p.yaw, pitch: p.pitch, sei: p.sei, perclos: p.perclos, distance_cm: p.distance_cm,
            quality: p.quality, situation: p.situation, alert: p.alert, identity_conf: p.identity_conf,
            context: ctxView(p, now), tic: ticView(p, now) }; }),
        fps: fps, activity: ["Học cá nhân", "Thảo luận nhóm", "Đọc hoặc viết", "Học cá nhân"][Math.floor(now / 20000) % 4] };
      voiceSimTick();
      // Có khẩu lệnh (DEMO) thì ngữ cảnh lớp nghe theo khẩu lệnh, giống classroom.py ở bản thật.
      if (SIM.voice.context !== "UNKNOWN") {
        D.live.activity = VOICE_VI[SIM.voice.context] + " (khẩu lệnh GV · DEMO)";
      }
      D.overview.camera = { state: "on", note: "demo · " + n + " em trong khung (mô phỏng)" };
      D.overview.ai = { state: "active", note: "Engine mô phỏng chạy trong trình duyệt" };
      D.overview.averages = { ear: mean(SIM.people.map(function (p) { return p.ear; })),
        mar: mean(SIM.people.map(function (p) { return p.mar; })),
        angle: mean(SIM.people.map(function (p) { return Math.abs(p.yaw); })),
        sei: mean(SIM.people.map(function (p) { return p.sei; })),
        label: "mô phỏng demo trực tiếp", n: n };
      var bucket = Math.floor(now / 60000) * 60000;
      var row = D.trend.find(function (r) { return r.bucket_ms === bucket; });
      var msei = mean(SIM.people.map(function (p) { return p.sei; }));
      if (row) row.mean_sei = msei; else D.trend.push({ bucket_ms: bucket, mean_sei: msei });
      D.trend = D.trend.slice(-45);
      syncOverview();
    }
    function simStart() {
      initPeople(); SIM.running = true; SIM.started_ms = Date.now(); SIM.frames = 0; SIM.lastAlert = {}; SIM.advisories = [];
      simTick(); audit("camera_start", "demo mo phong");
    }
    function simStop() {
      SIM.running = false; SIM.people = [];
      D.live = emptyLive();
      D.overview.camera = { state: "off", note: "demo chưa bật — bấm Bắt đầu để mô phỏng" };
      D.overview.ai = { state: "ready", note: "Sẵn sàng — bấm Bắt đầu để chạy mô phỏng" };
      D.overview.averages = FIX_AVG ? JSON.parse(JSON.stringify(FIX_AVG)) : null;
      if (D.overview.averages) D.overview.averages.label = "phiên mẫu gần nhất (demo)";
      syncOverview(); audit("camera_stop", "demo mo phong");
    }

    /* Camera demo: vẽ lớp học mô phỏng lên canvas rồi bơm JPEG data-URL vào hai thẻ
       img của app.js (Tổng quan + Camera). app.js chỉ gắn src khi camera chạy, nên
       observer đổi src "/api/camera/stream" sang khung demo ngay lập tức. */
    var cv = document.createElement("canvas"); cv.width = 640; cv.height = 480;
    var cx = cv.getContext("2d");
    var SIT_VI = { FORWARD: "", DOWN: "Cúi đầu", TURNED: "Quay đầu", DOZING: "Nhắm mắt" };
    function drawFrame() {
      var now = Date.now();
      var g = cx.createLinearGradient(0, 0, 0, 480);
      g.addColorStop(0, "#16233a"); g.addColorStop(1, "#0d1626");
      cx.fillStyle = g; cx.fillRect(0, 0, 640, 480);
      cx.fillStyle = "#1d2c44"; cx.fillRect(0, 340, 640, 140);
      cx.fillStyle = "#e8eef7"; cx.fillRect(40, 34, 560, 110);
      cx.strokeStyle = "#9fb6d4"; cx.lineWidth = 3; cx.strokeRect(40, 34, 560, 110);
      cx.fillStyle = "#33507a"; cx.font = "600 20px 'Segoe UI', sans-serif";
      cx.fillText("LỚP HỌC · BẢN MÔ PHỎNG", 60, 78);
      cx.font = "13px 'Segoe UI', sans-serif"; cx.fillStyle = "#5b7691";
      cx.fillText("FocusChange AI — demo trong trình duyệt", 60, 104);
      var n = SIM.people.length;
      var slots = n === 1 ? [320] : n === 2 ? [200, 440] : n === 3 ? [140, 320, 500] : [110, 250, 390, 530];
      SIM.people.forEach(function (p, i) {
        var x = slots[i] || 320, y = 250 + (i % 2) * 26;
        cx.fillStyle = "#24344f"; cx.fillRect(x - 72, y + 96, 144, 26);
        cx.strokeStyle = "#3b5f96"; cx.lineWidth = 2; cx.strokeRect(x - 72, y + 96, 144, 26);
        cx.fillStyle = "#0a1526"; cx.fillRect(x - 26, y + 78, 52, 20);
        cx.fillStyle = p.shirt; cx.beginPath(); cx.ellipse(x, y + 62, 46, 34, 0, 0, Math.PI * 2); cx.fill();
        cx.save(); cx.translate(x, y + p.pitch * 0.5); cx.rotate(p.yaw * 0.006);
        cx.fillStyle = p.skin; cx.beginPath(); cx.arc(0, 0, 30, 0, Math.PI * 2); cx.fill();
        cx.fillStyle = "#2a2a33"; cx.beginPath(); cx.arc(0, -6, 30, Math.PI, 0); cx.fill();
        cx.fillStyle = "#22303f";
        if (p.ear < 0.12) {
          cx.fillRect(-15, -5, 9, 2); cx.fillRect(6, -5, 9, 2);
        } else {
          var ry = 4 * clamp(p.ear / 0.3, 0.2, 1.4);
          cx.beginPath(); cx.ellipse(-11, -4, 4, ry, 0, 0, Math.PI * 2); cx.fill();
          cx.beginPath(); cx.ellipse(11, -4, 4, ry, 0, 0, Math.PI * 2); cx.fill();
        }
        cx.fillStyle = "#7a4a3a"; cx.beginPath(); cx.ellipse(0, 14, 7, 3 + p.mar * 16, 0, 0, Math.PI * 2); cx.fill();
        cx.restore();
        if (DISP.boxes) {
          cx.strokeStyle = "#35c46f"; cx.lineWidth = 2; cx.strokeRect(x - 52, y - 40, 104, 150);
          cx.strokeStyle = "#ffd23e"; cx.strokeRect(x - 32, y - 32, 64, 64);
        }
        if (DISP.overlay) {
          cx.fillStyle = "rgba(10,21,38,0.82)"; cx.fillRect(x - 70, y - 78, 140, 34);
          cx.fillStyle = "#cfe3ff"; cx.font = "11px 'Segoe UI', sans-serif";
          cx.fillText("EAR " + p.ear.toFixed(2) + "  MAR " + p.mar.toFixed(2), x - 64, y - 64);
          cx.fillText("SEI " + Math.round(p.sei) + "  " + p.perclos.toFixed(0) + "%", x - 64, y - 51);
        }
        var sit = SIT_VI[p.situation];
        if (sit) {
          cx.fillStyle = p.situation === "DOZING" ? "#ff5d5d" : "#ffb020";
          cx.font = "600 12px 'Segoe UI', sans-serif"; cx.fillText(sit, x - 24, y - 46);
        }
        cx.fillStyle = "rgba(10,21,38,0.85)"; cx.fillRect(x - 60, y + 124, 120, 22);
        cx.fillStyle = "#e6eefb"; cx.font = "600 12px 'Segoe UI', sans-serif";
        cx.fillText(p.name.slice(0, 16), x - 54, y + 139);
        cx.fillStyle = "#22303f"; cx.fillRect(x - 60, y + 148, 120, 5);
        cx.fillStyle = p.sei >= 70 ? "#35c46f" : p.sei >= 45 ? "#ffb020" : "#ff5d5d";
        cx.fillRect(x - 60, y + 148, 120 * clamp(p.sei, 0, 100) / 100, 5);
      });
      if (DISP.brighten) { cx.fillStyle = "rgba(255,255,255,0.12)"; cx.fillRect(0, 0, 640, 480); }
      cx.fillStyle = "rgba(255,176,32,0.92)"; cx.fillRect(474, 8, 158, 26);
      cx.fillStyle = "#4a2f00"; cx.font = "700 13px 'Segoe UI', sans-serif";
      cx.fillText("DEMO · MÔ PHỎNG", 486, 26);
      cx.fillStyle = "#9fb6d4"; cx.font = "12px 'Segoe UI', sans-serif";
      cx.fillText(new Date(now).toLocaleTimeString("vi-VN"), 12, 470);
    }
    function feedStreams() {
      if (!SIM.running) return;
      drawFrame();
      var url = cv.toDataURL("image/jpeg", 0.72);
      ["ov-stream", "cam-stream"].forEach(function (id) {
        var im = document.getElementById(id);
        if (im && !im.hidden) im.src = url;
      });
    }
    setInterval(simTick, 1000);
    setInterval(feedStreams, 700);
    new MutationObserver(function (muts) {
      muts.forEach(function (m) {
        if (SIM.running && m.target.getAttribute && m.target.getAttribute("src") === "/api/camera/stream") feedStreams();
      });
    }).observe(document.body, { subtree: true, attributes: true, attributeFilter: ["src"] });
    D.live = emptyLive();
    // Mở link là thấy lớp học demo ĐANG CHẠY ngay (camera mô phỏng + số liệu nhảy),
    // thay vì màn hình chết chờ bấm nút. Nhãn DEMO/MÔ PHỎNG hiện khắp nơi.
    simStart();

    function todayStr() {
      var d = new Date(), p = function (x) { return String(x).padStart(2, "0"); };
      return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate());
    }
    function dayStr(ms) {
      var d = new Date(ms), p = function (x) { return String(x).padStart(2, "0"); };
      return d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate());
    }
    function jsonRes(obj, code) {
      return new Response(JSON.stringify(obj), {
        status: code || 200, headers: { "Content-Type": "application/json; charset=utf-8" } });
    }
    function csvRes(text) {
      // BOM, like the server's utf-8-sig: without it Excel reads the Vietnamese columns as mojibake.
      return new Response("\uFEFF" + text, { status: 200, headers: { "Content-Type": "text/csv; charset=utf-8" } });
    }
    function audit(action, detail) {
      D.audit.unshift({ at_ms: Date.now(), actor: "teacher (web)", action: action, detail: detail || "" });
      D.audit = D.audit.slice(0, 60);
    }
    function syncOverview() {
      var ov = D.overview;
      ov.students = D.students.length;
      ov.alerts_unseen = D.alerts.filter(function (a) { return a.seen_at_ms == null; }).length;
      ov.alerts_today = D.alerts.filter(function (a) { return dayStr(a.raised_at_wall_ms) === todayStr(); }).length;
      ov.demo = !!D.demoOn;
    }
    function emptyOverview() {
      var ov = JSON.parse(JSON.stringify(D.overview));
      ov.alerts_today = 0; ov.averages = null;
      ov.camera = { state: "off", note: "không có phiên ngày này" };
      syncOverview();
      return ov;
    }
    function overviewFor(date) {
      if (!date || date === todayStr()) { syncOverview(); return D.overview; }
      return emptyOverview();
    }
    function webSystem() {
      return {
        app: "Bản xem trước web (demo)", tier: D.settings.tier, demo: true,
        deps: { mediapipe: false, ultralytics: false, cv2: false, yaml: true, PIL: true },
        model: { present: false }, database: { ready: true }, camera_running: SIM.running,
        camera_cfg: (D.system || {}).camera_cfg || {}
      };
    }
    function filterAlerts(q) {
      return D.alerts.filter(function (a) {
        if (q.student && a.student_id !== q.student && (a.student_name || "").indexOf(q.student) < 0) return false;
        if (q.kind && a.kind !== q.kind) return false;
        if (q.level && String(a.level) !== q.level) return false;
        if (q.date && dayStr(a.raised_at_wall_ms) !== q.date) return false;
        if (q.unseen && a.seen_at_ms != null) return false;
        return true;
      });
    }
    function reportsFor(scope, date) {
      var src = (!date || date === todayStr()) ? D.reports : D.reports_empty;
      return src[scope] || src.day;
    }
    // Print columns, CSV quoting and HTML escaping live in report-shared.js so the demo, the
    // real-data bridge and the Python server cannot drift apart.
    var R = window.FC_REPORT;
    function studentsCsv() { return R.studentsCsv(D.students); }
    function reportsCsv(scope, date) { return R.reportsCsv(reportsFor(scope, date).rows || []); }
    function printHtml(scope, date) {
      return R.printHtml(scope, date, reportsFor(scope, date),
        "BẢN XEM TRƯỚC WEB (DEMO) — số liệu mẫu, không phải số đo thật.");
    }

    async function route(method, path, q, body) {
      if (method === "GET") {
        switch (path) {
          case "/api/classes": return jsonRes(D.classes);
          case "/api/overview": return jsonRes(overviewFor(q.get("date")));
          case "/api/live": return jsonRes(D.live);
          case "/api/trend": return jsonRes(D.trend);
          case "/api/system": return jsonRes(webSystem());
          case "/api/audit": return jsonRes(D.audit);
          case "/api/alerts": return jsonRes(filterAlerts(q));
          case "/api/settings": return jsonRes(D.settings);
          case "/api/camera/options": return jsonRes(DISP);
          case "/api/teacher-voice": return jsonRes(voiceStatus());
          case "/api/camera/snapshot": {
            // Same single-JPEG contract as the server endpoint, so the watchStream() fallback in
            // app.js also works when the page is running the in-browser simulation.
            if (!SIM.running) return jsonRes({ ok: false, error: "DEMO: camera chưa bật" }, 503);
            drawFrame();
            var b64 = cv.toDataURL("image/jpeg", 0.8).split(",")[1];
            var bin = atob(b64), arr = new Uint8Array(bin.length);
            for (var bi = 0; bi < bin.length; bi++) arr[bi] = bin.charCodeAt(bi);
            return new Response(arr, { status: 200,
              headers: { "Content-Type": "image/jpeg", "Cache-Control": "no-store" } });
          }
          case "/api/students": return jsonRes(D.students);
          case "/api/student": return jsonRes(D.student[q.get("id")] || D.student_empty);
          case "/api/student/tic-history": return jsonRes(demoTicHistory(q.get("id") || ""));
          case "/api/analysis": {
            var per = D.analysis[q.get("id")] || {};
            return jsonRes(per[q.get("scope")] || D.analysis_empty[q.get("scope")] || D.analysis_empty.buoi);
          }
          case "/api/reports": return jsonRes(reportsFor(q.get("scope"), q.get("date")));
          case "/api/backups": return jsonRes(D.backups);
          case "/api/students/csv": return csvRes(studentsCsv());
          case "/api/reports/csv":
            return csvRes(reportsCsv(q.get("scope"), q.get("date")));
        }
        return jsonRes({ ok: false, error: "DEMO: không có tuyến " + path }, 404);
      }
      // POST
      switch (path) {
        case "/api/settings":
          Object.assign(D.settings, body || {});
          audit("settings_save", Object.keys(body || {}).join(","));
          return jsonRes({ ok: true });
        case "/api/camera/start":
          // Người dùng bấm Bắt đầu vì muốn thấy MẶT MÌNH, không phải xem diễn.
          // Nếu dashboard cục bộ đang chạy (và không ép mode=demo), chuyển thẳng
          // sang LIVE + autostart: một lần bấm là camera thật bật, mặt hiện ngay.
          if (forced !== "demo") {
            try {
              var ctl2 = new AbortController();
              var t2 = setTimeout(function () { ctl2.abort(); }, 2000);
              var pr = await fetch(LOCAL + "/api/system", { signal: ctl2.signal, headers: PROBE_HEADERS });
              clearTimeout(t2);
              var pj = await pr.json();
              if (pj && pj.app) {
                setTimeout(function () { location.href = location.pathname + "?mode=live&autostart=1"; }, 600);
                return jsonRes({ ok: false, error: "Đã thấy dashboard cục bộ — đang chuyển sang LIVE để bật camera thật…" });
              }
            } catch (e) { /* không với tới máy cục bộ: chạy mô phỏng có nhãn */ }
          }
          simStart();
          return jsonRes({ ok: true });
        case "/api/camera/stop":
          simStop();
          return jsonRes({ ok: true });
        case "/api/teacher-voice/context": {
          var want = (body || {}).context || "UNKNOWN";
          if (["UNKNOWN", "LECTURE", "STUDY_NOTE", "GROUP_WORK", "SEATWORK", "Q_AND_A"].indexOf(want) < 0) {
            return jsonRes({ ok: false, error: "DEMO: ngữ cảnh không hợp lệ" }, 400);
          }
          var vv = SIM.voice;
          vv.context = want; vv.at = Date.now();
          vv.source = want === "UNKNOWN" ? null : "manual";
          // Không bịa câu nói khi bấm nút: last_speech chỉ chứa lời nghe được (mô phỏng), giống
          // teacher_voice.py ở bản thật. Nguồn đã nằm ở trường source.
          vv.last_speech = "";
          audit("voice_context", want);
          return jsonRes({ ok: true, status: voiceStatus() });
        }
        case "/api/teacher-voice/listen": {
          var on = ((body || {}).action || "start") === "start";
          SIM.voice.listening = on;
          SIM.voice.next = Date.now() + 4000;
          audit("voice_listen", on ? "start" : "stop");
          return jsonRes({ ok: true, status: voiceStatus() });
        }
        case "/api/camera/options":
          Object.assign(DISP, body || {});
          if (D.live && D.live.camera) D.live.camera.options = DISP;
          return jsonRes({ ok: true, options: DISP });
        case "/api/consent": {
          var st = findStudent(body.student_id);
          if (st) st.consent = body.consent;
          var det = D.student[body.student_id];
          if (det && "consent" in det) det.consent = body.consent;
          audit("consent_set", body.student_id + "=" + body.consent);
          return jsonRes({ ok: true });
        }
        case "/api/students/create": {
          var sid = body.student_id || ("hs_web_" + (D.students.length + 1));
          var row = { student_id: sid, name: body.name || sid, birth_year: body.birth_year || null,
            gender: body.gender || null, consent: body.consent == null ? 1 : body.consent,
            sessions: 0, sei_mean: null };
          D.students.push(row);
          D.student[sid] = JSON.parse(JSON.stringify(D.student_empty));
          if (D.student[sid] && "student" in D.student[sid]) D.student[sid].student = row;
          syncOverview(); audit("student_create", sid);
          return jsonRes({ ok: true, student_id: sid });
        }
        case "/api/students/update": {
          var su = findStudent(body.student_id);
          if (su) Object.assign(su, body);
          audit("student_update", body.student_id);
          return jsonRes({ ok: true });
        }
        case "/api/students/delete": {
          D.students = D.students.filter(function (s) { return s.student_id !== body.student_id; });
          D.alerts = D.alerts.filter(function (a) { return a.student_id !== body.student_id; });
          delete D.student[body.student_id]; delete D.analysis[body.student_id];
          syncOverview(); audit("student_delete", body.student_id);
          return jsonRes({ ok: true });
        }
        case "/api/students/import": {
          var text = body.csv || body.text || "";
          var added = 0;
          text.split(/\r?\n/).slice(1).forEach(function (line) {
            if (!line.trim()) return;
            var c = line.split(",");
            if (!c[0]) return;
            if (!findStudent(c[0])) {
              D.students.push({ student_id: c[0], name: c[1] || c[0], birth_year: c[2] || null,
                gender: c[3] || null, consent: c[4] === "" ? 1 : parseInt(c[4], 10), sessions: 0, sei_mean: null });
              added++;
            }
          });
          syncOverview(); audit("students_import", "added=" + added);
          return jsonRes({ ok: true, added: added, updated: 0 });
        }
        case "/api/alerts/seen": {
          var al = D.alerts.find(function (a) { return a.alert_id === body.alert_id; });
          if (al) al.seen_at_ms = Date.now();
          syncOverview();
          return jsonRes({ ok: true });
        }
        case "/api/alerts/seen_all":
          D.alerts.forEach(function (a) { if (a.seen_at_ms == null) a.seen_at_ms = Date.now(); });
          syncOverview(); audit("alerts_seen_all");
          return jsonRes({ ok: true });
        case "/api/backup": {
          // A browser tab has no filesystem, so the demo snapshot lives in memory. Same field names as
          // backups_list()/do_backup() on the server, and the byte count is measured, not invented.
          var name = "focuschange_web_" + Date.now() + ".db";
          var snap = JSON.stringify(D);
          SNAPSHOTS[name] = snap;
          var bytes = new Blob([snap]).size;
          D.backups.unshift({ file: name, bytes: bytes, mtime_ms: Date.now() });
          audit("backup", name);
          return jsonRes({ ok: true, file: name, bytes: bytes });
        }
        case "/api/restore": {
          var file = (body && body.file) || "";
          var snap = SNAPSHOTS[file];
          var keep = D.backups;   // backup files sit outside the database, so a restore keeps them
          simStop();
          D = snap ? JSON.parse(snap) : fresh();
          D.backups = keep;
          syncOverview(); audit("restore", file);
          return jsonRes({ ok: true });
        }
        case "/api/demo/seed":
          simStop(); D = fresh(); syncOverview(); audit("demo_seed", "students=" + D.students.length);
          return jsonRes({ ok: true, students: D.students.length, sessions: (D.reports.session.summary || {}).sessions || 0, alerts: D.alerts.length });
        case "/api/demo/purge": {
          simStop();
          var purgedStudents = D.students.length, purgedBuckets = D.trend.length;
          D.students = []; D.alerts = []; D.trend = []; D.student = {}; D.analysis = {};
          D.reports = JSON.parse(JSON.stringify(D.reports_empty));
          D.demoOn = false; D.overview = emptyOverview();
          audit("demo_purge", "students=" + purgedStudents);
          return jsonRes({ ok: true, students: purgedStudents, trend_buckets: purgedBuckets });
        }
      }
      return jsonRes({ ok: false, error: "DEMO: không có tuyến " + path }, 404);
    }

    var origFetch = window.fetch.bind(window);
    window.fetch = function (input, opt) {
      var url = typeof input === "string" ? input : (input && input.url) || "";
      if (url.indexOf("/api/") === 0) {
        var u = new URL(url, location.origin);
        var method = (opt && opt.method) || "GET";
        var body = null;
        try { body = opt && opt.body ? JSON.parse(opt.body) : null; } catch (e) { body = null; }
        return Promise.resolve(route(method, u.pathname, u.searchParams, body));
      }
      return origFetch(input, opt);
    };
    // In bao cao: trang static khong co /api/reports/print -> mo blob HTML dung tu mock.
    var origOpen = window.open.bind(window);
    window.open = function (url) {
      if (typeof url === "string" && url.indexOf("/api/reports/print") === 0) {
        var u = new URL(url, location.origin);
        var blob = new Blob([printHtml(u.searchParams.get("scope"), u.searchParams.get("date"))], { type: "text/html" });
        return origOpen(URL.createObjectURL(blob), "_blank");
      }
      return origOpen.apply(null, arguments);
    };
    syncOverview();
  }
})();