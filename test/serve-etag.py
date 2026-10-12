#!/usr/bin/env python3
"""测试用静态托管：在 SimpleHTTPRequestHandler 上补 ETag（内容 sha256）与 304。

`python3 -m http.server` 不发 ETag，agent 因此永远走不到 304 分支；这个脚本让测试能真实覆盖它。
用法：serve-etag.py <port> <directory>
"""
import hashlib
import http.server
import os
import sys


class Handler(http.server.SimpleHTTPRequestHandler):
    def _resolve(self):
        path = self.translate_path(self.path)
        if os.path.isdir(path):
            path = os.path.join(path, "index.html")
        return path

    def _serve(self, body_only: bool) -> None:
        path = self._resolve()
        if not os.path.isfile(path):
            self.send_error(404, "Not Found")
            return
        with open(path, "rb") as handle:
            body = handle.read()
        etag = '"' + hashlib.sha256(body).hexdigest() + '"'
        if self.headers.get("If-None-Match") == etag:
            self.send_response(304)
            self.send_header("ETag", etag)
            self.end_headers()
            return
        self.send_response(200)
        ctype = "application/json" if path.endswith(".json") else "application/octet-stream"
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("ETag", etag)
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        if not body_only:
            self.wfile.write(body)

    def do_GET(self):  # noqa: N802 - http.server 的命名约定
        self._serve(body_only=False)

    def do_HEAD(self):  # noqa: N802
        self._serve(body_only=True)

    def log_message(self, *args):  # 静默
        pass


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    port = int(sys.argv[1])
    directory = os.path.abspath(sys.argv[2])
    handler = lambda *a, **kw: Handler(*a, directory=directory, **kw)  # noqa: E731
    with http.server.ThreadingHTTPServer(("127.0.0.1", port), handler) as httpd:
        httpd.serve_forever()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
