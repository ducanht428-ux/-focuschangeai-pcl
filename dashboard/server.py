"""Máy chủ cục bộ của dashboard FocusChangeAI - chỉ thư viện chuẩn Python + PyYAML.

Vì sao là web cục bộ thay vì PySide6 (đặc tả mục 3 cho phép đổi nếu có lý do tốt hơn):
  * không phải cài thêm bộ Qt nặng trên máy lớp yếu;双击 Chay-Dashboard.bat là chạy;
  * chính giao diện này sau này là màn hình xem cho điện thoại trong mạng lớp (đặc tả 6.2),
    nên một bản UI phục vụ cả hai thay vì viết hai lần;
  * bám sát mẫu đã duyệt (thẻ bo góc, tông xanh) dễ hơn và kiểm chứng bằng ảnh chụp thật được.

Ranh giới dữ liệu: server KHÔNG đo gì cả. Nó đọc SQLite (10 bảng đo lường + bảng phụ trợ của
database/app_tables.py), đọc hai file live trong out/, và điều khiển luồng camera nền
(dashboard/camera_runner.py) vốn gọi đúng ClassroomPipeline của lõi. Mọi con số trên màn hình
đều có nguồn thật; chỗ chưa có dữ liệu thì nói rõ chứ không bịa.

Bảo mật đợt này: chỉ binds 127.0.0.1 (máy giáo viên), chưa đăng nhập/phân quyền - đó là giai
đoạn sau của đặc tả mục 9, ghi rõ trong báo cáo cuối đợt. Ghép nối điện thoại/PIN/TLS: giai đoạn 3.
"""
from __future__ import annotations

import csv
import io
import json
import os
import sqlite3
import sys
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO not in sys.path:
    sys.path.insert(0, REPO)

import yaml  # noqa: E402  (đã cài; cũng là thư viện lõi camera còn thiếu trước đây)

from database import focuschange_db as schema  # noqa: E402
from database import app_tables as app  # noqa: E402
from focusedge.local_store import resolve_db_path  # noqa: E402
from dashboard import camera_runner  # noqa: E402
from dashboard import demo_data  # noqa: E402

WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")
CONFIG_PATH = os.path.join(REPO, "config.yaml")
BACKUP_DIR = os.path.join(REPO, "backups")

# Mức độ cảnh báo 1-3 cho màn Cảnh báo (đặc tả 5.7). Loại buồn ngủ/PERCLOS là mức 3 vì đó là
# dấu hiệu nguy hiểm nhất khi đang học; nhóm nhắc nhở nhẹ là mức 1.
KIND_LEVEL = {
    "DROWSY": 3, "PERCLOS_HIGH": 3,
    "LOOK_AWAY": 2, "VISUAL_DRIFT": 2, "YAWN": 2, "TOO_CLOSE": 2, "LOW_VS_PERSONAL": 2,
    "LOW_BLINK": 1, "SQUINT": 1, "ASYMMETRY": 1, "INCOMPLETE_BLINK": 1, "HIGH_BLINK": 1,
    "BREAK": 1, "CONFUSED": 1, "NOISY": 1, "UNRELIABLE": 1,
}

SETTINGS_KEYS = set(app.DEFAULT_SETTINGS) | {"camera_source", "camera_resolution", "camera_faces"}


# --------------------------------------------------------------------------- tiện ích
def load_cfg() -> dict:
    with io.open(CONFIG_PATH, encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def save_cfg(cfg: dict) -> None:
    with io.open(CONFIG_PATH, "w", encoding="utf-8") as f:
        yaml.safe_dump(cfg, f, allow_unicode=True, sort_keys=False)


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(resolve_db_path(load_cfg().get("storage", {}).get("db", "")), timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")   # đặc tả mục 8: WAL cho ghi đọc song song
    schema.init_db(conn)
    app.init_app_tables(conn)
    return conn


def rows(cur) -> list:
    return [dict(r) for r in cur.fetchall()]


def day_range(date_str: str):
    """[start, end) mili-giây của một ngày dương lịch THEO GIỜ MÁY - cùng luật với lõi."""
    t = time.strptime(date_str, "%Y-%m-%d")
    start = int(time.mktime(t) * 1000)
    return start, start + 86_400_000


def today_str() -> str:
    return time.strftime("%Y-%m-%d")


def _mean(vals):
    vals = [v for v in vals if v is not None]
    return round(sum(vals) / len(vals), 2) if vals else None


# --------------------------------------------------------------------------- đọc: tổng quan
def overview(conn, cfg, runner, date_str: str) -> dict:
    class_id = cfg.get("class_id", "class")
    start, end = day_range(date_str)
    n_students = conn.execute(
        "SELECT COUNT(DISTINCT student_id) c FROM class_memberships"
        " WHERE class_id = ? AND ended_on IS NULL", (class_id,)).fetchone()["c"]
    alerts_today = conn.execute(
        "SELECT COUNT(*) c FROM alerts WHERE raised_at_wall_ms >= ? AND raised_at_wall_ms < ?",
        (start, end)).fetchone()["c"]
    unseen = conn.execute("SELECT COUNT(*) c FROM alerts WHERE seen_at_ms IS NULL").fetchone()["c"]
    live = live_payload(cfg, runner)
    running = runner.running()
    miss = camera_runner.missing_deps()
    if running:
        ai_state, ai_note = "active", "Đang hoạt động"
    elif miss:
        ai_state, ai_note = "missing", "Thiếu: %s" % ", ".join(miss)
    else:
        ai_state, ai_note = "ready", "Sẵn sàng (chưa bật camera)"
    if running:
        cam_state, cam_note = "on", "1/1 đang kết nối"
    elif live["age_s"] is not None and live["age_s"] < 90:
        cam_state, cam_note = "live", "Đang nhận chỉ số (file live)"
    else:
        cam_state, cam_note = "off", "0/1 chưa chạy"
    return {
        "date": date_str,
        "class_id": class_id,
        "class_label": cfg.get("class_label") or class_id,
        "school_year": cfg.get("storage", {}).get("school_year", ""),
        "students": n_students,
        "camera": {"state": cam_state, "note": cam_note},
        "ai": {"state": ai_state, "note": ai_note},
        "alerts_today": alerts_today,
        "alerts_unseen": unseen,
        "averages": class_averages(conn, cfg, runner, live),
        "demo": demo_data.is_demo_present(conn),
    }


def class_averages(conn, cfg, runner, live) -> dict:
    """EAR / MAR / góc đầu / mức chú ý của lớp. Ưu tiên số đang đo, rồi file live, rồi phiên gần nhất.

    Mỗi nguồn gắn nhãn riêng để màn hình không bao giờ gọi số của phiên hôm qua là "hiện tại".
    """
    if runner.running():
        _, info = runner.snapshot()
        people = [p for p in info["people"] if p["measurable"]]
        if people:
            return {"source": "live", "label": "hiện tại",
                    "ear": _mean([p["ear"] for p in people]),
                    "mar": _mean([p["mar"] for p in people]),
                    "angle": _mean([abs(p["pitch"]) if p["pitch"] is not None else None
                                    for p in people]),
                    "sei": _mean([p["sei"] for p in people]),
                    "n": len(people)}
    students = (live.get("board") or {}).get("students") or {}
    seis = [s.get("sei") for s in students.values() if s.get("sei") is not None]
    if live["age_s"] is not None and live["age_s"] < 90 and seis:
        return {"source": "file", "label": "hiện tại (file live %ds)" % live["age_s"],
                "ear": None, "mar": None, "angle": None, "sei": _mean(seis), "n": len(seis)}
    row = conn.execute(
        "SELECT s.session_row FROM observation_sessions s ORDER BY s.session_row DESC LIMIT 1"
    ).fetchone()
    if row:
        feats = {r["feature"]: r["mean"] for r in conn.execute(
            "SELECT feature, mean FROM feature_records WHERE session_row = ?", (row["session_row"],))}
        return {"source": "session", "label": "phiên gần nhất",
                "ear": feats.get("ear"), "mar": feats.get("mar"),
                "angle": feats.get("pitch"), "sei": feats.get("sei"), "n": 1}
    return {"source": "none", "label": "chưa có dữ liệu", "ear": None, "mar": None,
            "angle": None, "sei": None, "n": 0}


def live_payload(cfg, runner) -> dict:
    out_dir = os.path.join(REPO, (cfg.get("local_output") or {}).get("dir", "out"))
    class_id = cfg.get("class_id", "class")
    path = os.path.join(out_dir, "class_live_%s.json" % class_id)
    board, age = None, None
    if os.path.exists(path):
        try:
            with io.open(path, encoding="utf-8") as f:
                board = json.load(f)
            age = int(time.time() - os.path.getmtime(path))
        except Exception:
            board, age = None, None
    presence = []
    if os.path.isdir(out_dir):
        for name in sorted(os.listdir(out_dir)):
            if name.startswith("presence_") and name.endswith(".json"):
                try:
                    with io.open(os.path.join(out_dir, name), encoding="utf-8") as f:
                        presence.append(json.load(f))
                except Exception:
                    pass
    _, info = runner.snapshot()
    return {"board": board, "age_s": age, "presence": presence,
            "camera": runner.status(), "people": info["people"],
            "fps": round(info["fps"], 1), "activity": info["activity"]}


def trend(conn, minutes: int = 30) -> list:
    since = int(time.time() * 1000) - minutes * 60_000
    return rows(conn.execute(
        "SELECT bucket_ms, mean_sei, mean_ear, mean_mar, mean_angle, n_students, source"
        " FROM class_trend WHERE bucket_ms >= ? ORDER BY bucket_ms", (since,)))


def system_status(cfg, runner) -> dict:
    import importlib.util
    from focusedge.classroom import resolve_asset
    deps = {}
    for mod in ("mediapipe", "ultralytics", "cv2", "yaml", "PIL"):
        deps[mod] = importlib.util.find_spec(mod) is not None
    model = resolve_asset(cfg.get("face_landmarker_task", "face_landmarker.task"))
    return {
        "app": "Đang hoạt động",
        "deps": deps,
        "model": {"path": model, "present": os.path.exists(model)},
        "camera_running": runner.running(),
        "camera_error": runner.error,
        # Giá trị camera đang nằm trong config.yaml, để màn Camera hiện đúng thứ sẽ chạy
        # lần bật tới thay vì đoán mò. Ghi thì qua POST /api/settings (body.camera).
        "camera_cfg": {
            "sensitivity": (cfg.get("person") or {}).get("sensitivity", 1),
            "max_active_faces": (cfg.get("scheduler") or {}).get("max_active_faces", 10),
            "process_width": (cfg.get("input") or {}).get("process_width", 640),
            "detect_fps": (cfg.get("detection") or {}).get("detect_fps", 8),
        },
        "database": {"path": resolve_db_path(cfg.get("storage", {}).get("db", "")), "ready": True},
        "network": "Offline theo đặc tả - chưa bật mạng lớp (giai đoạn 3)",
        "firebase": "Không dùng (đã bỏ đám mây theo đặc tả)",
        "tier": None,  # handler điền từ settings
        "version": "dashboard-0.1.0",
    }


# --------------------------------------------------------------------------- đọc: học sinh
def students_list(conn, cfg) -> list:
    class_id = cfg.get("class_id", "class")
    return rows(conn.execute(
        "SELECT st.student_id, st.name, st.birth_year, st.gender,"
        " COALESCE(c.consent, 0) AS consent, c.note AS consent_note,"
        " (SELECT COUNT(*) FROM observation_sessions s WHERE s.student_id = st.student_id) AS sessions,"
        " (SELECT ROUND(AVG(f.mean), 1) FROM feature_records f"
        "   JOIN observation_sessions s ON s.session_row = f.session_row"
        "   WHERE s.student_id = st.student_id AND f.feature = 'sei') AS sei_mean,"
        " (SELECT MAX(s.started_at_wall_ms) FROM observation_sessions s"
        "   WHERE s.student_id = st.student_id) AS last_session_ms"
        " FROM students st LEFT JOIN student_consent c ON c.student_id = st.student_id"
        " ORDER BY st.student_id"))


def student_detail(conn, sid: str) -> dict:
    sessions = rows(conn.execute(
        "SELECT s.*, (SELECT ROUND(AVG(f.mean), 1) FROM feature_records f"
        "  WHERE f.session_row = s.session_row AND f.feature = 'sei') AS sei_mean"
        " FROM observation_sessions s WHERE s.student_id = ?"
        " ORDER BY s.started_at_wall_ms DESC LIMIT 60", (sid,)))
    for s in sessions:
        s["features"] = {r["feature"]: r for r in rows(conn.execute(
            "SELECT * FROM feature_records WHERE session_row = ?", (s["session_row"],)))}
        s["notes"] = json.loads(s["notes"] or "{}")
    base = conn.execute(
        "SELECT * FROM baseline_profiles WHERE student_id = ? ORDER BY baseline_id DESC LIMIT 1",
        (sid,)).fetchone()
    alerts = rows(conn.execute(
        "SELECT * FROM alerts WHERE student_id = ? ORDER BY raised_at_wall_ms DESC LIMIT 40", (sid,)))
    consent = conn.execute("SELECT * FROM student_consent WHERE student_id = ?", (sid,)).fetchone()
    return {"sessions": sessions,
            "baseline": dict(base) if base else None,
            "alerts": alerts,
            "consent": dict(consent) if consent else {"consent": 0}}


def analysis(conn, sid: str, scope: str) -> dict:
    """Biểu đồ theo buổi (mỗi phiên một điểm) hoặc theo tiết (chuỗi phút của phiên gần nhất)."""
    if scope == "tiet":
        ses = conn.execute(
            "SELECT session_row, started_at_wall_ms FROM observation_sessions"
            " WHERE student_id = ? ORDER BY started_at_wall_ms DESC LIMIT 1", (sid,)).fetchone()
        if not ses:
            return {"scope": scope, "points": [], "session": None}
        points = rows(conn.execute(
            "SELECT at_ms, ear, mar, pitch, yaw, sei FROM metrics"
            " WHERE session_row = ? ORDER BY at_ms", (ses["session_row"],)))
        return {"scope": scope, "points": points, "session": dict(ses)}
    points = rows(conn.execute(
        "SELECT s.session_row, s.started_at_wall_ms, s.valid_ms,"
        " MAX(CASE WHEN f.feature = 'sei' THEN f.mean END) AS sei,"
        " MAX(CASE WHEN f.feature = 'ear' THEN f.mean END) AS ear,"
        " MAX(CASE WHEN f.feature = 'mar' THEN f.mean END) AS mar"
        " FROM observation_sessions s LEFT JOIN feature_records f ON f.session_row = s.session_row"
        " WHERE s.student_id = ? GROUP BY s.session_row ORDER BY s.started_at_wall_ms", (sid,)))
    return {"scope": scope, "points": points, "session": None}


# --------------------------------------------------------------------------- đọc: cảnh báo
def alerts_list(conn, q: dict) -> list:
    sql = ("SELECT a.*, st.name AS student_name FROM alerts a"
           " JOIN students st ON st.student_id = a.student_id WHERE 1 = 1")
    args = []
    if q.get("student"):
        sql += " AND a.student_id = ?"
        args.append(q["student"])
    if q.get("kind"):
        sql += " AND a.kind = ?"
        args.append(q["kind"])
    if q.get("unseen") == "1":
        sql += " AND a.seen_at_ms IS NULL"
    if q.get("date"):
        start, end = day_range(q["date"])
        sql += " AND a.raised_at_wall_ms >= ? AND a.raised_at_wall_ms < ?"
        args += [start, end]
    sql += " ORDER BY a.raised_at_wall_ms DESC LIMIT 300"
    out = rows(conn.execute(sql, args))
    for a in out:
        a["level"] = KIND_LEVEL.get(a["kind"], 1)
    if q.get("level"):
        out = [a for a in out if a["level"] == int(q["level"])]
    return out


# --------------------------------------------------------------------------- đọc: báo cáo
def report_rows(conn, cfg, scope: str, date_str: str) -> dict:
    start, end = day_range(date_str)
    if scope == "week":
        t = time.strptime(date_str, "%Y-%m-%d")
        monday = time.mktime(t) - t.tm_wday * 86400
        start = int(monday * 1000)
        end = start + 7 * 86_400_000
    sessions = rows(conn.execute(
        "SELECT s.*, st.name AS student_name,"
        " (SELECT ROUND(AVG(f.mean), 1) FROM feature_records f"
        "  WHERE f.session_row = s.session_row AND f.feature = 'sei') AS sei_mean,"
        " (SELECT COUNT(*) FROM alerts a WHERE a.session_row = s.session_row) AS alerts"
        " FROM observation_sessions s JOIN students st ON st.student_id = s.student_id"
        " WHERE s.started_at_wall_ms >= ? AND s.started_at_wall_ms < ?"
        " ORDER BY s.started_at_wall_ms", (start, end)))
    for s in sessions:
        s["notes"] = json.loads(s["notes"] or "{}")
    if scope == "session":
        return {"scope": scope, "rows": sessions,
                "summary": _report_summary(sessions)}
    if scope == "day":
        by_student = {}
        for s in sessions:
            g = by_student.setdefault(s["student_id"], {
                "student_id": s["student_id"], "name": s["student_name"],
                "sessions": 0, "valid_ms": 0, "sei_vals": [], "alerts": 0})
            g["sessions"] += 1
            g["valid_ms"] += s["valid_ms"]
            if s["sei_mean"] is not None:
                g["sei_vals"].append(s["sei_mean"])
            g["alerts"] += s["alerts"]
        out = []
        for g in by_student.values():
            g["sei_mean"] = _mean(g["sei_vals"])
            g.pop("sei_vals")
            out.append(g)
        out.sort(key=lambda g: g["student_id"])
        return {"scope": scope, "rows": out, "summary": _report_summary(sessions)}
    by_day = {}
    for s in sessions:
        day = time.strftime("%Y-%m-%d", time.localtime(s["started_at_wall_ms"] / 1000.0))
        g = by_day.setdefault(day, {"date": day, "sessions": 0, "valid_ms": 0,
                                    "sei_vals": [], "alerts": 0})
        g["sessions"] += 1
        g["valid_ms"] += s["valid_ms"]
        if s["sei_mean"] is not None:
            g["sei_vals"].append(s["sei_mean"])
        g["alerts"] += s["alerts"]
    out = []
    for day in sorted(by_day):
        g = by_day[day]
        g["sei_mean"] = _mean(g["sei_vals"])
        g.pop("sei_vals")
        out.append(g)
    return {"scope": scope, "rows": out, "summary": _report_summary(sessions)}


def _report_summary(sessions) -> dict:
    return {"sessions": len(sessions),
            "valid_min": round(sum(s["valid_ms"] for s in sessions) / 60000.0, 1),
            "sei_mean": _mean([s["sei_mean"] for s in sessions]),
            "alerts": sum(s["alerts"] for s in sessions),
            "students": len({s["student_id"] for s in sessions})}


def report_csv(conn, cfg, scope, date_str) -> str:
    data = report_rows(conn, cfg, scope, date_str)
    buf = io.StringIO()
    if not data["rows"]:
        return ""
    writer = csv.DictWriter(buf, fieldnames=list(data["rows"][0].keys()), extrasaction="ignore")
    writer.writeheader()
    for r in data["rows"]:
        writer.writerow({k: (json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list)) else v)
                         for k, v in r.items()})
    return buf.getvalue()


def report_print_html(conn, cfg, scope, date_str) -> str:
    data = report_rows(conn, cfg, scope, date_str)
    titles = {"session": "Báo cáo phiên đo", "day": "Báo cáo ngày", "week": "Báo cáo tuần"}
    head = "".join("<th>%s</th>" % k for k in (data["rows"][0].keys() if data["rows"] else ["(trống)"]))
    body = ""
    for r in data["rows"]:
        body += "<tr>" + "".join(
            "<td>%s</td>" % (json.dumps(v, ensure_ascii=False) if isinstance(v, (dict, list))
                             else ("--" if v is None else v)) for v in r.values()) + "</tr>"
    s = data["summary"]
    return ("<!doctype html><html lang='vi'><head><meta charset='utf-8'>"
            "<title>%s %s</title><style>body{font-family:'Segoe UI',sans-serif;margin:24px;color:#123}"
            "table{border-collapse:collapse;width:100%%;font-size:12px}td,th{border:1px solid #9ab;padding:4px 6px;text-align:left}"
            "th{background:#dce9f7}.sum{margin:12px 0;font-size:14px}button{margin:12px 0;padding:8px 16px}"
            "@media print{button{display:none}}</style></head><body>"
            "<h2>%s - %s</h2><div class='sum'>Lớp %s · %s · %d phiên · %.1f phút hợp lệ · SEI trung bình %s · %d cảnh báo</div>"
            "<button onclick='window.print()'>In / lưu PDF</button>"
            "<table><thead><tr>%s</tr></thead><tbody>%s</tbody></table>"
            "<p style='font-size:11px;color:#567'>Số liệu chỉ để hỗ trợ giáo viên, không dùng làm căn cứ kỷ luật duy nhất.</p>"
            "</body></html>") % (titles[scope], date_str, titles[scope], date_str,
                               cfg.get("class_label") or cfg.get("class_id", ""),
                               cfg.get("storage", {}).get("school_year", ""),
                               s["sessions"], s["valid_min"],
                               s["sei_mean"] if s["sei_mean"] is not None else "--", s["alerts"],
                               head, body)


# --------------------------------------------------------------------------- ghi: học sinh
def create_student(conn, cfg, body) -> dict:
    sid = (body.get("student_id") or "").strip()
    name = (body.get("name") or "").strip()
    if not sid or not name:
        return {"ok": False, "error": "Cần cả mã học sinh và tên"}
    now = int(time.time() * 1000)
    try:
        conn.execute("INSERT INTO students (student_id, name, birth_year, gender, created_at_ms)"
                     " VALUES (?, ?, ?, ?, ?)",
                     (sid, name, body.get("birth_year"), body.get("gender"), now))
        conn.execute("INSERT INTO classes (class_id, name, school_year, created_at_ms)"
                     " VALUES (?, ?, ?, ?) ON CONFLICT(class_id) DO NOTHING",
                     (cfg.get("class_id", "class"), cfg.get("class_label") or cfg.get("class_id", "class"),
                      cfg.get("storage", {}).get("school_year", "2026-2027"), now))
        conn.execute("INSERT INTO class_memberships (student_id, class_id, school_year, started_on, note)"
                     " VALUES (?, ?, ?, ?, ?)",
                     (sid, cfg.get("class_id", "class"), cfg.get("storage", {}).get("school_year", "2026-2027"),
                      time.strftime("%Y-%m-%d"), "dashboard"))
        conn.execute("INSERT OR IGNORE INTO student_consent (student_id, consent, updated_ms, note)"
                     " VALUES (?, 0, ?, ?)", (sid, now, "chưa xin đồng ý"))
        conn.commit()
    except sqlite3.IntegrityError as e:
        conn.rollback()
        return {"ok": False, "error": "Mã học sinh đã tồn tại hoặc dữ liệu không hợp lệ (%s)" % e}
    app.audit(conn, "teacher", "student_create", sid)
    return {"ok": True}


def update_student(conn, body) -> dict:
    sid = body.get("student_id")
    if not sid:
        return {"ok": False, "error": "Thiếu mã học sinh"}
    conn.execute("UPDATE students SET name = COALESCE(?, name), birth_year = COALESCE(?, birth_year),"
                 " gender = COALESCE(?, gender) WHERE student_id = ?",
                 (body.get("name"), body.get("birth_year"), body.get("gender"), sid))
    conn.commit()
    app.audit(conn, "teacher", "student_update", sid)
    return {"ok": True}


def delete_student(conn, sid: str) -> dict:
    """Xóa dây chuyền mọi dữ liệu của một em (đặc tả 8 + 10: chuyển trường / yêu cầu xóa).

    Thứ tự xóa theo khoá ngoại: phiên trước (cascade kéo features/alerts/metrics/ai/review),
    rồi baseline, membership, consent, cuối cùng là dòng học sinh. Một giao dịch duy nhất.
    """
    try:
        conn.execute("DELETE FROM observation_sessions WHERE student_id = ?", (sid,))
        conn.execute("DELETE FROM baseline_profiles WHERE student_id = ?", (sid,))
        conn.execute("DELETE FROM class_memberships WHERE student_id = ?", (sid,))
        conn.execute("DELETE FROM student_consent WHERE student_id = ?", (sid,))
        conn.execute("DELETE FROM metrics WHERE student_id = ?", (sid,))
        conn.execute("DELETE FROM students WHERE student_id = ?", (sid,))
        conn.commit()
    except Exception as e:
        conn.rollback()
        return {"ok": False, "error": str(e)}
    _drop_roster_template(sid)
    app.audit(conn, "teacher", "student_delete", sid)
    return {"ok": True}


def set_consent(conn, sid: str, consent: int, note: str = "") -> dict:
    now = int(time.time() * 1000)
    conn.execute("INSERT INTO student_consent (student_id, consent, updated_ms, note)"
                 " VALUES (?, ?, ?, ?) ON CONFLICT(student_id) DO UPDATE SET"
                 " consent = excluded.consent, updated_ms = excluded.updated_ms, note = excluded.note",
                 (sid, 1 if consent else 0, now, note))
    conn.commit()
    if not consent:
        # Rút đồng ý là xóa vector mặt NGAY (đặc tả 2 + 5.5), không chờ đợt dọn sau.
        _drop_roster_template(sid)
    app.audit(conn, "teacher", "consent_set", "%s=%d" % (sid, consent))
    return {"ok": True}


def _drop_roster_template(sid: str) -> None:
    path = os.path.join(REPO, "roster.json")
    if not os.path.exists(path):
        return
    try:
        with io.open(path, encoding="utf-8") as f:
            data = json.load(f)
        entries = data.get("students") or data.get("entries") or []
        kept = [e for e in entries if e.get("student_id") != sid]
        if len(kept) != len(entries):
            if "students" in data:
                data["students"] = kept
            else:
                data["entries"] = kept
            with io.open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
    except Exception:
        pass


def import_students_csv(conn, cfg, text: str) -> dict:
    reader = csv.DictReader(io.StringIO(text))
    added = updated = 0
    for row in reader:
        sid = (row.get("student_id") or "").strip()
        name = (row.get("name") or "").strip()
        if not sid or not name:
            continue
        exists = conn.execute("SELECT 1 FROM students WHERE student_id = ?", (sid,)).fetchone()
        if exists:
            conn.execute("UPDATE students SET name = ? WHERE student_id = ?", (name, sid))
            updated += 1
        else:
            res = create_student(conn, cfg, {"student_id": sid, "name": name,
                                             "birth_year": row.get("birth_year") or None,
                                             "gender": row.get("gender") or None})
            if res.get("ok"):
                added += 1
    conn.commit()
    app.audit(conn, "teacher", "students_import", "added=%d updated=%d" % (added, updated))
    return {"ok": True, "added": added, "updated": updated}


def students_csv(conn) -> str:
    buf = io.StringIO()
    writer = csv.writer(buf)
    writer.writerow(["student_id", "name", "birth_year", "gender", "consent", "sessions", "sei_mean"])
    for r in students_list(conn, load_cfg()):
        writer.writerow([r["student_id"], r["name"], r["birth_year"] or "", r["gender"] or "",
                         r["consent"], r["sessions"], r["sei_mean"] if r["sei_mean"] is not None else ""])
    return buf.getvalue()


# --------------------------------------------------------------------------- sao lưu / lưu trữ
def backups_list() -> list:
    if not os.path.isdir(BACKUP_DIR):
        return []
    out = []
    for name in sorted(os.listdir(BACKUP_DIR), reverse=True):
        if name.endswith(".db"):
            p = os.path.join(BACKUP_DIR, name)
            out.append({"file": name, "bytes": os.path.getsize(p),
                        "mtime_ms": int(os.path.getmtime(p) * 1000)})
    return out


def do_backup(conn) -> dict:
    os.makedirs(BACKUP_DIR, exist_ok=True)
    name = "focuschange_%s.db" % time.strftime("%Y%m%d_%H%M%S")
    dest_path = os.path.join(BACKUP_DIR, name)
    dest = sqlite3.connect(dest_path)
    try:
        conn.backup(dest)   # API backup của SQLite, an toàn cả khi đang ghi (đặc tả 8)
    finally:
        dest.close()
    app.audit(conn, "teacher", "backup", name)
    return {"ok": True, "file": name, "bytes": os.path.getsize(dest_path)}


def do_restore(conn, runner, name: str) -> dict:
    path = os.path.join(BACKUP_DIR, os.path.basename(name))
    if not os.path.exists(path):
        return {"ok": False, "error": "Không thấy file sao lưu"}
    src = sqlite3.connect(path)
    try:
        tables = {r[0] for r in src.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {"students", "observation_sessions"} <= tables:
            return {"ok": False, "error": "File không phải database FocusChangeAI"}
        runner.stop()   # không khôi phục đè lên luồng đang đo
        src.backup(conn)
        schema.init_db(conn)
        app.init_app_tables(conn)
    finally:
        src.close()
    app.audit(conn, "teacher", "restore", name)
    return {"ok": True}


def retention_purge(conn, days: int) -> int:
    cutoff = int(time.time() * 1000) - days * 86_400_000
    cur = conn.execute("DELETE FROM observation_sessions WHERE started_at_wall_ms < ?", (cutoff,))
    conn.execute("DELETE FROM class_trend WHERE bucket_ms < ?", (cutoff,))
    conn.commit()
    n = cur.rowcount
    if n:
        app.audit(conn, "system", "retention_purge", "sessions=%d days=%d" % (n, days))
    return n


# --------------------------------------------------------------------------- HTTP handler
class Handler(BaseHTTPRequestHandler):
    server_version = "FocusChangeDashboard/0.1"
    runner: camera_runner.CameraRunner = None   # main() gán trước khi serve

    def log_message(self, fmt, *args):
        pass   # console ASCII và yên tĩnh; lỗi thật đã in ở chỗ khác

    # CORS cho bản web hosted (trang https://*.qoder.website gọi về máy này). Server chỉ
    # bind 127.0.0.1 nên không lộ ra mạng; chỉ chấp nhận Origin là site Qoder hoặc trang
    # cục bộ (127.0.0.1/localhost) để một trang web lạ bất kỳ không đọc trộm số liệu học
    # sinh qua trình duyệt của người dùng. Origin do trình duyệt đặt, trang remote không
    # giả mạo được thành loopback.
    def end_headers(self):
        origin = self.headers.get("Origin") or ""
        if (origin.endswith(".qoder.website") or origin.startswith("http://127.0.0.1:")
                or origin.startswith("http://localhost:")):
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
            # Chrome chặn trang public (https) gọi về máy cục bộ nếu preflight thiếu
            # header Private Network Access này; thêm vào để bản web LIVE đọc được máy.
            self.send_header("Access-Control-Allow-Private-Network", "true")
        super().end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()

    # ------------------------------------------------------------- tiện ích trả lời
    def _json(self, obj, code=200):
        payload = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(payload)

    def _html(self, text, code=200):
        payload = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _csv(self, text, filename):
        payload = text.encode("utf-8-sig")   # BOM để Excel mở tiếng Việt không lỗi font
        self.send_response(200)
        self.send_header("Content-Type", "text/csv; charset=utf-8")
        self.send_header("Content-Disposition", "attachment; filename=%s" % filename)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return {}

    def _static(self, name):
        types = {".html": "text/html; charset=utf-8", ".css": "text/css; charset=utf-8",
                 ".js": "application/javascript; charset=utf-8", ".svg": "image/svg+xml"}
        path = os.path.join(WEB_DIR, name)
        if not os.path.isfile(path):
            self._json({"error": "not found"}, 404)
            return
        with open(path, "rb") as f:
            payload = f.read()
        self.send_response(200)
        self.send_header("Content-Type", types.get(os.path.splitext(name)[1], "application/octet-stream"))
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    # ------------------------------------------------------------- GET
    def do_GET(self):
        url = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        path = url.path
        if path in ("/", "/index.html"):
            return self._static("index.html")
        if path in ("/app.css", "/app.js"):
            return self._static(path[1:])
        if not path.startswith("/api/"):
            return self._json({"error": "not found"}, 404)
        try:
            return self._api_get(path, q)
        except Exception as e:
            return self._json({"ok": False, "error": "%s: %s" % (type(e).__name__, e)}, 500)

    def _api_get(self, path, q):
        cfg = load_cfg()
        if path == "/api/camera/stream":
            return self._mjpeg()
        if path == "/api/camera/snapshot":
            # Một JPEG duy nhất cho trình duyệt bị chặn luồng MJPEG (watchStream trong
            # app.js sẽ kéo đường này bằng fetch). CORS + PNA do end_headers() lo.
            jpeg, _ = self.runner.snapshot()
            if not jpeg:
                return self._json({"error": "chua co khung hinh"}, 404)
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(jpeg)))
            self.end_headers()
            self.wfile.write(jpeg)
            return
        conn = db()
        try:
            if path == "/api/overview":
                return self._json(overview(conn, cfg, self.runner, q.get("date") or today_str()))
            if path == "/api/live":
                return self._json(live_payload(cfg, self.runner))
            if path == "/api/trend":
                return self._json(trend(conn, int(q.get("minutes", 30))))
            if path == "/api/students":
                return self._json(students_list(conn, cfg))
            if path == "/api/student":
                return self._json(student_detail(conn, q.get("id", "")))
            if path == "/api/analysis":
                return self._json(analysis(conn, q.get("id", ""), q.get("scope", "buoi")))
            if path == "/api/alerts":
                return self._json(alerts_list(conn, q))
            if path == "/api/reports":
                return self._json(report_rows(conn, cfg, q.get("scope", "session"),
                                              q.get("date") or today_str()))
            if path == "/api/reports/print":
                return self._html(report_print_html(conn, cfg, q.get("scope", "session"),
                                                    q.get("date") or today_str()))
            if path == "/api/reports/csv":
                tier = app.get_setting(conn, "tier", "standard")
                if not app.tier_allows(tier, "csv_export"):
                    return self._json({"ok": False, "error": "Xuất CSV cần bậc Pro trở lên (đổi trong Cài đặt)"}, 403)
                return self._csv(report_csv(conn, cfg, q.get("scope", "session"),
                                            q.get("date") or today_str()), "bao_cao.csv")
            if path == "/api/students/csv":
                tier = app.get_setting(conn, "tier", "standard")
                if not app.tier_allows(tier, "csv_export"):
                    return self._json({"ok": False, "error": "Xuất CSV cần bậc Pro trở lên (đổi trong Cài đặt)"}, 403)
                return self._csv(students_csv(conn), "hoc_sinh.csv")
            if path == "/api/settings":
                return self._json({r["key"]: r["value"] for r in conn.execute("SELECT * FROM settings")})
            if path == "/api/system":
                st = system_status(cfg, self.runner)
                st["tier"] = app.get_setting(conn, "tier", "standard")
                st["tiers"] = list(app.TIERS)
                st["gates"] = app.TIER_GATES
                return self._json(st)
            if path == "/api/backups":
                return self._json(backups_list())
            if path == "/api/audit":
                return self._json(rows(conn.execute(
                    "SELECT * FROM audit_log ORDER BY audit_id DESC LIMIT 80")))
            if path == "/api/camera/status":
                return self._json(self.runner.status())
            if path == "/api/classes":
                return self._json(rows(conn.execute("SELECT * FROM classes ORDER BY class_id")))
            return self._json({"error": "not found"}, 404)
        finally:
            conn.close()

    def _mjpeg(self):
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            while True:
                jpeg, _ = self.runner.snapshot()
                if jpeg:
                    self.wfile.write(b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: "
                                     + str(len(jpeg)).encode() + b"\r\n\r\n" + jpeg + b"\r\n")
                time.sleep(0.1)
        except Exception:
            return   # trình duyệt đóng tab / dừng stream: kết thúc lặng lẽ

    # ------------------------------------------------------------- POST
    def do_POST(self):
        url = urlparse(self.path)
        path = url.path
        body = self._body()
        try:
            return self._api_post(path, body)
        except Exception as e:
            return self._json({"ok": False, "error": "%s: %s" % (type(e).__name__, e)}, 500)

    def _api_post(self, path, body):
        cfg = load_cfg()
        conn = db()
        try:
            if path == "/api/students/create":
                return self._json(create_student(conn, cfg, body))
            if path == "/api/students/update":
                return self._json(update_student(conn, body))
            if path == "/api/students/delete":
                return self._json(delete_student(conn, body.get("student_id", "")))
            if path == "/api/students/import":
                return self._json(import_students_csv(conn, cfg, body.get("csv", "")))
            if path == "/api/consent":
                return self._json(set_consent(conn, body.get("student_id", ""),
                                              int(body.get("consent", 0)), body.get("note", "")))
            if path == "/api/alerts/seen":
                conn.execute("UPDATE alerts SET seen_at_ms = ? WHERE alert_id = ?",
                             (int(time.time() * 1000), int(body.get("alert_id", 0))))
                conn.commit()
                return self._json({"ok": True})
            if path == "/api/alerts/seen_all":
                conn.execute("UPDATE alerts SET seen_at_ms = ? WHERE seen_at_ms IS NULL",
                             (int(time.time() * 1000),))
                conn.commit()
                app.audit(conn, "teacher", "alerts_seen_all")
                return self._json({"ok": True})
            if path == "/api/settings":
                return self._json(self._save_settings(conn, cfg, body))
            if path == "/api/backup":
                return self._json(do_backup(conn))
            if path == "/api/restore":
                return self._json(do_restore(conn, self.runner, body.get("file", "")))
            if path == "/api/camera/start":
                res = self.runner.start(cfg)
                if res.get("ok"):
                    conn.execute("INSERT INTO cameras (camera_id, kind, label, status, last_seen_ms)"
                                 " VALUES ('pc-webcam', 'webcam', 'Webcam máy này', 'running', ?)"
                                 " ON CONFLICT(camera_id) DO UPDATE SET status='running', last_seen_ms=excluded.last_seen_ms",
                                 (int(time.time() * 1000),))
                    conn.commit()
                    app.audit(conn, "teacher", "camera_start")
                return self._json(res)
            if path == "/api/camera/stop":
                res = self.runner.stop()
                conn.execute("UPDATE cameras SET status='stopped', last_seen_ms=? WHERE camera_id='pc-webcam'",
                             (int(time.time() * 1000),))
                conn.commit()
                app.audit(conn, "teacher", "camera_stop")
                return self._json(res)
            if path == "/api/camera/options":
                for key in ("boxes", "overlay", "brighten"):
                    if key in body:
                        self.runner.options[key] = bool(body[key])
                return self._json({"ok": True, "options": self.runner.options})
            if path == "/api/camera/bind":
                # Nút "Gắn tên" trên thẻ người: khai báo tên như phím M của main.py,
                # identity_conf "manual". Có tên thì lõi mới mở phiên và ghi SQLite.
                return self._json(self.runner.bind(int(body.get("track_id", -1)),
                                                   str(body.get("name", ""))))
            if path == "/api/camera/unbind":
                return self._json(self.runner.unbind(int(body.get("track_id", -1))))
            if path == "/api/demo/seed":
                res = demo_data.seed(conn, cfg)
                app.audit(conn, "teacher", "demo_seed", json.dumps(res))
                return self._json(res)
            if path == "/api/demo/purge":
                res = demo_data.purge(conn)
                app.audit(conn, "teacher", "demo_purge", json.dumps(res))
                return self._json(res)
            return self._json({"error": "not found"}, 404)
        finally:
            conn.close()

    def _save_settings(self, conn, cfg, body):
        changed = []
        for key, value in body.items():
            if key not in SETTINGS_KEYS:
                continue
            value = str(value)
            if key == "tier" and value not in app.TIERS:
                return {"ok": False, "error": "Bậc không hợp lệ"}
            if key == "retention_days":
                try:
                    if int(value) < 1:
                        raise ValueError
                except ValueError:
                    return {"ok": False, "error": "Số ngày lưu phải >= 1"}
            if key == "auto_backup" and value == "1":
                tier = app.get_setting(conn, "tier", "standard")
                if not app.tier_allows(tier, "auto_backup"):
                    return {"ok": False, "error": "Sao lưu tự động cần bậc Pro trở lên"}
            app.set_setting(conn, key, value)
            changed.append(key)
        # Vài cài đặt là của lõi camera: ghi thẳng vào config.yaml để lần chạy sau giữ nguyên.
        cam = body.get("camera") or {}
        if cam:
            if "sensitivity" in cam:
                cfg.setdefault("person", {})["sensitivity"] = int(cam["sensitivity"])
            if "max_active_faces" in cam:
                cfg.setdefault("scheduler", {})["max_active_faces"] = int(cam["max_active_faces"])
            if "process_width" in cam:
                cfg.setdefault("input", {})["process_width"] = int(cam["process_width"])
            if "detect_fps" in cam:
                cfg.setdefault("detection", {})["detect_fps"] = int(cam["detect_fps"])
            save_cfg(cfg)
            changed.append("config.yaml")
        if "retention_days" in changed:
            retention_purge(conn, int(app.get_setting(conn, "retention_days", "180")))
        app.audit(conn, "teacher", "settings_save", ",".join(changed))
        return {"ok": True, "changed": changed}


def _dashboard_alive(url):
    """True neu cong dang nghe la chinh dashboard nay, khong phai ung dung khac.
    GET /api/system tra JSON co key 'app' chi khi la server nay; qua han 2s thi coi nhu khong."""
    import urllib.request
    try:
        with urllib.request.urlopen(url + "/api/system", timeout=2) as r:
            data = json.loads(r.read().decode("utf-8"))
        return isinstance(data, dict) and "app" in data
    except Exception:
        return False


def main():
    import argparse
    ap = argparse.ArgumentParser(description="Dashboard FocusChangeAI (web cuc bo)")
    ap.add_argument("--port", type=int, default=8730)
    ap.add_argument("--no-open", action="store_true", help="khong tu mo trinh duyet")
    args = ap.parse_args()

    conn = db()
    days = int(app.get_setting(conn, "retention_days", "180"))
    removed = retention_purge(conn, days)
    conn.close()

    def on_minute(bucket_ms, means):
        c = db()
        try:
            c.execute("INSERT INTO class_trend (bucket_ms, mean_sei, mean_ear, mean_mar, mean_angle,"
                      " n_students, source) VALUES (?, ?, ?, ?, ?, ?, 'live')"
                      " ON CONFLICT(bucket_ms) DO UPDATE SET mean_sei=excluded.mean_sei,"
                      " mean_ear=excluded.mean_ear, mean_mar=excluded.mean_mar,"
                      " mean_angle=excluded.mean_angle, n_students=excluded.n_students, source='live'",
                      (bucket_ms, means.get("sei"), means.get("ear"), means.get("mar"),
                       means.get("angle"), means.get("n", 0)))
            c.commit()
        finally:
            c.close()

    url = "http://127.0.0.1:%d" % args.port
    # Hoi "dashboard nay dang chay chua?" TRUOC khi bind. Tren Windows http.server dat
    # SO_REUSEADDR nen bind lan 2 van thanh cong (khong nem OSError) -> hai server cung
    # nghe mot cong, instance sau nuot instance truoc. Bat buoc phai do bang GET /api/system
    # truoc; neu dung la minh thi chi mo trinh duyet roi thoat (bam shortcut lan 2 van thay
    # giao dien ngay, khong traceback, khong server ma).
    if _dashboard_alive(url):
        print("dashboard: %s dang chay san, mo trinh duyet" % url)
        if not args.no_open:
            webbrowser.open(url)
        return
    Handler.runner = camera_runner.CameraRunner(on_minute=on_minute)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print("dashboard: %s  (db: %s)" % (url, resolve_db_path(load_cfg().get("storage", {}).get("db", ""))))
    print("retention: %d ngay, da don %d phien qua han" % (days, removed))
    print("nhan Ctrl-C de dung")
    if not args.no_open:
        webbrowser.open(url)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        Handler.runner.stop()
        server.server_close()
    print("dashboard stopped")


if __name__ == "__main__":
    main()