/* FocusChangeAI - shared report rendering for the two in-browser bridges.
   The printed columns and the CSV quoting must stay identical to report_print_html() /
   report_csv() in dashboard/server.py, whether the rows came from the local Python server,
   from this machine's own SQLite, or from the labelled demo fixture. One copy, three callers.
   Exposes window.FC_REPORT. No DOM, no fetch, no state. */
(function () {
  "use strict";

  function esc(v) {
    return String(v == null ? "" : v).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  function csvCell(v) {
    var s = v == null ? "" : String(v);
    return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  }

  function p2(x) { return String(x).padStart(2, "0"); }
  function printTime(ms) {
    if (!ms) return "--";
    var d = new Date(ms);
    return p2(d.getHours()) + ":" + p2(d.getMinutes()) + " " + p2(d.getDate()) + "/" +
      p2(d.getMonth() + 1) + "/" + d.getFullYear();
  }
  function printMin(ms) { return ((ms || 0) / 60000).toFixed(1); }
  function printNum(v) { return v == null ? "--" : Number(v).toFixed(1); }
  function printQuality(r) {
    var q = (r.notes || {}).quality_mean;
    return q == null ? "--" : Number(q).toFixed(0) + "%";
  }

  var STUDENT_COLS = ["student_id", "name", "birth_year", "gender", "consent", "sessions", "sei_mean"];

  function studentsCsv(rows) {
    var lines = [STUDENT_COLS.join(",")];
    (rows || []).forEach(function (s) {
      lines.push(STUDENT_COLS.map(function (c) { return csvCell(s[c]); }).join(","));
    });
    return lines.join("\n") + "\n";
  }

  function reportsCsv(rows) {
    rows = rows || [];
    if (!rows.length) return "";
    var cols = Object.keys(rows[0]);
    var lines = [cols.join(",")];
    rows.forEach(function (r) {
      lines.push(cols.map(function (c) {
        var v = r[c];
        return csvCell(v && typeof v === "object" ? JSON.stringify(v) : v);
      }).join(","));
    });
    return lines.join("\n") + "\n";
  }

  // Same columns, same order as report_print_html() in dashboard/server.py.
  var PRINT_COLS = {
    session: [
      ["Học sinh", function (r) { return r.student_name || r.student_id || "--"; }],
      ["Bắt đầu", function (r) { return printTime(r.started_at_wall_ms); }],
      ["Kết thúc", function (r) { return printTime(r.ended_at_wall_ms); }],
      ["Phút hợp lệ", function (r) { return printMin(r.valid_ms); }],
      ["SEI trung bình", function (r) { return printNum(r.sei_mean); }],
      ["Cảnh báo", function (r) { return r.alerts == null ? 0 : r.alerts; }],
      ["Nguồn", function (r) { return r.source || "--"; }],
      ["Chất lượng đo", printQuality]],
    day: [
      ["Mã học sinh", function (r) { return r.student_id || "--"; }],
      ["Học sinh", function (r) { return r.name || "--"; }],
      ["Số phiên", function (r) { return r.sessions || 0; }],
      ["Phút hợp lệ", function (r) { return printMin(r.valid_ms); }],
      ["SEI trung bình", function (r) { return printNum(r.sei_mean); }],
      ["Cảnh báo", function (r) { return r.alerts == null ? 0 : r.alerts; }]],
    weekstu: [
      ["Mã học sinh", function (r) { return r.student_id || "--"; }],
      ["Học sinh", function (r) { return r.name || "--"; }],
      ["Số phiên", function (r) { return r.sessions || 0; }],
      ["Số ngày", function (r) { return r.days || 0; }],
      ["Phút hợp lệ", function (r) { return printMin(r.valid_ms); }],
      ["SEI trung bình", function (r) { return printNum(r.sei_mean); }],
      ["SEI thấp nhất", function (r) { return printNum(r.sei_low); }],
      ["Cảnh báo", function (r) { return r.alerts == null ? 0 : r.alerts; }]],
    week: [
      ["Ngày", function (r) { return r.date || "--"; }],
      ["Số phiên", function (r) { return r.sessions || 0; }],
      ["Phút hợp lệ", function (r) { return printMin(r.valid_ms); }],
      ["SEI trung bình", function (r) { return printNum(r.sei_mean); }],
      ["Cảnh báo", function (r) { return r.alerts == null ? 0 : r.alerts; }]]
  };

  function todayStr() {
    var d = new Date();
    return d.getFullYear() + "-" + p2(d.getMonth() + 1) + "-" + p2(d.getDate());
  }

  /* notice: the honesty line under the title. The caller owns it because only the caller knows
     where the rows came from. It is never optional - a printed report must name its source. */
  function printHtml(scope, date, data, notice) {
    var labels = { session: "Báo cáo phiên đo", day: "Báo cáo ngày", week: "Báo cáo tuần",
      weekstu: "Báo cáo tuần theo từng em" };
    var cols = PRINT_COLS[scope] || PRINT_COLS.session;
    var rows = (data && data.rows) || [];
    var s = (data && data.summary) || {};
    var html = "<!doctype html><html lang='vi'><head><meta charset='utf-8'><title>" +
      esc(labels[scope] || scope) + "</title>" +
      "<style>body{font-family:'Segoe UI',sans-serif;margin:32px;color:#16324f}h1{font-size:22px}" +
      "table{border-collapse:collapse;width:100%;margin-top:14px;font-size:13px}" +
      "th,td{border:1px solid #dbe7f4;padding:6px 9px;text-align:left}th{background:#eef4fb}" +
      ".muted{color:#5b7691;font-size:12px}button{margin:12px 0;padding:8px 16px}" +
      "@media print{button{display:none}}</style></head><body>" +
      "<h1>FocusChangeAI — " + esc(labels[scope] || scope) + " · " + esc(date || todayStr()) + "</h1>" +
      "<p class='muted'>" + esc(notice || "") + "</p>" +
      "<div class='muted'>" + (s.sessions || 0) + " phiên · " + (s.students || 0) + " học sinh · " +
      (s.valid_min == null ? "--" : Number(s.valid_min).toFixed(1)) + " phút hợp lệ · SEI trung bình " + printNum(s.sei_mean) +
      " · " + (s.alerts == null ? "--" : s.alerts) + " cảnh báo</div>" +
      "<button onclick='window.print()'>In / lưu PDF</button>";
    html += "<table><thead><tr>" + cols.map(function (c) { return "<th>" + esc(c[0]) + "</th>"; }).join("") +
      "</tr></thead><tbody>";
    if (rows.length) {
      html += rows.map(function (r) {
        return "<tr>" + cols.map(function (c) { return "<td>" + esc(c[1](r)) + "</td>"; }).join("") + "</tr>";
      }).join("");
    } else {
      html += "<tr><td colspan='" + cols.length + "' style='text-align:center;color:#5b7691'>" +
        "Chưa có phiên đo nào trong khoảng thời gian này.</td></tr>";
    }
    return html + "</tbody></table>" +
      "<p class='muted'>Số liệu chỉ để hỗ trợ giáo viên, không dùng làm căn cứ kỷ luật duy nhất.</p></body></html>";
  }

  /* ---- Tuần theo từng em: gộp nhiều phiên đo trong một tuần thành một dòng cho mỗi học sinh. ----
     Thuần dữ liệu, không DOM, không fetch: chạy giống nhau trên server Python, SQLite của trình duyệt
     và bộ mẫu. Không đo lại gì: mọi số lấy từ chính các dòng phiên đã lưu. */
  function dayOf(ms) {
    var d = new Date(ms);
    return d.getFullYear() + "-" + p2(d.getMonth() + 1) + "-" + p2(d.getDate());
  }

  /* Thứ Hai của tuần chứa dateStr (YYYY-MM-DD, giờ địa phương), cùng quy ước với tuyến /api/reports?scope=week. */
  function weekMonday(dateStr) {
    var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(dateStr || "");
    var d = m ? new Date(+m[1], +m[2] - 1, +m[3]) : new Date();
    d.setDate(d.getDate() - ((d.getDay() + 6) % 7));
    return d;
  }

  /* 7 ngày Thứ Hai → Chủ Nhật của tuần chứa dateStr, dạng YYYY-MM-DD. */
  function weekDates(dateStr) {
    var mon = weekMonday(dateStr), out = [];
    for (var i = 0; i < 7; i++) {
      var d = new Date(mon.getFullYear(), mon.getMonth(), mon.getDate() + i);
      out.push(d.getFullYear() + "-" + p2(d.getMonth() + 1) + "-" + p2(d.getDate()));
    }
    return out;
  }

  /* SEI của tuần là trung bình CÓ TRỌNG SỐ theo phút hợp lệ: một phiên 40 phút nặng hơn một phiên 3 phút.
     Trung bình các trung bình không trọng số sẽ để một phiên thử 2 phút kéo cả tuần của em xuống. Nếu mọi
     phiên có SEI đều 0 phút hợp lệ thì quay về trung bình thường, và nếu không phiên nào có SEI thì để null. */
  function weekByStudent(sessions) {
    var groups = {}, order = [];
    (sessions || []).forEach(function (s) {
      var id = s.student_id || "";
      var g = groups[id];
      if (!g) {
        g = groups[id] = { student_id: id, name: s.student_name || s.name || "", sessions: 0, days: {},
          valid_ms: 0, alerts: 0, not_eligible: 0, wsum: 0, wval: 0, plain: [], low: null, high: null,
          first_ms: null, last_ms: null };
        order.push(id);
      }
      g.sessions += 1;
      g.valid_ms += s.valid_ms || 0;
      g.alerts += s.alerts || 0;
      if (s.notes && s.notes.eligible === false) g.not_eligible += 1;
      var t = s.started_at_wall_ms;
      if (typeof t === "number") {
        g.days[dayOf(t)] = 1;
        if (g.first_ms === null || t < g.first_ms) g.first_ms = t;
        if (g.last_ms === null || t > g.last_ms) g.last_ms = t;
      }
      if (typeof s.sei_mean === "number" && isFinite(s.sei_mean)) {
        g.plain.push(s.sei_mean);
        g.wsum += s.sei_mean * (s.valid_ms || 0);
        g.wval += s.valid_ms || 0;
        g.low = g.low === null ? s.sei_mean : Math.min(g.low, s.sei_mean);
        g.high = g.high === null ? s.sei_mean : Math.max(g.high, s.sei_mean);
      }
    });
    return order.sort().map(function (id) {
      var g = groups[id], sei = null;
      if (g.plain.length) {
        sei = g.wval > 0 ? g.wsum / g.wval
          : g.plain.reduce(function (a, b) { return a + b; }, 0) / g.plain.length;
      }
      return { student_id: g.student_id, name: g.name, sessions: g.sessions, days: Object.keys(g.days).length,
        valid_ms: g.valid_ms, sei_mean: sei, sei_low: g.low, sei_high: g.high, alerts: g.alerts,
        not_eligible: g.not_eligible, first_at_ms: g.first_ms, last_at_ms: g.last_ms };
    });
  }

  /* Tóm tắt cả tuần từ các dòng phiên (cùng dạng với summary của /api/reports). */
  function weekSummary(sessions, rows) {
    var valid = 0, alerts = 0, wsum = 0, wval = 0, plain = [];
    (sessions || []).forEach(function (s) {
      valid += s.valid_ms || 0; alerts += s.alerts || 0;
      if (typeof s.sei_mean === "number" && isFinite(s.sei_mean)) {
        plain.push(s.sei_mean); wsum += s.sei_mean * (s.valid_ms || 0); wval += s.valid_ms || 0;
      }
    });
    return { sessions: (sessions || []).length, students: (rows || []).length,
      valid_min: Math.round(valid / 6000) / 10, alerts: alerts,
      sei_mean: !plain.length ? null : (wval > 0 ? wsum / wval
        : plain.reduce(function (a, b) { return a + b; }, 0) / plain.length) };
  }

  var WEEKSTU_COLS = ["student_id", "name", "sessions", "days", "valid_min", "sei_mean", "sei_low", "sei_high",
    "alerts", "not_eligible", "first_at", "last_at"];

  function isoOrEmpty(ms) { return typeof ms === "number" ? new Date(ms).toISOString() : ""; }

  /* CSV một dòng cho mỗi em. Ô chữ bắt đầu bằng = + - @ được thêm dấu nháy để Excel không chạy như công thức;
     nội dung chuỗi lấy qua csvCell nên dấu phẩy, ngoặc kép và xuống dòng trong tên vẫn đúng. */
  function weekStudentsCsv(rows) {
    var lines = [WEEKSTU_COLS.join(",")];
    (rows || []).forEach(function (r) {
      var cells = [r.student_id, r.name, r.sessions, r.days, Math.round((r.valid_ms || 0) / 600) / 100,
        r.sei_mean == null ? "" : Math.round(r.sei_mean * 10) / 10,
        r.sei_low == null ? "" : r.sei_low, r.sei_high == null ? "" : r.sei_high,
        r.alerts, r.not_eligible, isoOrEmpty(r.first_at_ms), isoOrEmpty(r.last_at_ms)];
      lines.push(cells.map(function (v) {
        if (typeof v === "string" && /^[=+\-@\t\r]/.test(v)) v = "'" + v;
        return csvCell(v);
      }).join(","));
    });
    return lines.join("\r\n") + "\r\n";
  }

  /* JSON của một báo cáo: vỏ có tên định dạng, ngày xuất và phạm vi để người đọc sau này biết đang cầm gì. */
  function reportJson(scope, date, data, extra) {
    var out = {
      format: "focuschange-report/1", scope: scope, date: date || todayStr(),
      exported_at_iso: new Date().toISOString(),
      summary: (data && data.summary) || {}, rows: (data && data.rows) || []
    };
    if (extra) Object.keys(extra).forEach(function (k) { out[k] = extra[k]; });
    out.note = "Số liệu chỉ để hỗ trợ giáo viên, không dùng làm căn cứ kỷ luật duy nhất.";
    return JSON.stringify(out, null, 2);
  }

  window.FC_REPORT = {
    weekDates: weekDates, weekMonday: weekMonday, weekByStudent: weekByStudent, weekSummary: weekSummary,
    weekStudentsCsv: weekStudentsCsv, reportJson: reportJson,
    esc: esc, csvCell: csvCell, todayStr: todayStr,
    printTime: printTime, printMin: printMin, printNum: printNum, printQuality: printQuality,
    STUDENT_COLS: STUDENT_COLS, studentsCsv: studentsCsv, reportsCsv: reportsCsv,
    PRINT_COLS: PRINT_COLS, printHtml: printHtml
  };
})();