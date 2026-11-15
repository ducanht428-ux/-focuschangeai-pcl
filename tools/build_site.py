"""Dựng thư mục site/ cho bản web hosted (Qoder Sites) từ giao diện cục bộ.

Chép app.css / app.js / index.html từ dashboard/web sang site/, đổi đường dẫn tuyệt đối
sang tương đối và chen hai script của bản hosted (fixture + bridge) TRƯỚC app.js.
site/hosted-bridge.js và site/demo-fixture.js không bị chép đè ở đây.
Chạy: python tools/build_site.py  (sau mỗi lần sửa dashboard/web).
"""
import pathlib
import shutil
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "dashboard" / "web"
DST = ROOT / "site"


def main():
    DST.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SRC / "app.css", DST / "app.css")
    shutil.copyfile(SRC / "app.js", DST / "app.js")
    html = (SRC / "index.html").read_text(encoding="utf-8")
    html = html.replace('href="/app.css"', 'href="app.css"')
    html = html.replace('<script src="/app.js"></script>',
                        '<script src="demo-fixture.js"></script>\n'
                        '<script src="hosted-bridge.js"></script>\n'
                        '<script src="app.js"></script>')
    html = html.replace("<title>FocusChange AI - Giám sát và phân tích hành vi học sinh</title>",
                        "<title>FocusChange AI - Giám sát và phân tích hành vi học sinh</title>\n"
                        '<meta name="description" content="Bản web của dashboard FocusChangeAI: mở link là thấy giao diện 8 màn; tự nối về máy lớp khi dashboard cục bộ đang chạy.">')
    (DST / "index.html").write_text(html, encoding="utf-8")
    print("site built: %s (index.html, app.css, app.js + bridge/fixture)" % DST)
    return 0


if __name__ == "__main__":
    sys.exit(main())