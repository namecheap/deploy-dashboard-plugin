"""A Maven "repository" that answers every request with 429, counting hits.

Used by `sandbox.sh retry-check` to prove that the resolver retry setting the
workflows pass in MAVEN_ARGS is actually honoured.
"""

import http.server
import sys

hits = 0


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        global hits
        hits += 1
        print(f"hit {hits} {self.path}", flush=True)
        self.send_response(429)
        self.send_header("Content-Length", "0")
        self.end_headers()

    do_HEAD = do_GET

    def log_message(self, *args):
        pass


http.server.ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), Handler).serve_forever()
