"""Chụp toàn bộ GET API của dashboard cục bộ thành fixture cho bản web hosted.

Bản hosted không có SQLite: khi không thấy server cục bộ, hosted-bridge.js trả lời mọi
lời gọi API từ fixture này (có nhãn DEMO). Fixture chụp từ server THẬT đang nạp dữ liệu
mẫu nên hình dạng khớp 1:1 với app.js; bridge dịch các mốc thời gian về "hôm nay" lúc mở
trang để màn Tổng quan luôn có số liệu.
Cần server cục bộ đang chạy và đã nạp dữ liệu mẫu: python dashboard/server.py rồi
POST /api/demo/seed (hoặc nút "Nạp dữ liệu mẫu" trong app).
Chạy: python tools/make_site_fixture.py
"""
import datetime
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8730"
ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "site" / "demo-fixture.js"
SCOPES = ("session", "day", "week")
AN_SCOPES = ("buoi", "tiet")


def get(path):
    try:
        with urllib.request.urlopen(BASE + path, timeout=15) as r:
            ct = r.headers.get("Content-Type", "")
            raw = r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        ct = e.headers.get("Content-Type", "")
    return json.loads(raw) if "json" in ct else raw


def main():
    today = datetime.date.today().isoformat()
    d = {}
    d["classes"] = get("/api/classes")
    d["overview"] = get("/api/overview?date=" + today)
    d["live"] = get("/api/live")
    # Cửa sổ 1440 phút: bucket demo nằm quanh lúc seed, có thể đã quá xa tại lúc chụp;
    # bridge sẽ dời bucket cuối về "bây giờ" để biểu đồ 30 phút luôn có đường.
    d["trend"] = get("/api/trend?minutes=1440")
    d["system"] = get("/api/system")
    # This fixture is published on a public demo link, so the teacher's absolute disk path must not ride
    # along; the repo-relative default says the same thing without naming anybody's machine.
    if isinstance(d["system"].get("database"), dict):
        d["system"]["database"]["path"] = "../data/focuschange.db"
    d["audit"] = get("/api/audit")
    d["alerts"] = get("/api/alerts")
    d["settings"] = get("/api/settings")
    d["cam_options"] = get("/api/camera/options")
    d["students"] = get("/api/students")
    d["backups"] = get("/api/backups")
    d["csv_students"] = get("/api/students/csv")
    d["reports"] = {s: get("/api/reports?scope=%s&date=%s" % (s, today)) for s in SCOPES}
    d["reports_empty"] = {s: get("/api/reports?scope=%s&date=2000-01-01" % s) for s in SCOPES}
    d["csv_reports"] = {s: get("/api/reports/csv?scope=%s&date=%s" % (s, today)) for s in SCOPES}
    d["student"], d["analysis"] = {}, {}
    for stu in d["students"]:
        sid = stu["student_id"]
        d["student"][sid] = get("/api/student?id=" + urllib.request.quote(sid))
        d["analysis"][sid] = {sc: get("/api/analysis?id=%s&scope=%s" % (urllib.request.quote(sid), sc))
                              for sc in AN_SCOPES}
    d["student_empty"] = get("/api/student?id=hs__none__")
    d["analysis_empty"] = {sc: get("/api/analysis?id=hs__none__&scope=" + sc) for sc in AN_SCOPES}
    payload = {"generated_at": int(time.time() * 1000), "data": d}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("window.FC_FIXTURE = " + json.dumps(payload, ensure_ascii=False) + ";\n",
                   encoding="utf-8")
    print("fixture written: %s (%d bytes, %d students, %d alerts)"
          % (OUT, OUT.stat().st_size, len(d["students"]), len(d["alerts"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())