from argparse import ArgumentParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Preview(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/", "/onboarding/"):
            self.send_error(404)
            return
        body = Path(__file__).with_name("index.html").read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    parser = ArgumentParser(description="Serve only the PristinePage onboarding mockup on loopback.")
    parser.add_argument("--port", type=int, default=8768)
    with ThreadingHTTPServer(("127.0.0.1", parser.parse_args().port), Preview) as server:
        print(f"PristinePage preview: http://localhost:{server.server_port}/onboarding/", flush=True)
        server.serve_forever()
