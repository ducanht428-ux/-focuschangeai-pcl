#!/usr/bin/env python3
"""Chạy FocusChange AI offline: máy chủ tĩnh chỉ nghe trên máy này (127.0.0.1), không cần internet.
Dùng:  python serve.py [cổng]     (mặc định 8765). Mở http://localhost:8765/dashboard/
"""
import http.server, mimetypes, os, sys, threading, webbrowser

# Trình duyệt cần đúng kiểu nội dung cho .wasm và .mjs (Windows hay trả sai).
for ext, typ in {".wasm": "application/wasm", ".mjs": "text/javascript", ".js": "text/javascript", ".onnx": "application/octet-stream",
                 ".task": "application/octet-stream", ".tflite": "application/octet-stream", ".webmanifest": "application/manifest+json"}.items():
    mimetypes.add_type(typ, ext)

class Handler(http.server.SimpleHTTPRequestHandler):
    def end_headers(self):
        # Mã luôn kiểm tra bản mới; model nặng cho trình duyệt giữ (giống cấu hình trên Cloudflare).
        heavy = self.path.split("?")[0].endswith((".wasm", ".onnx", ".task", ".tflite"))
        self.send_header("Cache-Control", "public, max-age=604800" if heavy else "no-cache")
        super().end_headers()
    def log_message(self, fmt, *args):  # im lặng, đỡ rối cửa sổ
        pass
    def send_error(self, code, message=None, explain=None):
        if code == 404 and os.path.exists("404.html"):
            data = open("404.html", "rb").read()
            self.send_response(404); self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data))); self.end_headers()
            if self.command != "HEAD": self.wfile.write(data)
        else:
            super().send_error(code, message, explain)

def main():
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    try:
        srv = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
    except OSError:
        print("Cổng %d đang được dùng. Đóng cửa sổ FocusChange AI cũ rồi thử lại, hoặc chạy: python serve.py 8766" % port); input("Nhấn Enter để thoát..."); return
    url = "http://localhost:%d/dashboard/" % port
    print("FocusChange AI đang chạy offline tại", url)
    print("Giữ cửa sổ này mở khi dùng. Đóng cửa sổ (hoặc Ctrl+C) để tắt.")
    threading.Timer(1.2, lambda: webbrowser.open(url)).start()
    try: srv.serve_forever()
    except KeyboardInterrupt: pass

if __name__ == "__main__":
    main()
