/* FocusChangeAI - real-data bridge for the hosted dashboard.
   The measurement screen (../index.html) and the installed app already write the ten-table SQLite
   file: a browser tab keeps it in IndexedDB "focuschangeai-sql", the app keeps it at
   userData/focuschange.db through window.fcDb. This page is served from the same origin, so it can
   read that same file and answer the dashboard API with measured rows instead of a fixture.

   What it will NOT do: invent numbers. No demo students, no simulated camera, no synthetic SEI.
   Tables the web leg does not write (alerts, metrics, class_trend, student_consent) are reported as
   absent, and the screens say so. Writes are refused on purpose: the measurement screen holds the
   whole database in memory and saves it on every closed session, so a second writer here would be
   overwritten - or would overwrite a run in progress.

   Exposes window.FC_WEBDB.install() -> Promise<info>. Sets window.FC_WEBDB_INFO. */
(function () {
  "use strict";
  var R = window.FC_REPORT;
  var IDB_NAME = "focuschangeai-sql";
  var IDB_KEY = "focuschange.db";

  function idbOpen() {
    return new Promise(function (resolve, reject) {
      var r = indexedDB.open(IDB_NAME, 1);
      r.onupgradeneeded = function () { r.result.createObjectStore("db"); };
      r.onsuccess = function () { resolve(r.result); };
      r.onerror = function () { reject(r.error || new Error("IndexedDB")); };
    });
  }
  function idbLoad() {
    return idbOpen().then(function (d) {
      return new Promise(function (resolve, reject) {
        var q = d.transaction("db", "readonly").objectStore("db").get(IDB_KEY);
        q.onsuccess = function () { d.close(); resolve(q.result ? new Uint8Array(q.result) : null); };
        q.onerror = function () { d.close(); reject(q.error); };
      });
    });
  }
  function loadScript(src) {
    return new Promise(function (resolve, reject) {
      var s = document.createElement("script");
      s.src = src; s.onload = resolve;
      s.onerror = function () { reject(new Error("không nạp được " + src)); };
      document.head.appendChild(s);
    });
  }

  function round(v, d) {
    if (v == null || !isFinite(v)) return null;
    var p = Math.pow(10, d);
    return Math.round(v * p) / p;
  }
  function mean(vals) {
    var v = vals.filter(function (x) { return x != null && isFinite(x); });
    return v.length ? round(v.reduce(function (a, b) { return a + b; }, 0) / v.length, 2) : null;
  }
  function p2(x) { return String(x).padStart(2, "0"); }
  function dayStr(ms) {
    var d = new Date(ms);
    return d.getFullYear() + "-" + p2(d.getMonth() + 1) + "-" + p2(d.getDate());
  }
  function dayStart(dateStr) {
    var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateStr || "");
    if (!m) return null;
    return new Date(+m[1], +m[2] - 1, +m[3]).getTime();
  }
  function jsonRes(obj, code) {
    return new Response(JSON.stringify(obj), {
      status: code || 200, headers: { "Content-Type": "application/json; charset=utf-8" } });
  }
  function csvRes(text) {
    // BOM, like the server's utf-8-sig: without it Excel reads the Vietnamese columns as mojibake.
    return new Response("\uFEFF" + text, { status: 200, headers: { "Content-Type": "text/csv; charset=utf-8" } });
  }
  function refuse(what, where) {
    return { ok: false, error: what, hint: where };
  }

  var WRITE_NOTE = "API của trang báo cáo chỉ ĐỌC. Thêm/sửa em làm trong khung đo ở màn Camera " +
    "(nút Nhập danh sách) — khung đo giữ cả tệp db trong bộ nhớ và ghi lại sau mỗi phiên, nên ghi " +
    "qua API này sẽ bị đè hoặc đè mất phiên đang đo.";
  var NO_CAM = "Camera không chạy qua API của trang báo cáo. Bấm Bắt đầu ở màn Camera: khung đo " +
    "nhúng mở webcam máy này ngay trong dashboard.";
  // ../index.html is the measurement screen only when this dashboard is embedded one level below it.
  // A dashboard-only build (tools/build_site.py) sits at the root, where that path is this same page.
  var HAS_MEASURE = new URL("../index.html", location.href).href !==
    location.href.split("#")[0].split("?")[0];
  var MEASURE_HINT = HAS_MEASURE
    ? "Vào màn Camera của dashboard rồi bấm Bắt đầu để đo ngay tại đây."
    : "Bản dashboard-only không có màn đo đi kèm — chạy app PC hoặc bản web đầy đủ để đo.";

  var activeReload = null;
  function reload() {
    return activeReload ? activeReload() : Promise.resolve(window.FC_WEBDB_INFO || null);
  }

  function install() {
    var base = new URL("../sqljs/", location.href).href;
    var info = {
      ok: false, where: "", bytes: 0, tables: [], counts: {}, error: "", empty: false,
      hasConsent: false, hasMetrics: false, hasTrend: false, hasSeenAt: false, app: !!window.fcDb
    };
    var db = null;
    var auditLog = [];
    var exports = [];

    function q(sql, params) {
      if (!db) return [];
      var st = db.prepare(sql);
      try {
        if (params) st.bind(params);
        var out = [];
        while (st.step()) out.push(st.getAsObject());
        return out;
      } finally { st.free(); }
    }
    function one(sql, params) { return q(sql, params)[0] || null; }
    function has(t) { return info.tables.indexOf(t) >= 0; }
    function count(t) { return has(t) ? (one('SELECT COUNT(*) AS n FROM "' + t + '"') || {}).n || 0 : 0; }
    function audit(action, detail) {
      auditLog.unshift({ at_ms: Date.now(), actor: "teacher (web)", action: action, detail: detail || "" });
      auditLog = auditLog.slice(0, 60);
    }

    // The web leg names its features after habit.mjs HF_KEYS: pitch_deg / yaw_deg, not pitch / yaw.
    var F_EAR = "ear", F_MAR = "mar", F_SEI = "sei", F_PITCH = "pitch_deg";
    function featRow(row, name) {
      var f = (row || {})[name];
      return f == null ? null : f;
    }
    function sessionFeatures(sessionRow) {
      var out = {};
      q("SELECT * FROM feature_records WHERE session_row = ? ORDER BY feature_id", [sessionRow])
        .forEach(function (r) { out[r.feature] = r; });
      return out;
    }
    function parseNotes(s) {
      try { s.notes = JSON.parse(s.notes || "{}"); } catch (e) { s.notes = {}; }
      return s;
    }
    function seiOf(sessionRow) {
      var r = one("SELECT ROUND(AVG(mean), 1) AS v FROM feature_records WHERE session_row = ? AND feature = 'sei'",
        [sessionRow]);
      return r ? r.v : null;
    }
    function alertsOf(sessionRow) {
      if (!has("alerts")) return 0;
      return (one("SELECT COUNT(*) AS n FROM alerts WHERE session_row = ?", [sessionRow]) || {}).n || 0;
    }
    // The Python leg migrates alerts to add seen_at_ms (edge-server/database/app_tables.py); the web/app
    // SqlStore creates the base table, which only carries status. Naming the absent column throws and
    // takes the whole route down with it, so the "unseen" test follows the schema actually open.
    function unseenSql() { return info.hasSeenAt ? "seen_at_ms IS NULL" : "status <> 'seen'"; }
    function isSeen(a) { return info.hasSeenAt ? a.seen_at_ms != null : a.status === "seen"; }

    function classRow() {
      return one("SELECT class_id, name, school_year FROM classes ORDER BY created_at_ms LIMIT 1") ||
        { class_id: "lop12a", name: "Lớp học", school_year: "2026-2027" };
    }

    function studentsList() {
      var consentJoin = has("student_consent");
      var sql = "SELECT st.student_id, st.name, st.birth_year, st.gender," +
        (consentJoin ? " COALESCE(c.consent, 0) AS consent, c.note AS consent_note,"
                     : " 0 AS consent, NULL AS consent_note,") +
        " (SELECT COUNT(*) FROM observation_sessions s WHERE s.student_id = st.student_id) AS sessions," +
        " (SELECT ROUND(AVG(f.mean), 1) FROM feature_records f" +
        "   JOIN observation_sessions s ON s.session_row = f.session_row" +
        "   WHERE s.student_id = st.student_id AND f.feature = 'sei') AS sei_mean," +
        " (SELECT MAX(s.started_at_wall_ms) FROM observation_sessions s" +
        "   WHERE s.student_id = st.student_id) AS last_session_ms" +
        " FROM students st" + (consentJoin ? " LEFT JOIN student_consent c ON c.student_id = st.student_id" : "") +
        " ORDER BY st.student_id";
      return q(sql);
    }

    function studentDetail(sid) {
      var sessions = q(
        "SELECT s.*, (SELECT ROUND(AVG(f.mean), 1) FROM feature_records f" +
        "  WHERE f.session_row = s.session_row AND f.feature = 'sei') AS sei_mean" +
        " FROM observation_sessions s WHERE s.student_id = ?" +
        " ORDER BY s.started_at_wall_ms DESC LIMIT 60", [sid]);
      sessions.forEach(function (s) { parseNotes(s); s.features = sessionFeatures(s.session_row); });
      var base = one("SELECT * FROM baseline_profiles WHERE student_id = ? ORDER BY baseline_id DESC LIMIT 1", [sid]);
      var alerts = has("alerts")
        ? q("SELECT * FROM alerts WHERE student_id = ? ORDER BY raised_at_wall_ms DESC LIMIT 40", [sid]) : [];
      var consent = has("student_consent")
        ? one("SELECT * FROM student_consent WHERE student_id = ?", [sid]) : null;
      return { sessions: sessions, baseline: base, alerts: alerts, consent: consent || { consent: 0 } };
    }

    function analysis(sid, scope) {
      if (scope === "tiet") {
        // The per-minute series lives in the Python leg's metrics table. The web leg stores session
        // statistics only, so there is nothing to plot here - an empty chart, not a drawn curve.
        var ses = one("SELECT session_row, started_at_wall_ms FROM observation_sessions" +
          " WHERE student_id = ? ORDER BY started_at_wall_ms DESC LIMIT 1", [sid]);
        if (!ses || !has("metrics")) return { scope: scope, points: [], session: ses || null };
        return { scope: scope, points: q("SELECT at_ms, ear, mar, pitch, yaw, sei FROM metrics" +
          " WHERE session_row = ? ORDER BY at_ms", [ses.session_row]), session: ses };
      }
      var points = q(
        "SELECT s.session_row, s.started_at_wall_ms, s.valid_ms," +
        " MAX(CASE WHEN f.feature = 'sei' THEN f.mean END) AS sei," +
        " MAX(CASE WHEN f.feature = 'ear' THEN f.mean END) AS ear," +
        " MAX(CASE WHEN f.feature = 'mar' THEN f.mean END) AS mar" +
        " FROM observation_sessions s LEFT JOIN feature_records f ON f.session_row = s.session_row" +
        " WHERE s.student_id = ? GROUP BY s.session_row ORDER BY s.started_at_wall_ms", [sid]);
      return { scope: scope, points: points, session: null };
    }

    function averages() {
      var row = one("SELECT session_row FROM observation_sessions ORDER BY session_row DESC LIMIT 1");
      if (!row) {
        return { source: "none", label: "chưa có dữ liệu", ear: null, mar: null, angle: null, sei: null, n: 0 };
      }
      var f = sessionFeatures(row.session_row);
      return {
        source: "session", label: "phiên gần nhất",
        ear: featRow(f, F_EAR) == null ? null : f[F_EAR].mean,
        mar: featRow(f, F_MAR) == null ? null : f[F_MAR].mean,
        angle: featRow(f, F_PITCH) == null ? null : f[F_PITCH].mean,
        sei: featRow(f, F_SEI) == null ? null : f[F_SEI].mean, n: 1
      };
    }

    function overview(dateStr) {
      var cls = classRow();
      var date = dateStr || R.todayStr();
      var start = dayStart(date), end = start == null ? null : start + 86400000;
      var nStudents = one("SELECT COUNT(DISTINCT student_id) AS c FROM class_memberships" +
        " WHERE class_id = ? AND ended_on IS NULL", [cls.class_id]);
      nStudents = nStudents ? nStudents.c : 0;
      if (!nStudents) nStudents = count("students");
      var today = 0, unseen = 0;
      if (has("alerts") && start != null) {
        today = (one("SELECT COUNT(*) AS c FROM alerts WHERE raised_at_wall_ms >= ? AND raised_at_wall_ms < ?",
          [start, end]) || {}).c || 0;
        unseen = (one("SELECT COUNT(*) AS c FROM alerts WHERE " + unseenSql()) || {}).c || 0;
      }
      return {
        date: date, class_id: cls.class_id, class_label: cls.name, school_year: cls.school_year,
        students: nStudents,
        camera: { state: "off", note: "camera chạy ở màn đo, không ở trang này" },
        ai: { state: "ready", note: "Engine đo nằm ở màn đo — trang này đọc kết quả đã ghi" },
        alerts_today: today, alerts_unseen: unseen,
        averages: averages(),
        demo: false, web: true,
        web_info: { where: info.where, bytes: info.bytes, sessions: count("observation_sessions"),
                    students: count("students"), alerts_counted: has("alerts") && count("alerts") > 0 }
      };
    }

    function trend(minutes) {
      var since = Date.now() - (minutes || 30) * 60000;
      var rows = q(
        "SELECT s.started_at_wall_ms AS at_ms, s.student_id, s.source," +
        " MAX(CASE WHEN f.feature = 'sei' THEN f.mean END) AS sei," +
        " MAX(CASE WHEN f.feature = 'ear' THEN f.mean END) AS ear," +
        " MAX(CASE WHEN f.feature = 'mar' THEN f.mean END) AS mar," +
        " MAX(CASE WHEN f.feature = 'pitch_deg' THEN f.mean END) AS angle" +
        " FROM observation_sessions s LEFT JOIN feature_records f ON f.session_row = s.session_row" +
        " WHERE s.started_at_wall_ms >= ? GROUP BY s.session_row ORDER BY s.started_at_wall_ms", [since]);
      var buckets = {};
      rows.forEach(function (r) {
        if (r.at_ms == null) return;
        var b = Math.floor(r.at_ms / 60000) * 60000;
        var g = buckets[b] || (buckets[b] = { bucket_ms: b, seis: [], ears: [], mars: [], angles: [], ids: {}, source: r.source });
        g.seis.push(r.sei); g.ears.push(r.ear); g.mars.push(r.mar); g.angles.push(r.angle);
        g.ids[r.student_id] = 1;
      });
      return Object.keys(buckets).map(Number).sort(function (a, b) { return a - b; }).map(function (b) {
        var g = buckets[b];
        return { bucket_ms: b, mean_sei: mean(g.seis), mean_ear: mean(g.ears), mean_mar: mean(g.mars),
                 mean_angle: mean(g.angles), n_students: Object.keys(g.ids).length, source: g.source || "pc-web" };
      });
    }

    function reportRows(scope, dateStr) {
      var date = dateStr || R.todayStr();
      var start = dayStart(date);
      if (start == null) start = dayStart(R.todayStr());
      var end = start + 86400000;
      if (scope === "week") {
        var d = new Date(start);
        var wd = (d.getDay() + 6) % 7;           // Monday = 0, same as Python's tm_wday
        start -= wd * 86400000;
        end = start + 7 * 86400000;
      }
      var sessions = q(
        "SELECT s.*, st.name AS student_name," +
        " (SELECT ROUND(AVG(f.mean), 1) FROM feature_records f" +
        "  WHERE f.session_row = s.session_row AND f.feature = 'sei') AS sei_mean" +
        " FROM observation_sessions s JOIN students st ON st.student_id = s.student_id" +
        " WHERE s.started_at_wall_ms >= ? AND s.started_at_wall_ms < ?" +
        " ORDER BY s.started_at_wall_ms", [start, end]);
      sessions.forEach(function (s) { parseNotes(s); s.alerts = alertsOf(s.session_row); });
      var summary = {
        sessions: sessions.length,
        valid_min: round(sessions.reduce(function (a, s) { return a + (s.valid_ms || 0); }, 0) / 60000, 1),
        sei_mean: mean(sessions.map(function (s) { return s.sei_mean; })),
        alerts: sessions.reduce(function (a, s) { return a + (s.alerts || 0); }, 0),
        students: Object.keys(sessions.reduce(function (m, s) { m[s.student_id] = 1; return m; }, {})).length
      };
      if (scope === "session") return { scope: scope, rows: sessions, summary: summary };
      var groups = {};
      sessions.forEach(function (s) {
        var key = scope === "day" ? s.student_id : dayStr(s.started_at_wall_ms);
        var g = groups[key] || (groups[key] = scope === "day"
          ? { student_id: s.student_id, name: s.student_name, sessions: 0, valid_ms: 0, sei_vals: [], alerts: 0 }
          : { date: key, sessions: 0, valid_ms: 0, sei_vals: [], alerts: 0 });
        g.sessions += 1; g.valid_ms += s.valid_ms || 0; g.alerts += s.alerts || 0;
        if (s.sei_mean != null) g.sei_vals.push(s.sei_mean);
      });
      var out = Object.keys(groups).sort().map(function (k) {
        var g = groups[k];
        g.sei_mean = mean(g.sei_vals);
        delete g.sei_vals;
        return g;
      });
      return { scope: scope, rows: out, summary: summary };
    }

    function systemStatus() {
      return {
        app: info.app ? "App PC — đọc focuschange.db của app" : "Bản web — đọc SQLite của trình duyệt này",
        web: true, tier: null,
        deps: { mediapipe: false, ultralytics: false, cv2: false, yaml: false, PIL: false },
        model: { path: "", present: false },
        camera_running: false, camera_error: "", camera_cfg: {},
        database: { ready: !!db, empty: !!info.empty, path: info.where, bytes: info.bytes,
                    sessions: count("observation_sessions"), students: count("students") },
        network: "Offline theo đặc tả - chưa bật mạng lớp (giai đoạn 3)",
        firebase: "Không dùng (đã bỏ đám mây theo đặc tả)",
        version: "web-db-bridge", tiers: ["standard", "pro", "promax", "ultra"], gates: {},
        web_error: info.error
      };
    }

    function livePayload() {
      return {
        board: null, age_s: null, presence: [],
        camera: { running: false, error: "", people: 0, fps: 0, processing_ms: 0, started_ms: 0, frames: 0,
                  options: { boxes: true, overlay: false, brighten: false } },
        people: [], fps: 0, activity: ""
      };
    }

    function alertsList(params) {
      if (!has("alerts")) return [];
      var rows = q("SELECT a.*, st.name AS student_name FROM alerts a" +
        " LEFT JOIN students st ON st.student_id = a.student_id ORDER BY a.raised_at_wall_ms DESC LIMIT 200");
      return rows.filter(function (a) {
        var st = params.get("student"), kind = params.get("kind"), lv = params.get("level");
        var date = params.get("date"), unseen = params.get("unseen");
        if (st && a.student_id !== st && (a.student_name || "").indexOf(st) < 0) return false;
        if (kind && a.kind !== kind) return false;
        if (lv && String(a.level) !== lv) return false;
        if (date && dayStr(a.raised_at_wall_ms) !== date) return false;
        if (unseen && isSeen(a)) return false;
        return true;
      }).map(function (a) {
        if (!("seen_at_ms" in a)) a.seen_at_ms = a.status === "seen" ? a.raised_at_wall_ms : null;
        if (!("level" in a)) a.level = null;
        return a;
      });
    }

    function printNotice() {
      return "Số đo thật đọc từ " + (info.where || "SQLite của máy này") + " — " +
        count("observation_sessions") + " phiên, " + count("students") + " em. " +
        (has("alerts") && count("alerts") ? "" : "Bản web/app không ghi bảng cảnh báo nên cột Cảnh báo = 0 dòng trong db, không phải đo được 0 cảnh báo. ");
    }

    function downloadBytes(bytes, name) {
      var url = URL.createObjectURL(new Blob([bytes], { type: "application/x-sqlite3" }));
      var a = document.createElement("a");
      a.href = url; a.download = name;
      document.body.appendChild(a); a.click(); a.remove();
      setTimeout(function () { URL.revokeObjectURL(url); }, 20000);
    }

    async function route(method, path, params, body) {
      if (method === "GET") {
        switch (path) {
          case "/api/classes": return jsonRes(q("SELECT class_id, name, school_year, grade, created_at_ms FROM classes ORDER BY created_at_ms"));
          case "/api/overview": return jsonRes(overview(params.get("date")));
          case "/api/live": return jsonRes(livePayload());
          case "/api/trend": return jsonRes(trend(parseInt(params.get("minutes") || "30", 10)));
          case "/api/system": return jsonRes(systemStatus());
          case "/api/audit": return jsonRes(auditLog);
          case "/api/alerts": return jsonRes(alertsList(params));
          case "/api/settings": return jsonRes({ tier: null, language: "vi", theme: "light", readonly: true });
          case "/api/camera/options": return jsonRes({ boxes: true, overlay: false, brighten: false });
          case "/api/camera/status": return jsonRes({ running: false, error: NO_CAM, people: 0, fps: 0 });
          case "/api/camera/snapshot": return jsonRes({ ok: false, error: NO_CAM }, 503);
          case "/api/students": return jsonRes(studentsList());
          case "/api/student": return jsonRes(studentDetail(params.get("id")));
          case "/api/analysis": return jsonRes(analysis(params.get("id"), params.get("scope") || "buoi"));
          case "/api/reports": return jsonRes(reportRows(params.get("scope") || "session", params.get("date")));
          case "/api/backups": return jsonRes(exports);
          case "/api/students/csv": return csvRes(R.studentsCsv(studentsList()));
          case "/api/reports/csv":
            return csvRes(R.reportsCsv(reportRows(params.get("scope") || "session", params.get("date")).rows));
        }
        return jsonRes({ ok: false, error: "Bản web không có tuyến " + path }, 404);
      }
      switch (path) {
        case "/api/camera/start": {
          // Keep the handoff: if the Python dashboard is actually running on this machine, one click
          // goes straight to LIVE + autostart so the real camera comes up instead of a dead end.
          try {
            var ctl = new AbortController();
            var t = setTimeout(function () { ctl.abort(); }, 2000);
            var pr = await fetch("http://127.0.0.1:8730/api/system",
              { signal: ctl.signal, headers: { "Content-Type": "application/json" } });
            clearTimeout(t);
            var pj = await pr.json();
            if (pj && pj.app) {
              setTimeout(function () { location.href = location.pathname + "?mode=live&autostart=1"; }, 600);
              return jsonRes({ ok: false, error: "Đã thấy dashboard cục bộ — đang chuyển sang LIVE để bật camera thật…" });
            }
          } catch (e) { /* no local server: stay honest, do not simulate */ }
          return jsonRes(refuse(NO_CAM, MEASURE_HINT));
        }
        case "/api/camera/stop":
          return jsonRes(refuse("Trang này không chạy camera nên không có gì để dừng.", MEASURE_HINT));
        case "/api/backup": {
          if (!db || !info.bytes) {
            return jsonRes(refuse("Chưa có database để sao lưu — chưa có phiên đo nào được ghi trên máy này.",
              "Đo một phiên ở màn Camera rồi bấm Dừng."));
          }
          var bytes = db.export();
          var name = "focuschange_web_" + Date.now() + ".db";
          downloadBytes(bytes, name);
          exports.unshift({ file: name, bytes: bytes.length, mtime_ms: Date.now() });
          exports = exports.slice(0, 20);
          audit("backup", name + " (" + bytes.length + " B)");
          return jsonRes({ ok: true, file: name, bytes: bytes.length, downloaded: true });
        }
        case "/api/restore":
          return jsonRes(refuse("Bản web không khôi phục đè lên db của màn đo/app từ trang này.",
            "Khôi phục làm ở bản Python cục bộ (màn Sao lưu)."));
        case "/api/settings":
          return jsonRes(refuse("Bản web không có config.yaml để lưu. Ngưỡng đo chỉnh ở chính màn đo.",
            MEASURE_HINT));
        case "/api/camera/options":
          return jsonRes(refuse("Không có camera ở trang này để đặt tuỳ chọn.", MEASURE_HINT));
        case "/api/students/create":
        case "/api/students/update":
        case "/api/students/delete":
        case "/api/students/import":
          return jsonRes(refuse(WRITE_NOTE, MEASURE_HINT));
        case "/api/consent":
          return jsonRes(refuse("Bản web/app không có bảng student_consent — cột đồng ý chỉ có ở bản Python.",
            "Khai đồng ý ở bản Python cục bộ."));
        case "/api/alerts/seen":
        case "/api/alerts/seen_all":
          return jsonRes(refuse("Không có cảnh báo nào trong db của bản web để đánh dấu.", "Bản Python cục bộ đếm cảnh báo."));
        case "/api/demo/seed":
          return jsonRes(refuse("Bản này không nạp dữ liệu mẫu — chỉ đọc số đo thật.",
            "Muốn xem giao diện mẫu: mở thêm ?mode=demo vào địa chỉ."));
        case "/api/demo/purge":
          return jsonRes(refuse("Không có dữ liệu mẫu để xoá, và trang này không xoá dữ liệu thật.", ""));
      }
      return jsonRes({ ok: false, error: "Bản web không có tuyến " + path }, 404);
    }

    // sql.js is loaded once for the life of the page. The embedded measurement frame saves the whole
    // db after every closed session, so the reader has to be able to reopen the file without
    // re-wrapping fetch/window.open a second time (that would nest the interceptors).
    var SQLMod = null;

    function refreshInfo() {
      info.tables = q("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
        .map(function (r) { return r.name; });
      info.hasConsent = has("student_consent");
      info.hasMetrics = has("metrics");
      info.hasTrend = has("class_trend");
      info.hasSeenAt = has("alerts") &&
        q("PRAGMA table_info(alerts)").some(function (c) { return c.name === "seen_at_ms"; });
      info.counts = {};
      info.tables.forEach(function (t) { info.counts[t] = count(t); });
    }

    function emptyInfo() {
      if (db) { try { db.close(); } catch (e) { /* already closed */ } db = null; }
      info.ok = false; info.bytes = 0; info.tables = []; info.counts = {};
      // An empty store is not a broken one: the chip must say "Trống", not "Lỗi", or a teacher whose
      // first session has not been closed yet reads a red error on a perfectly healthy machine.
      info.empty = true;
      info.hasConsent = info.hasMetrics = info.hasTrend = info.hasSeenAt = false;
      info.where = window.fcDb ? "userData/focuschange.db (chưa có tệp)" : "IndexedDB của trình duyệt này (chưa có db)";
      info.error = "Chưa có phiên đo nào được ghi trên máy này.";
    }

    async function adopt(bytes) {
      if (!bytes || !bytes.length) { emptyInfo(); return info; }
      if (!SQLMod) {
        // Only fetched when there is actually a file to open: a visitor with no measured session
        // gets the honest empty state without downloading the 688 KB wasm first.
        await loadScript(base + "sql-wasm.js");
        SQLMod = await window.initSqlJs({ locateFile: function (f) { return base + f; } });
      }
      var next = new SQLMod.Database(bytes);
      if (db) { try { db.close(); } catch (e) { /* already closed */ } }
      db = next;
      info.bytes = bytes.length;
      info.error = "";
      info.empty = false;
      info.ok = true;
      refreshInfo();
      return info;
    }

    // Re-read the file the measurement frame just wrote. Deliberately NOT install(): the fetch and
    // window.open interceptors must stay wrapped exactly once.
    async function reload() {
      try {
        var bytes = null;
        if (window.fcDb) {
          bytes = await window.fcDb.load();
          info.where = await window.fcDb.path();
        } else {
          bytes = await idbLoad();
          info.where = "IndexedDB của trình duyệt này (focuschangeai-sql)";
        }
        await adopt(bytes);
      } catch (e) {
        info.error = String((e && e.message) || e);
        info.empty = false;
      }
      window.FC_WEBDB_INFO = info;
      return info;
    }
    activeReload = reload;

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
    var origOpen = window.open.bind(window);
    window.open = function (url) {
      if (typeof url === "string" && url.indexOf("/api/reports/print") === 0) {
        var u = new URL(url, location.origin);
        var scope = u.searchParams.get("scope") || "session";
        var date = u.searchParams.get("date");
        var html = R.printHtml(scope, date, reportRows(scope, date), printNotice());
        audit("report_print", scope + " " + (date || R.todayStr()));
        return origOpen(URL.createObjectURL(new Blob([html], { type: "text/html" })), "_blank");
      }
      return origOpen.apply(null, arguments);
    };

    return reload();
  }

  window.FC_WEBDB = { install: install, reload: reload };
})();