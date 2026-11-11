import json
import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

# Run as `python database/test_focuschange_db.py` from the repo root, this file's own folder lands on
# sys.path first and database/database.py shadows the package, so the import below dies. Putting the
# repo root ahead of it is what makes both that command and `python -m database.test_focuschange_db`
# work; the ten-table schema is the thing under test, and a test nobody can paste is a test nobody runs.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from database import focuschange_db as DB


class DatabaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "data" / "focuschange.db"
        self.conn = DB.get_connection(self.path)

    def tearDown(self):
        self.conn.close()
        self.temp.cleanup()

    def seed(self):
        self.conn.executemany(
            "INSERT INTO students (student_id, name, created_at_ms) VALUES (?, ?, ?)",
            [("hs1", "Học sinh thử 1", 1000), ("hs2", "Học sinh thử 2", 1000)],
        )
        self.conn.executemany(
            "INSERT INTO classes (class_id, name, school_year, created_at_ms) VALUES (?, ?, ?, ?)",
            [("a", "10A1", "2026-2027", 1000), ("b", "10A2", "2026-2027", 1000),
             ("c", "11A1", "2027-2028", 1000), ("d", "12A1", "2028-2029", 1000)],
        )

    def membership(self, class_id, year, start, end=None):
        return self.conn.execute(
            "INSERT INTO class_memberships"
            " (student_id, class_id, school_year, started_on, ended_on) VALUES (?, ?, ?, ?, ?)",
            ("hs1", class_id, year, start, end),
        ).lastrowid

    def session(self):
        return self.conn.execute(
            "INSERT INTO observation_sessions"
            " (student_id, source, started_at_wall_ms, ended_at_wall_ms, valid_ms)"
            " VALUES (?, ?, ?, ?, ?)",
            ("hs1", "web-pc", 1000, 601000, 600000),
        ).lastrowid

    def alert(self, session_id, student="hs1"):
        return self.conn.execute(
            "INSERT INTO alerts (session_row, student_id, kind, message, raised_at_wall_ms)"
            " VALUES (?, ?, ?, ?, ?)",
            (session_id, student, "phone", "Có điện thoại trong vùng quan sát", 2000),
        ).lastrowid

    def test_creates_exact_ten_empty_tables(self):
        self.assertTrue(self.path.is_file())
        self.assertEqual(sorted(DB.TABLES), DB.table_names(self.conn))
        for table in DB.TABLES:
            self.assertEqual(self.conn.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0], 0)
        self.assertEqual(self.conn.execute("PRAGMA foreign_keys").fetchone()[0], 1)

    def test_reopen_preserves_data_and_enforces_foreign_keys(self):
        self.seed()
        self.conn.commit()
        self.conn.close()
        self.conn = DB.get_connection(self.path)
        self.assertEqual(self.conn.execute("SELECT name FROM students WHERE student_id='hs1'")
                         .fetchone()["name"], "Học sinh thử 1")
        self.assertEqual(self.conn.execute("PRAGMA foreign_keys").fetchone()[0], 1)
        DB.init_db(self.conn)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM students").fetchone()[0], 2)

    def test_in_memory_connection(self):
        conn = DB.get_connection(":memory:")
        try:
            self.assertEqual(DB.table_names(conn), sorted(DB.TABLES))
        finally:
            conn.close()

    def test_orphan_references_are_rejected(self):
        statements = [
            ("INSERT INTO class_memberships (student_id,class_id,school_year,started_on)"
             " VALUES (?,?,?,?)", ("missing", "missing", "2026-2027", "2026-09-01")),
            ("INSERT INTO observation_sessions (student_id,ended_at_wall_ms) VALUES (?,?)",
             ("missing", 1000)),
            ("INSERT INTO baseline_profiles (student_id,window_start_ms,window_end_ms,created_at_ms)"
             " VALUES (?,?,?,?)", ("missing", 1000, 2000, 2000)),
            ("INSERT INTO feature_records (session_row,feature,mean,std,n) VALUES (?,?,?,?,?)",
             (999, "ear", 0.3, 0.01, 10)),
            ("INSERT INTO ai_results (session_row,created_at_ms) VALUES (?,?)", (999, 2000)),
            ("INSERT INTO alerts (session_row,student_id,kind,message,raised_at_wall_ms)"
             " VALUES (?,?,?,?,?)", (999, "missing", "phone", "test", 2000)),
            ("INSERT INTO review_records (alert_id,reviewer,verdict,reviewed_at_ms) VALUES (?,?,?,?)",
             (999, "test", "needs_review", 2000)),
        ]
        for sql, args in statements:
            with self.subTest(sql=sql), self.assertRaisesRegex(sqlite3.IntegrityError, "FOREIGN KEY"):
                self.conn.execute(sql, args)

    def test_same_id_across_three_school_years(self):
        self.seed()
        self.membership("a", "2026-2027", "2026-09-01", "2027-09-01")
        self.membership("c", "2027-2028", "2027-09-01", "2028-09-01")
        self.membership("d", "2028-2029", "2028-09-01")
        rows = self.conn.execute("SELECT student_id FROM class_memberships ORDER BY started_on").fetchall()
        self.assertEqual([r["student_id"] for r in rows], ["hs1"] * 3)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM students WHERE student_id='hs1'")
                         .fetchone()[0], 1)

    def test_return_to_previous_class_preserves_each_period(self):
        self.seed()
        self.membership("a", "2026-2027", "2026-09-01", "2026-10-01")
        self.membership("b", "2026-2027", "2026-10-01", "2026-11-01")
        self.membership("a", "2026-2027", "2026-11-01")
        rows = self.conn.execute("SELECT class_id FROM class_memberships ORDER BY started_on").fetchall()
        self.assertEqual([r["class_id"] for r in rows], ["a", "b", "a"])

    def test_overlapping_memberships_rejected_on_insert_and_update(self):
        self.seed()
        first = self.membership("a", "2026-2027", "2026-09-01", "2026-10-01")
        second = self.membership("b", "2026-2027", "2026-10-01")
        with self.assertRaisesRegex(sqlite3.IntegrityError, "overlapping"):
            self.membership("a", "2026-2027", "2026-11-01")
        with self.assertRaisesRegex(sqlite3.IntegrityError, "overlapping"):
            self.conn.execute("UPDATE class_memberships SET ended_on=NULL WHERE membership_id=?", (first,))
        with self.assertRaisesRegex(sqlite3.IntegrityError, "overlapping"):
            self.conn.execute("UPDATE class_memberships SET started_on=? WHERE membership_id=?",
                              ("2026-09-15", second))

    def test_membership_year_must_match_class(self):
        self.seed()
        with self.assertRaisesRegex(sqlite3.IntegrityError, "FOREIGN KEY"):
            self.membership("a", "2027-2028", "2027-09-01")
        self.membership("a", "2026-2027", "2026-09-01")
        with self.assertRaisesRegex(sqlite3.IntegrityError, "FOREIGN KEY"):
            self.conn.execute("UPDATE class_memberships SET school_year='2027-2028'")
        with self.assertRaisesRegex(sqlite3.IntegrityError, "FOREIGN KEY"):
            self.conn.execute("UPDATE classes SET school_year='2027-2028' WHERE class_id='a'")

    def test_membership_dates_are_valid_and_ordered(self):
        self.seed()
        for start, end in [("2026-02-30", None), ("bad", None), ("2026-09-01", "2026-08-01"),
                           ("2026-09-01", "2026-09-01"), ("2026-09-01", "2026-11-31")]:
            with self.subTest(start=start, end=end), self.assertRaises(sqlite3.IntegrityError):
                self.membership("a", "2026-2027", start, end)

    def test_alert_student_must_own_session(self):
        self.seed()
        session_id = self.session()
        with self.assertRaisesRegex(sqlite3.IntegrityError, "FOREIGN KEY"):
            self.alert(session_id, "hs2")
        alert_id = self.alert(session_id)
        with self.assertRaisesRegex(sqlite3.IntegrityError, "FOREIGN KEY"):
            self.conn.execute("UPDATE alerts SET student_id='hs2' WHERE alert_id=?", (alert_id,))
        with self.assertRaisesRegex(sqlite3.IntegrityError, "FOREIGN KEY"):
            self.conn.execute("UPDATE observation_sessions SET student_id='hs2' WHERE session_row=?",
                              (session_id,))

    def test_baseline_stores_statistics_and_rejects_invalid_json(self):
        self.seed()
        stats = {"ear": {"mean": 0.3, "std": 0.02, "n": 100},
                 "blink_bpm": {"mean": 15.0, "std": 2.0, "n": 50}}
        sql = ("INSERT INTO baseline_profiles"
               " (student_id,window_start_ms,window_end_ms,features_json,created_at_ms)"
               " VALUES (?,?,?,?,?)")
        self.conn.execute(sql, ("hs1", 1000, 2000, json.dumps(stats), 2000))
        row = self.conn.execute("SELECT * FROM baseline_profiles").fetchone()
        self.assertEqual(json.loads(row["features_json"]), stats)
        self.assertEqual(row["days"], 14)
        for invalid in ["broken", "[]", "null", "42"]:
            with self.subTest(invalid=invalid), self.assertRaises(sqlite3.IntegrityError):
                self.conn.execute(sql, ("hs1", 1000, 2000, invalid, 2000))

    def test_features_preserve_mediapipe_and_yolo_context(self):
        self.seed()
        sid = self.session()
        sql = ("INSERT INTO feature_records (session_row,feature,mean,std,n,extractor)"
               " VALUES (?,?,?,?,?,?)")
        self.conn.execute(sql, (sid, "ear", 0.31, 0.02, 100, "mediapipe"))
        self.conn.execute(sql, (sid, "phone_present", 0.2, 0.4, 100, "yolo"))
        rows = self.conn.execute("SELECT * FROM feature_records ORDER BY extractor").fetchall()
        self.assertEqual([(r["feature"], r["extractor"]) for r in rows],
                         [("ear", "mediapipe"), ("phone_present", "yolo")])
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute(sql, (sid, "ear", 0.3, 0.01, 100, "mediapipe"))
        for std, n in [(-1, 10), (0, 0), (0, -1)]:
            with self.subTest(std=std, n=n), self.assertRaises(sqlite3.IntegrityError):
                self.conn.execute(sql, (sid, "mar", 0.3, std, n, "mediapipe"))

    def test_session_dates_and_scores_reject_invalid_values(self):
        self.seed()
        with self.assertRaises(sqlite3.IntegrityError):
            self.conn.execute("INSERT INTO observation_sessions"
                              " (student_id,started_at_wall_ms,ended_at_wall_ms) VALUES ('hs1',2000,1000)")
        sid = self.session()
        for value in [-1, 101]:
            with self.subTest(value=value), self.assertRaises(sqlite3.IntegrityError):
                self.conn.execute("INSERT INTO ai_results (session_row,sei_mean,created_at_ms) VALUES (?,?,?)",
                                  (sid, value, 2000))
        self.conn.execute("INSERT INTO ai_results (session_row,sei_mean,created_at_ms) VALUES (?,NULL,?)",
                          (sid, 2000))

    def test_teacher_review_and_session_cascade(self):
        self.seed()
        sid = self.session()
        self.conn.execute("INSERT INTO feature_records (session_row,feature,mean,std,n) VALUES (?,?,?,?,?)",
                          (sid, "ear", 0.3, 0.01, 50))
        self.conn.execute("INSERT INTO ai_results (session_row,label,explanation,created_at_ms)"
                          " VALUES (?,?,?,?)", (sid, "needs_review", "Cần giáo viên xem xét", 2000))
        aid = self.alert(sid)
        self.conn.execute("INSERT INTO review_records (alert_id,reviewer,verdict,comment,reviewed_at_ms)"
                          " VALUES (?,?,?,?,?)", (aid, "Giáo viên thử", "false_alarm", "Kiểm tra lại", 3000))
        self.assertEqual(self.conn.execute("SELECT comment FROM review_records").fetchone()[0], "Kiểm tra lại")
        self.conn.execute("DELETE FROM observation_sessions WHERE session_row=?", (sid,))
        for table in ["feature_records", "ai_results", "alerts", "review_records"]:
            self.assertEqual(self.conn.execute(f'SELECT COUNT(*) FROM "{table}"').fetchone()[0], 0)
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM students").fetchone()[0], 2)

    def test_student_ids_cannot_be_changed_under_history(self):
        self.seed()
        self.membership("a", "2026-2027", "2026-09-01")
        with self.assertRaisesRegex(sqlite3.IntegrityError, "FOREIGN KEY"):
            self.conn.execute("UPDATE students SET student_id='changed' WHERE student_id='hs1'")
        with self.assertRaisesRegex(sqlite3.IntegrityError, "FOREIGN KEY"):
            self.conn.execute("DELETE FROM students WHERE student_id='hs1'")

    def test_parameterized_unicode_values_and_required_ids(self):
        text = "Học sinh O'Brien'); DROP TABLE students;--"
        self.conn.execute("INSERT INTO students (student_id,name,created_at_ms) VALUES (?,?,?)",
                          ("test", text, 1000))
        self.assertEqual(self.conn.execute("SELECT name FROM students").fetchone()[0], text)
        for student_id in [None, "", " "]:
            with self.subTest(student_id=student_id), self.assertRaises(sqlite3.IntegrityError):
                self.conn.execute("INSERT INTO students (student_id,name,created_at_ms) VALUES (?,?,?)",
                                  (student_id, "test", 1000))

    def test_backup_history_is_local_metadata(self):
        self.conn.execute("INSERT INTO sync_history"
                          " (direction,target,status,rows_synced,started_at_ms,detail) VALUES (?,?,?,?,?,?)",
                          ("backup", "USB", "completed", 12, 1000, "Local test only"))
        row = self.conn.execute("SELECT * FROM sync_history").fetchone()
        self.assertEqual((row["direction"], row["rows_synced"]), ("backup", 12))

    def test_transaction_rolls_back_after_invalid_reference(self):
        with self.assertRaises(sqlite3.IntegrityError):
            with self.conn:
                self.conn.execute("INSERT INTO students (student_id,name,created_at_ms) VALUES ('hs1','test',1000)")
                self.conn.execute("INSERT INTO observation_sessions"
                                  " (student_id,ended_at_wall_ms) VALUES ('missing',2000)")
        self.assertEqual(self.conn.execute("SELECT COUNT(*) FROM students").fetchone()[0], 0)

    def test_integrity_and_foreign_key_checks(self):
        self.seed()
        self.session()
        self.assertEqual(self.conn.execute("PRAGMA integrity_check").fetchone()[0], "ok")
        self.assertEqual(self.conn.execute("PRAGMA foreign_key_check").fetchall(), [])


if __name__ == "__main__":
    unittest.main()
