"""Kiểm thử tự chạy cho tầng dashboard (server + app_tables + demo_data), thư viện chuẩn.

Chạy:  python tests/test_dashboard.py
Không đụng database thật: mọi kiểm thử dùng file SQLite tạm và thư mục backups tạm,
 nên chạy bao nhiêu lần cũng không đổi dữ liệu lớp đang thu.

Phủ các yêu cầu nghiệm thu của đặc tả mục 14 cho phần giao diện/dữ liệu:
  * migration cộng thêm idempotent, cột alerts.seen_at_ms, settings mặc định;
  * cổng bậc sản phẩm (tier_allows) đúng thứ tự standard<pro<promax<ultra;
  * dữ liệu mẫu có nhãn: nạp/xoá không đụng dữ liệu thật;
  * số liệu tổng quan / báo cáo / CSV khớp nhau;
  * sao lưu + khôi phục tròn vẹn (API backup của SQLite);
  * dọn dữ liệu quá hạn (retention);
  * đánh dấu cảnh báo đã xem;
  * xoá học sinh dây chuyền mọi bảng liên quan.
"""
from __future__ import annotations

import os
import shutil
import sqlite3
import sys
import tempfile
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if REPO not in sys.path:
    sys.path.insert(0, REPO)

from database import focuschange_db as schema
from database import app_tables as app
from dashboard import server
from dashboard import demo_data

PASS = 0
FAIL = 0
OPEN = []   # các connection đang mở, để đóng hết trước khi dọn thư mục tạm (Windows giữ lock)


def check(name, cond, extra=""):
    global PASS, FAIL
    if cond:
        PASS += 1
        print("  PASS  %s" % name)
    else:
        FAIL += 1
        print("  FAIL  %s  %s" % (name, extra))


class FakeRunner:
    """Runner giả: không camera, không thư viện nặng - đủ cho các hàm đọc của server."""
    error = ""

    def running(self):
        return False

    def snapshot(self):
        return None, {"people": [], "fps": 0.0, "processing_ms": 0.0,
                      "activity": "", "started_ms": 0, "frames": 0}

    def status(self):
        return {"running": False, "error": "", "people": 0, "fps": 0.0,
                "processing_ms": 0.0, "started_ms": 0, "frames": 0,
                "options": {"boxes": True, "overlay": False, "brighten": False}}

    def stop(self):
        return {"ok": True}


def new_conn(path):
    conn = sqlite3.connect(path)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    schema.init_db(conn)
    app.init_app_tables(conn)
    OPEN.append(conn)
    return conn


def cfg_for(tmp):
    return {"class_id": "testclass", "class_label": "Lớp test",
            "storage": {"school_year": "2026-2027", "db": ""},
            "local_output": {"dir": os.path.join(tmp, "out")}}


def add_real_student(conn, cfg, sid="hs_real_01", name="HS Thật"):
    now = int(time.time() * 1000)
    conn.execute("INSERT INTO students (student_id,name,created_at_ms) VALUES (?,?,?)", (sid, name, now))
    conn.execute("INSERT INTO classes (class_id,name,school_year,created_at_ms) VALUES (?,?,?,?)",
                 (cfg["class_id"], cfg["class_label"], "2026-2027", now))
    conn.execute("INSERT INTO class_memberships (student_id,class_id,school_year,started_on) VALUES (?,?,?,?)",
                 (sid, cfg["class_id"], "2026-2027", time.strftime("%Y-%m-%d")))
    cur = conn.execute(
        "INSERT INTO observation_sessions (student_id,class_id,source,started_at_wall_ms,ended_at_wall_ms,"
        "valid_ms,device,app_version,notes) VALUES (?,?,?,?,?,?,?,?,?)",
        (sid, cfg["class_id"], "edge-camera", now - 3600_000, now, 1800_000, "test", "test", "{}"))
    row = cur.lastrowid
    conn.execute("INSERT INTO feature_records (session_row,feature,mean,std,n,extractor) VALUES (?,?,?,?,?,?)",
                 (row, "sei", 70.0, 5.0, 100, "test"))
    conn.execute("INSERT INTO alerts (session_row,student_id,kind,message,raised_at_wall_ms) VALUES (?,?,?,?,?)",
                 (row, sid, "LOOK_AWAY", "nhìn đi chỗ khác", now - 1800_000))
    conn.commit()
    return row


# --------------------------------------------------------------------------- các kiểm thử
def test_migration(tmp):
    print("[migration]")
    conn = new_conn(os.path.join(tmp, "m.db"))
    v1 = conn.execute("PRAGMA user_version").fetchone()[0]
    app.init_app_tables(conn)   # gọi lại lần 2 phải an toàn
    app.init_app_tables(conn)   # lần 3
    v2 = conn.execute("PRAGMA user_version").fetchone()[0]
    check("user_version = 1 và idempotent", v1 == 1 and v2 == 1, "v1=%s v2=%s" % (v1, v2))
    tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    check("đủ bảng phụ trợ", set(app.APP_TABLES) <= tables, str(sorted(tables)))
    cols = [r[1] for r in conn.execute("PRAGMA table_info(alerts)")]
    check("alerts có seen_at_ms", "seen_at_ms" in cols)
    check("settings mặc định đủ", app.get_setting(conn, "tier") == "standard"
          and app.get_setting(conn, "retention_days") == "180")
    # schema gốc không bị đổi tên/đổi kiểu
    check("10 bảng gốc còn nguyên", set(schema.TABLES) <= tables)
    conn.close()


def test_tier_gates(tmp):
    print("[tier gates]")
    check("standard chặn csv", not app.tier_allows("standard", "csv_export"))
    check("pro mở csv", app.tier_allows("pro", "csv_export"))
    check("pro chặn multi_class", not app.tier_allows("pro", "multi_class"))
    check("promax mở multi_class", app.tier_allows("promax", "multi_class"))
    check("promax chặn predict", not app.tier_allows("promax", "predict"))
    check("ultra mở predict", app.tier_allows("ultra", "predict"))
    check("bậc lạ coi như standard", not app.tier_allows("khong_hop_le", "csv_export"))
    check("tính năng không cổng luôn mở", app.tier_allows("standard", "khong_co_trong_gates"))
    check("standard mở sao lưu tự động", app.tier_allows("standard", "auto_backup"))


def test_demo_isolation(tmp):
    print("[demo isolation]")
    conn = new_conn(os.path.join(tmp, "d.db"))
    cfg = cfg_for(tmp)
    add_real_student(conn, cfg)
    conn.execute("INSERT INTO class_trend (bucket_ms,mean_sei,n_students,source) VALUES (?,?,?,?)",
                 (int(time.time() * 1000) // 60000 * 60000, 80.0, 1, "live"))
    conn.commit()
    demo_data.seed(conn, cfg)
    check("demo hiện diện", demo_data.is_demo_present(conn))
    check("dữ liệu thật còn khi có demo",
          conn.execute("SELECT COUNT(*) c FROM students WHERE student_id='hs_real_01'").fetchone()["c"] == 1)
    demo_data.purge(conn)
    check("demo sạch sau purge", not demo_data.is_demo_present(conn))
    check("thật còn sau purge",
          conn.execute("SELECT COUNT(*) c FROM students WHERE student_id='hs_real_01'").fetchone()["c"] == 1)
    check("phiên thật còn sau purge",
          conn.execute("SELECT COUNT(*) c FROM observation_sessions WHERE student_id='hs_real_01'").fetchone()["c"] == 1)
    check("bucket live còn, demo mất",
          conn.execute("SELECT COUNT(*) c FROM class_trend WHERE source='live'").fetchone()["c"] == 1
          and conn.execute("SELECT COUNT(*) c FROM class_trend WHERE source='demo'").fetchone()["c"] == 0)
    conn.close()


def test_overview_reports(tmp):
    print("[overview / reports / csv]")
    conn = new_conn(os.path.join(tmp, "o.db"))
    cfg = cfg_for(tmp)
    demo_data.seed(conn, cfg)
    runner = FakeRunner()
    today = time.strftime("%Y-%m-%d")
    ov = server.overview(conn, cfg, runner, today)
    check("overview đếm 5 em demo", ov["students"] == 5, str(ov["students"]))
    check("overview có cảnh báo hôm nay", ov["alerts_today"] > 0, str(ov["alerts_today"]))
    check("averages nguồn phiên (chưa camera)", ov["averages"]["source"] in ("session", "none"))
    ses = server.report_rows(conn, cfg, "session", today)
    day = server.report_rows(conn, cfg, "day", today)
    week = server.report_rows(conn, cfg, "week", today)
    check("report session có dòng", len(ses["rows"]) > 0)
    check("summary session khớp số dòng", ses["summary"]["sessions"] == len(ses["rows"]))
    check("report day gom theo em", len(day["rows"]) == 5, str(len(day["rows"])))
    check("report week >= day", week["summary"]["sessions"] >= day["summary"]["sessions"])
    csv_text = server.report_csv(conn, cfg, "session", today)
    check("csv có header + dòng", csv_text.count("\n") >= len(ses["rows"]))
    check("csv chứa mã demo", "hs_demo_" in csv_text)
    conn.close()


def test_backup_restore(tmp):
    print("[backup / restore]")
    conn = new_conn(os.path.join(tmp, "b.db"))
    cfg = cfg_for(tmp)
    add_real_student(conn, cfg)
    old_backup_dir = server.BACKUP_DIR
    server.BACKUP_DIR = os.path.join(tmp, "backups")
    try:
        res = server.do_backup(conn)
        check("backup tạo file", res["ok"] and os.path.exists(os.path.join(server.BACKUP_DIR, res["file"])))
        # phá dữ liệu hiện tại rồi khôi phục (xoá dây chuyền đúng thứ tự khoá ngoại)
        server.delete_student(conn, "hs_real_01")
        check("đã xoá sạch trước restore",
              conn.execute("SELECT COUNT(*) c FROM students").fetchone()["c"] == 0)
        r2 = server.do_restore(conn, FakeRunner(), res["file"])
        check("restore ok", r2["ok"])
        check("restore trả lại học sinh",
              conn.execute("SELECT COUNT(*) c FROM students WHERE student_id='hs_real_01'").fetchone()["c"] == 1)
        check("restore trả lại phiên",
              conn.execute("SELECT COUNT(*) c FROM observation_sessions").fetchone()["c"] == 1)
        bad = server.do_restore(conn, FakeRunner(), "khong_ton_tai.db")
        check("restore file lạ báo lỗi", not bad["ok"])
    finally:
        server.BACKUP_DIR = old_backup_dir
    conn.close()


def test_auto_backup(tmp):
    print("[auto backup]")
    conn = new_conn(os.path.join(tmp, "ab.db"))
    cfg = cfg_for(tmp)
    add_real_student(conn, cfg)
    old_backup_dir = server.BACKUP_DIR
    server.BACKUP_DIR = os.path.join(tmp, "backups_auto")
    try:
        # standard lưu được auto_backup=1 (không còn cổng Pro). _save_settings không dùng self.
        r = server.Handler._save_settings(None, conn, cfg, {"auto_backup": "1"})
        check("standard bật được sao lưu tự động", r["ok"], str(r))
        r1 = server.auto_backup_tick(conn)
        check("tick đầu chạy và tạo file auto_", r1["ran"] and r1["file"].startswith("auto_"), str(r1))
        check("file auto_ nằm trên đĩa", os.path.exists(os.path.join(server.BACKUP_DIR, r1["file"])))
        r2 = server.auto_backup_tick(conn)
        check("tick liền sau không chạy lại (24h)", not r2["ran"] and r2["reason"] == "fresh", str(r2))
        # bản sao lưu tay + bản auto cũ: prune chỉ đụng auto_
        manual = server.do_backup(conn)
        old_auto = os.path.join(server.BACKUP_DIR, "auto_20200101_000000.db")
        shutil.copyfile(os.path.join(server.BACKUP_DIR, r1["file"]), old_auto)
        # đổi tên bản đầu sang một tên cũ khác: cùng một giây thì tên mới trùng tên cũ
        older_auto = os.path.join(server.BACKUP_DIR, "auto_20200102_000000.db")
        os.replace(os.path.join(server.BACKUP_DIR, r1["file"]), older_auto)
        old_t = time.time() - 400 * 86_400
        os.utime(old_auto, (old_t, old_t))
        os.utime(older_auto, (old_t, old_t))
        r3 = server.auto_backup_tick(conn)
        check("auto cũ hơn 24h thì chạy lại", r3["ran"], str(r3))
        check("prune dọn auto quá hạn", r3["pruned"] == 2 and not os.path.exists(old_auto), str(r3))
        check("backup tay không bị prune", os.path.exists(os.path.join(server.BACKUP_DIR, manual["file"])))
        app.set_setting(conn, "auto_backup", "0")
        r4 = server.auto_backup_tick(conn)
        check("tắt rồi thì tick không chạy", not r4["ran"] and r4["reason"] == "off", str(r4))
    finally:
        server.BACKUP_DIR = old_backup_dir
    conn.close()


def test_retention(tmp):
    print("[retention]")
    conn = new_conn(os.path.join(tmp, "r.db"))
    cfg = cfg_for(tmp)
    now = int(time.time() * 1000)
    old = now - 200 * 86_400_000
    conn.execute("INSERT INTO students (student_id,name,created_at_ms) VALUES ('hs_old','Cũ',?)", (now,))
    conn.execute("INSERT INTO classes (class_id,name,school_year,created_at_ms) VALUES ('c','Lớp cũ','2026-2027',?)", (now,))
    ins = ("INSERT INTO observation_sessions (student_id,class_id,source,started_at_wall_ms,"
           "ended_at_wall_ms,valid_ms,device,app_version,notes) VALUES ('hs_old','c','edge',?,?,?,?,?, '{}')")
    conn.execute(ins, (old, old + 1000, 600_000, "t", "t"))
    conn.execute(ins, (now - 1000, now, 600_000, "t", "t"))
    conn.commit()
    removed = server.retention_purge(conn, 180)
    check("retention xoá 1 phiên cũ", removed == 1, str(removed))
    check("phiên mới còn", conn.execute("SELECT COUNT(*) c FROM observation_sessions").fetchone()["c"] == 1)
    conn.close()


def test_seen_and_cascade(tmp):
    print("[seen / delete cascade]")
    conn = new_conn(os.path.join(tmp, "s.db"))
    cfg = cfg_for(tmp)
    row = add_real_student(conn, cfg)
    aid = conn.execute("SELECT alert_id FROM alerts LIMIT 1").fetchone()["alert_id"]
    check("alert mới chưa xem", conn.execute("SELECT seen_at_ms FROM alerts WHERE alert_id=?", (aid,)).fetchone()[0] is None)
    conn.execute("UPDATE alerts SET seen_at_ms=? WHERE alert_id=?", (int(time.time() * 1000), aid))
    conn.commit()
    check("đánh dấu đã xem", conn.execute("SELECT seen_at_ms FROM alerts WHERE alert_id=?", (aid,)).fetchone()[0] is not None)
    # thêm các bảng con rồi xoá dây chuyền
    conn.execute("INSERT INTO baseline_profiles (student_id,window_start_ms,window_end_ms,days,sessions_used,status,features_json,created_at_ms)"
                 " VALUES ('hs_real_01',0,1,7,1,'ready','{}',0)")
    conn.execute("INSERT INTO student_consent (student_id,consent,updated_ms) VALUES ('hs_real_01',1,0)")
    conn.execute("INSERT INTO metrics (session_row,student_id,at_ms,sei) VALUES (?, 'hs_real_01', 0, 70)", (row,))
    conn.commit()
    res = server.delete_student(conn, "hs_real_01")
    check("delete ok", res["ok"])
    for table, col in (("students", "student_id"), ("observation_sessions", "student_id"),
                       ("feature_records", "session_row"), ("alerts", "student_id"),
                       ("baseline_profiles", "student_id"), ("student_consent", "student_id"),
                       ("metrics", "student_id"), ("class_memberships", "student_id")):
        if col == "session_row":
            n = conn.execute("SELECT COUNT(*) c FROM %s" % table).fetchone()["c"]
        else:
            n = conn.execute("SELECT COUNT(*) c FROM %s WHERE %s='hs_real_01'" % (table, col)).fetchone()["c"]
        check("cascade sạch bảng %s" % table, n == 0, "n=%s" % n)
    conn.close()


def test_minute_means(tmp):
    print("[minute means]")
    from types import SimpleNamespace
    from dashboard import camera_runner
    from focusedge.constants import HabitFeature

    def kid(sei, ear, mar, pitch):
        return SimpleNamespace(sei=sei, features={HabitFeature.EAR: ear, HabitFeature.MAR: mar},
                               pitch=pitch, yaw=None)

    # Ba khung, mỗi khung 2 em: n_students phải là 2 (số em), không phải 3 (số khung mẫu).
    frames = [[kid(80, 0.25, 0.05, 5), kid(60, 0.21, 0.04, -3)],
              [kid(90, 0.27, 0.06, 8), kid(70, 0.23, 0.05, 0)],
              [kid(85, 0.26, 0.05, 2), kid(65, 0.22, 0.04, 4)]]
    rows = [camera_runner.CameraRunner._means(f) for f in frames]
    avg = camera_runner.CameraRunner._average(rows)
    check("n_students đếm em, không đếm khung", avg["n"] == 2, str(avg["n"]))
    check("SEI trung bình phút đúng", abs(avg["sei"] - 75.0) < 1e-9, str(avg["sei"]))
    check("EAR trung bình phút đúng", abs(avg["ear"] - 0.24) < 1e-9, str(avg["ear"]))
    check("góc lấy trị tuyệt đối", abs(avg["angle"] - 11.0 / 3.0) < 1e-9, str(avg["angle"]))
    # Khung chỉ 1 em giữa phút không kéo số em lên thành số khung.
    rows2 = [camera_runner.CameraRunner._means([kid(80, 0.25, 0.05, 5)] * 1)] * 4
    check("một em suốt phút vẫn là 1", camera_runner.CameraRunner._average(rows2)["n"] == 1)
    # Không có em nào: mọi số None, n=0, không NaN.
    empty = camera_runner.CameraRunner._means([])
    check("khung trống cho None chứ không NaN", empty["sei"] is None and empty["n"] == 0)


def main():
    # Console Windows mặc định cp1252 không encode được tiếng Việt; đổi stdout sang UTF-8
    # (errors=replace) để không crash giữa chừng và vẫn đọc được khi chuyển hướng ra file.
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    tmp = tempfile.mkdtemp(prefix="fc_dash_test_")
    try:
        os.makedirs(os.path.join(tmp, "out"), exist_ok=True)
        test_migration(tmp)
        test_tier_gates(tmp)
        test_demo_isolation(tmp)
        test_minute_means(tmp)
        test_overview_reports(tmp)
        test_backup_restore(tmp)
        test_auto_backup(tmp)
        test_retention(tmp)
        test_seen_and_cascade(tmp)
    finally:
        for c in OPEN:
            try:
                c.close()
            except Exception:
                pass
        shutil.rmtree(tmp, ignore_errors=True)
    print("\n%d PASS, %d FAIL" % (PASS, FAIL))
    return 1 if FAIL else 0


if __name__ == "__main__":
    sys.exit(main())