"""Bảng phụ trợ cho giao diện dashboard - migration CỘNG THÊM, không sửa schema gốc.

Schema 10 bảng của ``focuschange_db.py`` là bản ghi đo lường viết theo đặc tả điện thoại
(parity): nó không có chỗ cho những thứ một ứng dụng lớp học cần nhưng phép đo không sinh ra
(cài đặt bậc sản phẩm, nhật ký kiểm toán, cờ đồng ý của phụ huynh, trạng thái camera, chuỗi
thời gian để vẽ biểu đồ). Đặc tả sản phẩm mục 8 liệt kê các bảng tối thiểu đó; module này thêm
 chúng theo đúng nguyên tắc migration của đặc tả: ``PRAGMA user_version`` tăng dần, mỗi bậc
migration chỉ chạy một lần, và không bảng nào của bản ghi đo lường bị đổi tên hay đổi kiểu.

Hai ngoại lệ có chủ đích, đều là thêm cột chứ không đổi cột:
  * ``alerts.seen_at_ms`` - đặc tả 5.7 đòi "đánh dấu đã xem"; bảng alerts gốc chưa có cột đó.
    Thêm cột NULL được, mặc định NULL = chưa xem, nên mọi dòng cũ giữ nguyên nghĩa.
  * không đụng ``students``: cờ đồng ý nằm ở ``student_consent`` khoá ngoại CASCADE, để việc
    thêm/bớt cờ không bao giờ ghi đè lên dòng học sinh mà roster đang giữ.

Module này không tự mở database: nó nhận connection đã mở (LocalStore hoặc server dashboard),
để chỉ có MỘT nơi quyết định đường dẫn file và chế độ khoá ngoại.
"""
from __future__ import annotations

import sqlite3
import time

# Bậc migration hiện tại của phần phụ trợ. Tăng số này khi thêm migration mới, và viết khối
# tương ứng trong init_app_tables; không sửa khối cũ của bậc đã phát hành.
APP_VERSION = 1

APP_TABLES = (
    "settings",
    "audit_log",
    "cameras",
    "student_consent",
    "metrics",
    "class_trend",
)

_SCHEMA_V1 = """
CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY CHECK (length(trim(key)) > 0),
    value TEXT NOT NULL,
    updated_ms INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_log (
    audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
    at_ms INTEGER NOT NULL,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    detail TEXT
);
CREATE INDEX IF NOT EXISTS audit_log_time ON audit_log(at_ms);

CREATE TABLE IF NOT EXISTS cameras (
    camera_id TEXT PRIMARY KEY CHECK (length(trim(camera_id)) > 0),
    kind TEXT NOT NULL,
    label TEXT NOT NULL,
    status TEXT NOT NULL,
    last_seen_ms INTEGER
);

CREATE TABLE IF NOT EXISTS student_consent (
    student_id TEXT PRIMARY KEY REFERENCES students(student_id) ON DELETE CASCADE,
    consent INTEGER NOT NULL DEFAULT 0 CHECK (consent IN (0, 1)),
    updated_ms INTEGER NOT NULL,
    note TEXT
);

-- Chuỗi thời gian chỉ số từng em (đặc tả mục 8: bảng metrics, ghi theo lô, có chỉ mục theo
-- phiên và theo em). Lõi camera hiện chưa ghi bảng này mỗi khung hình - đó là việc của giai
-- đoạn nối lõi đo vào dashboard; bảng và chỉ mục dựng sẵn để lời gọi ghi sau này không phải
-- migration lại. Dữ liệu mẫu có nhãn DEMO dùng bảng này để biểu đồ có gì mà vẽ.
CREATE TABLE IF NOT EXISTS metrics (
    metric_id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_row INTEGER NOT NULL REFERENCES observation_sessions(session_row) ON DELETE CASCADE,
    student_id TEXT NOT NULL REFERENCES students(student_id) ON DELETE CASCADE,
    at_ms INTEGER NOT NULL,
    ear REAL,
    mar REAL,
    pitch REAL,
    yaw REAL,
    sei REAL
);
CREATE INDEX IF NOT EXISTS metrics_student_time ON metrics(student_id, at_ms);
CREATE INDEX IF NOT EXISTS metrics_session ON metrics(session_row);

-- Trung bình lớp theo từng phút, để biểu đồ "30 phút gần nhất" sống sót qua lần mở app sau.
-- source phân biệt số đo thật ('live') với dữ liệu mẫu ('demo') - nút xoá demo chỉ xoá 'demo'.
CREATE TABLE IF NOT EXISTS class_trend (
    bucket_ms INTEGER PRIMARY KEY,
    mean_sei REAL,
    mean_ear REAL,
    mean_mar REAL,
    mean_angle REAL,
    n_students INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL DEFAULT 'live'
);
"""

# Giá trị mặc định đọc được ngay trên màn Cài đặt. Đặc tả: bậc standard cho bản đầu, lưu 180
# ngày, dấu hiệu lặp >= 2 lần mới báo, ngưỡng cá nhân 4-5 lần, có thời gian chờ giữa hai cảnh
# báo cùng loại. Mọi con số đều chỉnh được trong Cài đặt vì đặc tả 7.2 bắt buộc như vậy.
DEFAULT_SETTINGS = {
    "tier": "standard",
    "retention_days": "180",
    "language": "vi",
    "theme": "light",
    "alert_repeat": "2",
    "alert_personal_repeat": "4",
    "alert_cooldown_s": "60",
    "auto_backup": "0",
}

TIERS = ("standard", "pro", "promax", "ultra")

# Bậc tối thiểu của từng tính năng phụ (đặc tả mục 4). Một cờ tier duy nhất quyết định; UI và
# API đều hỏi hàm tier_allows chứ không tự rẽ nhánh theo bậc ở nhiều nơi.
TIER_GATES = {
    "csv_export": "pro",
    "auto_backup": "pro",
    "personal_baseline": "pro",
    "multi_class": "promax",
    "parent_report": "promax",
    "yolo_context": "promax",
    "predict": "ultra",
    "assistant": "ultra",
    "parent_portal": "ultra",
}


def tier_allows(tier: str, feature: str) -> bool:
    """Tính năng có mở ở bậc này không. Bậc lạ coi như standard - không nâng quyền nhầm."""
    need = TIER_GATES.get(feature)
    if need is None:
        return True
    if tier not in TIERS:
        tier = "standard"
    return TIERS.index(tier) >= TIERS.index(need)


def init_app_tables(conn: sqlite3.Connection) -> None:
    """Chạy migration phụ trợ tới APP_VERSION. Idempotent: gọi lại bao nhiêu lần cũng an toàn."""
    version = conn.execute("PRAGMA user_version").fetchone()[0]
    if version < 1:
        conn.executescript(_SCHEMA_V1)
        # Thêm cột đã xem cho alerts nếu chưa có. PRAGMA table_info là cách duy nhất hỏi
        # "cột này tồn tại chưa" mà không bắt exception điều khiển luồng.
        cols = [r[1] for r in conn.execute("PRAGMA table_info(alerts)")]
        if "seen_at_ms" not in cols:
            conn.execute("ALTER TABLE alerts ADD COLUMN seen_at_ms INTEGER")
        now = int(time.time() * 1000)
        for key, value in DEFAULT_SETTINGS.items():
            conn.execute("INSERT OR IGNORE INTO settings (key, value, updated_ms) VALUES (?, ?, ?)",
                         (key, value, now))
        conn.execute("PRAGMA user_version = 1")
        conn.commit()


def get_setting(conn: sqlite3.Connection, key: str, default: str = "") -> str:
    row = conn.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
    return row[0] if row else default


def set_setting(conn: sqlite3.Connection, key: str, value: str) -> None:
    conn.execute(
        "INSERT INTO settings (key, value, updated_ms) VALUES (?, ?, ?)"
        " ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_ms = excluded.updated_ms",
        (key, str(value), int(time.time() * 1000)))
    conn.commit()


def audit(conn: sqlite3.Connection, actor: str, action: str, detail: str = "") -> None:
    """Nhật ký kiểm toán (đặc tả 9): hành động nhạy cảm phải để lại dấu vết ai/lúc nào/làm gì."""
    conn.execute("INSERT INTO audit_log (at_ms, actor, action, detail) VALUES (?, ?, ?, ?)",
                 (int(time.time() * 1000), actor, action, detail))
    conn.commit()