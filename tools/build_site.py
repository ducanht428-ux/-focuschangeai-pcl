"""Dựng thư mục site/ cho bản web hosted (Qoder Sites) từ giao diện cục bộ.

Chép app.css / app.js / index.html từ dashboard/web sang site/, đổi đường dẫn tuyệt đối
sang tương đối và chen các script của bản hosted TRƯỚC app.js. Bốn script hosted
(report-shared, demo-fixture, webdb-bridge, hosted-bridge) chép từ dashboard/web-hosted:
đó là nguồn duy nhất, sửa ở đó rồi chạy lại tool này.
Chạy: python tools/build_site.py  (sau mỗi lần sửa dashboard/web hoặc web-hosted).
"""
import pathlib
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "dashboard" / "web"
HOSTED = ROOT / "dashboard" / "web-hosted"
DST = ROOT / "site"
SCRIPTS = ("report-shared.js", "demo-fixture.js", "webdb-bridge.js", "hosted-bridge.js")


def main():
    DST.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SRC / "app.css", DST / "app.css")
    shutil.copyfile(SRC / "app.js", DST / "app.js")
    for name in SCRIPTS:
        shutil.copyfile(HOSTED / name, DST / name)
    html = (SRC / "index.html").read_text(encoding="utf-8")
    html = html.replace('href="/app.css"', 'href="app.css"')
    html = html.replace('<script src="/app.js"></script>',
                        "".join('<script src="%s"></script>\n' % n for n in SCRIPTS)
                        + '<script src="app.js"></script>')
    html = html.replace("<title>FocusChange AI - Giám sát và phân tích hành vi học sinh</title>",
                        "<title>FocusChange AI - Giám sát và phân tích hành vi học sinh</title>\n"
                        '<meta name="description" content="Bản web của dashboard FocusChangeAI: đọc số đo thật từ SQLite của máy này, tự nối về dashboard cục bộ khi đang chạy; không hiển thị số liệu mẫu trừ khi gọi ?mode=demo.">')
    (DST / "index.html").write_text(html, encoding="utf-8")
    print("site built: %s (index.html, app.css, app.js + %s)" % (DST, ", ".join(SCRIPTS)))
    return 0


if __name__ == "__main__":
    sys.exit(main())