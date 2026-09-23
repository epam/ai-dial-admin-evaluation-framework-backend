#!/usr/bin/env python3
"""Serve the query console and proxy its API calls to the evaluation backend.

The backend sends no CORS headers, so a page loaded from a different origin cannot
read its responses. This puts both on one origin: static files come from this
directory, anything under /api/ is forwarded to the backend as-is.

    python3 local_env/query-console/serve.py
    python3 local_env/query-console/serve.py --backend http://localhost:8080 --port 9000

Standard library only, no install step. Local development tool: it listens on
127.0.0.1 and forwards whatever credentials the page sends.
"""

import argparse
import urllib.error
import urllib.request
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PROXY_PREFIX = "/api/"

# Hop-by-hop headers are connection-scoped and must not be forwarded.
SKIP_REQUEST_HEADERS = {"host", "connection", "keep-alive", "accept-encoding",
                        "transfer-encoding", "upgrade", "proxy-connection"}
SKIP_RESPONSE_HEADERS = {"connection", "keep-alive", "transfer-encoding",
                         "content-encoding", "content-length", "upgrade"}


class Handler(SimpleHTTPRequestHandler):
    """Static files from the console directory, plus a pass-through proxy for /api/."""

    backend = "http://localhost:8096"

    def do_GET(self):
        if self.is_api:
            self.proxy("GET")
        else:
            super().do_GET()

    def do_HEAD(self):
        if self.is_api:
            self.proxy("HEAD")
        else:
            super().do_HEAD()

    def do_POST(self):
        self.proxy("POST")

    def do_PUT(self):
        self.proxy("PUT")

    def do_PATCH(self):
        self.proxy("PATCH")

    def do_DELETE(self):
        self.proxy("DELETE")

    @property
    def is_api(self):
        return self.path.startswith(PROXY_PREFIX)

    def proxy(self, method):
        if not self.is_api:
            self.send_error(404, "Only %s* is proxied" % PROXY_PREFIX)
            return

        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None

        request = urllib.request.Request(self.backend + self.path, data=body, method=method)
        for name, value in self.headers.items():
            if name.lower() not in SKIP_REQUEST_HEADERS:
                request.add_header(name, value)

        try:
            with urllib.request.urlopen(request, timeout=120) as upstream:
                self.relay(upstream.status, upstream.headers, upstream.read())
        except urllib.error.HTTPError as error:
            # 4xx/5xx carry the backend's error body — the console renders it.
            self.relay(error.code, error.headers, error.read())
        except urllib.error.URLError as error:
            self.send_error(502, "Backend %s unreachable: %s" % (self.backend, error.reason))

    def relay(self, status, headers, payload):
        self.send_response(status)
        for name, value in headers.items():
            if name.lower() not in SKIP_RESPONSE_HEADERS:
                self.send_header(name, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args))


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8099, help="port to serve on (default: 8099)")
    parser.add_argument("--backend", default="http://localhost:8096",
                        help="evaluation backend base url (default: http://localhost:8096)")
    args = parser.parse_args()

    Handler.backend = args.backend.rstrip("/")
    handler = partial(Handler, directory=str(Path(__file__).resolve().parent))

    with ThreadingHTTPServer(("127.0.0.1", args.port), handler) as server:
        print("Query console  http://localhost:%d" % args.port)
        print("Backend        %s" % Handler.backend)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print()


if __name__ == "__main__":
    main()
