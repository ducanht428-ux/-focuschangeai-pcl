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
    var labels = { session: "Báo cáo phiên đo", day: "Báo cáo ngày", week: "Báo cáo tuần" };
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

  window.FC_REPORT = {
    esc: esc, csvCell: csvCell, todayStr: todayStr,
    printTime: printTime, printMin: printMin, printNum: printNum, printQuality: printQuality,
    STUDENT_COLS: STUDENT_COLS, studentsCsv: studentsCsv, reportsCsv: reportsCsv,
    PRINT_COLS: PRINT_COLS, printHtml: printHtml
  };
})();