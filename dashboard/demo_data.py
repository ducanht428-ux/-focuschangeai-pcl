"""Dữ liệu mẫu CÓ GẮN NHÃN cho dashboard - bật/tắt bằng một nút, xoá là sạch tận gốc.

Đặc tả mục 14 cho phép nghiệm thu bằng "dữ liệu thật hoặc dữ liệu mẫu"; máy này chưa chạy được
camera thật nên các màn hình cần một bộ số mẫu để giáo viên thấy đúng hình dạng của báo cáo,
biểu đồ và cảnh báo. Mọi dòng mẫu đều mang dấu vết không thể nhầm:
  * mã học sinh ``hs_demo_XX``, tên "HS Mẫu XX";
  * ``notes.demo = true`` trong từng phiên, device "demo", app_version "edge-demo-seed";
  * bucket biểu đồ ``class_trend.source = 'demo'``.
Nút xoá chỉ đụng các dấu vết đó - dữ liệu thật nằm cạnh không bị xoá nhầm một dòng.
"""
from __future__ import annotations

import json
import math
import time

DEMO_PREFIX = "hs_demo_"
DEMO_VERSION = "edge-demo-seed"


def is_demo_present(conn) -> bool:
    return conn.execute("SELECT 1 FROM students WHERE student_id LIKE ? LIMIT 1",
                        (DEMO_PREFIX + "%",)).fetchone() is not None


def _wave(i: int, d: int) -> float:
    """Dao động tất định để biểu đồ mẫu trông như một tuần học thật, không phải đường thẳng."""
    return 6.0 * math.sin((i + 1) * 1.7 + d * 0.9) + 3.0 * math.cos(d * 1.3)


def seed(conn, cfg) -> dict:
    if is_demo_present(conn):
        return {"ok": True, "already": True}
    from focusedge.hud import alert_sentence
    now = int(time.time() * 1000)
    class_id = cfg.get("class_id", "class")
    school_year = cfg.get("storage", {}).get("school_year", "2026-2027")
    conn.execute("INSERT INTO classes (class_id, name, school_year, created_at_ms)"
                 " VALUES (?, ?, ?, ?) ON CONFLICT(class_id) DO NOTHING",
                 (class_id, cfg.get("class_label") or class_id, school_year, now))
    n_sessions = n_alerts = n_metrics = 0
    for i in range(5):
        sid = "%s%02d" % (DEMO_PREFIX, i + 1)
        name = "HS Mẫu %02d" % (i + 1)
        conn.execute("INSERT OR IGNORE INTO students (student_id, name, birth_year, gender, created_at_ms)"
                     " VALUES (?, ?, ?, ?, ?)", (sid, name, 2010, "nam" if i % 2 else "nữ", now))
        conn.execute("INSERT OR IGNORE INTO student_consent (student_id, consent, updated_ms, note)"
                     " VALUES (?, 1, ?, 'DEMO - đồng ý mẫu')", (sid, now))
        conn.execute("INSERT INTO class_memberships (student_id, class_id, school_year, started_on, note)"
                     " VALUES (?, ?, ?, ?, 'demo')",
                     (sid, class_id, school_year, time.strftime("%Y-%m-%d", time.localtime(now / 1000 - 7 * 86400))))
        base = 62 + i * 5
        last_start = None
        for d in range(6):
            if d == 0:
                # Phiên HÔM NAY, kết thúc 5 phút trước: mở app ngày nào cũng thấy "Hôm nay"
                # có số mà xem, đúng tinh thần nghiệm thu bằng dữ liệu mẫu của đặc tả 14.
                start = now - 45 * 60_000
            else:
                day_start = int(time.mktime(time.strptime(
                    time.strftime("%Y-%m-%d", time.localtime(now / 1000 - d * 86400)), "%Y-%m-%d")) * 1000)
                start = day_start + 8 * 3600_000 + i * 60_000
            end = start + 40 * 60_000
            last_start = start
            sei = round(base + _wave(i, d), 1)
            notes = {"demo": True, "eligible": True, "face_ratio": 0.92, "valid_ratio": 0.8,
                     "quality_mean": 80.0, "alerts_total": 0, "avg_fps": 18.0, "identity": "manual"}
            cur = conn.execute(
                "INSERT INTO observation_sessions (student_id, class_id, source, started_at_wall_ms,"
                " ended_at_wall_ms, valid_ms, device, app_version, notes)"
                " VALUES (?, ?, 'edge-camera', ?, ?, ?, 'demo', ?, ?)",
                (sid, class_id, start, end, 30 * 60_000, DEMO_VERSION, json.dumps(notes, ensure_ascii=False)))
            row = cur.lastrowid
            n_sessions += 1
            for feat, mean, std, n in (("sei", sei, 8.0, 240), ("ear", 0.25 + 0.01 * i, 0.03, 200),
                                       ("mar", 0.18, 0.05, 200), ("pitch", 3.6 + i, 2.0, 200),
                                       ("yaw", 2.0 + i, 1.5, 200)):
                conn.execute("INSERT OR REPLACE INTO feature_records (session_row, feature, mean, std, n, extractor)"
                             " VALUES (?, ?, ?, ?, ?, 'demo')", (row, feat, mean, std, n))
            kinds = [("DROWSY", 10), ("LOOK_AWAY", 22), ("LOW_BLINK", 31)] if (i + d) % 3 == 0 else \
                    ([("LOOK_AWAY", 18)] if (i + d) % 3 == 1 else [])
            for kind, minute in kinds:
                conn.execute("INSERT INTO alerts (session_row, student_id, kind, message, raised_at_wall_ms)"
                             " VALUES (?, ?, ?, ?, ?)",
                             (row, sid, kind, alert_sentence(kind, None, None) or kind, start + minute * 60_000))
                n_alerts += 1
            if d == 0:
                for m in range(40):
                    conn.execute("INSERT INTO metrics (session_row, student_id, at_ms, ear, mar, pitch, yaw, sei)"
                                 " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                                 (row, sid, start + m * 60_000,
                                  round(0.25 + 0.03 * math.sin(m / 5.0 + i), 3),
                                  round(0.18 + 0.04 * math.sin(m / 7.0), 3),
                                  round(3.6 + 2.0 * math.sin(m / 6.0), 2),
                                  round(2.0 + 1.5 * math.cos(m / 8.0), 2),
                                  round(base + 10 * math.sin(m / 6.0 + i) + _wave(i, d) / 2, 1)))
                    n_metrics += 1
        conn.execute("INSERT INTO baseline_profiles (student_id, window_start_ms, window_end_ms, days,"
                     " sessions_used, status, features_json, created_at_ms) VALUES (?, ?, ?, 7, 6, 'ready', ?, ?)",
                     (sid, now - 7 * 86_400_000, now,
                      json.dumps({"band_k": 2.5, "source": "edge-camera", "device": "demo",
                                  "features": {"sei": {"typical": base, "min": base - 20,
                                                       "max": base + 20, "spread": 8.0}}},
                                 ensure_ascii=False), now))
    # 30 phút gần nhất cho biểu đồ Tổng quan, mỗi phút một bucket, nhãn demo.
    minute = now // 60_000 * 60_000
    for k in range(30, 0, -1):
        bucket = minute - k * 60_000
        conn.execute("INSERT OR REPLACE INTO class_trend (bucket_ms, mean_sei, mean_ear, mean_mar,"
                     " mean_angle, n_students, source) VALUES (?, ?, ?, ?, ?, 5, 'demo')",
                     (bucket, round(74 + 8 * math.sin(k / 5.0), 1), 0.25, 0.18, 3.6))
    conn.commit()
    return {"ok": True, "students": 5, "sessions": n_sessions, "alerts": n_alerts,
            "metrics": n_metrics, "trend_buckets": 30}


def purge(conn) -> dict:
    ids = [r[0] for r in conn.execute("SELECT student_id FROM students WHERE student_id LIKE ?",
                                      (DEMO_PREFIX + "%",))]
    for sid in ids:
        conn.execute("DELETE FROM observation_sessions WHERE student_id = ?", (sid,))
        conn.execute("DELETE FROM baseline_profiles WHERE student_id = ?", (sid,))
        conn.execute("DELETE FROM class_memberships WHERE student_id = ?", (sid,))
        conn.execute("DELETE FROM student_consent WHERE student_id = ?", (sid,))
        conn.execute("DELETE FROM metrics WHERE student_id = ?", (sid,))
        conn.execute("DELETE FROM students WHERE student_id = ?", (sid,))
    cur = conn.execute("DELETE FROM class_trend WHERE source = 'demo'")
    trend = cur.rowcount
    conn.commit()
    return {"ok": True, "students": len(ids), "trend_buckets": trend}