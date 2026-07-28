import hashlib
import hmac
import http.client
import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

ROLE = os.environ.get("FIXTURE_ROLE", "origin")
PORT = int(os.environ.get("PORT", "8080"))
UPSTREAM = os.environ.get("UPSTREAM", "")
CAPTURES = []


def read_secret(name, default=""):
    path = f"/run/secrets/{name}"
    try:
        return open(path, encoding="utf-8").read().strip()
    except OSError:
        return os.environ.get(name.upper(), default)


def safe_headers(headers):
    hidden = {"authorization", "proxy-authorization", "api-key", "x-app-secret", "x-app-signature"}
    return {k: ("<redacted>" if k.lower() in hidden else v) for k, v in headers.items()}


def record(handler, body=b""):
    CAPTURES.append({
        "role": ROLE,
        "method": handler.command,
        "path": handler.path,
        "headers": safe_headers(handler.headers),
        "body_sha256": hashlib.sha256(body).hexdigest(),
        "body_bytes": len(body),
        "at": int(time.time()),
    })
    del CAPTURES[:-50]


def json_bytes(value):
    return json.dumps(value, separators=(",", ":")).encode()


class Handler(BaseHTTPRequestHandler):
    server_version = "ChaiFixture/1.0"

    def log_message(self, fmt, *args):
        return

    def read_body(self):
        length = min(int(self.headers.get("Content-Length", "0") or 0), 1024 * 1024)
        return self.rfile.read(length)

    def send_value(self, status, value, headers=None):
        payload = value if isinstance(value, bytes) else json_bytes(value)
        self.send_response(status)
        content_type = "application/octet-stream" if isinstance(value, bytes) else "application/json"
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        for key, val in (headers or {}).items():
            self.send_header(key, val)
        self.end_headers()
        if payload:
            self.wfile.write(payload)

    def do_GET(self):
        if self.path == "/captures":
            return self.send_value(200, {"captures": CAPTURES})
        if ROLE == "origin":
            record(self)
            return self.send_value(200, {"ok": True, "role": ROLE, "status": "ok", "headers": safe_headers(self.headers)})
        if self.path == "/health":
            return self.send_value(200, {"role": ROLE, "status": "ok"})
        if ROLE == "dandan-upstream":
            return self.dandan_get()
        if ROLE == "dandan-relay":
            return self.relay()
        if ROLE == "opensubtitles":
            return self.opensubtitles_get()
        return self.send_value(404, {"error": "not_found"})

    def do_POST(self):
        body = self.read_body()
        if ROLE == "origin":
            record(self, body)
            return self.send_value(200, {"ok": True, "headers": safe_headers(self.headers)})
        if ROLE == "dandan-upstream":
            return self.dandan_post(body)
        if ROLE == "dandan-relay":
            return self.relay(body)
        if ROLE == "opensubtitles":
            return self.opensubtitles_post(body)
        return self.send_value(404, {"error": "not_found"})

    def do_DELETE(self):
        if ROLE == "opensubtitles" and self.path.startswith("/api/v1/logout"):
            record(self)
            return self.send_value(204, b"")
        return self.send_value(404, {"error": "not_found"})

    def require_dandan_auth(self):
        app_id = os.environ.get("DANDAN_APP_ID", "chai-local-fixture")
        secret = read_secret("dandan_app_secret")
        timestamp = self.headers.get("X-App-Timestamp", "")
        signature = self.headers.get("X-App-Signature", "")
        source = f"{app_id}{timestamp}{urlparse(self.path).path}".encode()
        expected = hmac.new(secret.encode(), source, hashlib.sha256).hexdigest()
        return self.headers.get("X-App-Id") == app_id and hmac.compare_digest(signature, expected)

    def dandan_get(self):
        record(self)
        if not self.require_dandan_auth():
            return self.send_value(401, {"success": False, "errorCode": 401, "errorMessage": "fixture auth rejected"})
        parsed = urlparse(self.path)
        if parsed.path == "/api/v2/search/episodes":
            return self.send_value(200, {"success": True, "animes": [{"animeId": 7001, "animeTitle": "Synthetic Series", "episodes": [{"episodeId": 700101, "episodeTitle": "Episode 1"}]}]})
        if parsed.path == "/api/v2/comment/700101":
            return self.send_value(200, {"count": 3, "comments": [
                {"cid": 1, "p": "1.25,1,16777215,fixture", "m": "Synthetic scrolling comment"},
                {"cid": 2, "p": "2.50,5,16711680,fixture", "m": "Synthetic top comment"},
                {"cid": 3, "p": "3.75,4,65280,fixture", "m": "Synthetic bottom comment"},
            ]})
        if parsed.path == "/api/v2/quota":
            return self.send_value(429, {"success": False, "errorCode": 429, "errorMessage": "fixture quota exhausted"}, {"Retry-After": "60"})
        return self.send_value(404, {"success": False, "errorCode": 404})

    def dandan_post(self, body):
        record(self, body)
        if not self.require_dandan_auth():
            return self.send_value(401, {"success": False, "errorCode": 401})
        if urlparse(self.path).path == "/api/v2/match":
            return self.send_value(200, {"success": True, "isMatched": True, "matches": [{"episodeId": 700101, "animeId": 7001, "animeTitle": "Synthetic Series", "episodeTitle": "Episode 1", "type": "hashAndFileName"}]})
        return self.send_value(404, {"success": False, "errorCode": 404})

    def relay(self, body=None):
        record(self, body or b"")
        parsed = urlparse(self.path)
        routes = {
            "/match": ("POST", "/api/v2/match"),
            "/search": ("GET", "/api/v2/search/episodes"),
            "/comments/700101": ("GET", "/api/v2/comment/700101?withRelated=true"),
            "/quota": ("GET", "/api/v2/quota"),
        }
        if parsed.path not in routes:
            return self.send_value(404, {"error": "relay_route_not_allowed"})
        method, upstream_path = routes[parsed.path]
        app_id = os.environ.get("DANDAN_APP_ID", "chai-local-fixture")
        secret = read_secret("dandan_app_secret")
        timestamp = str(int(time.time()))
        source = f"{app_id}{timestamp}{urlparse(upstream_path).path}".encode()
        signature = hmac.new(secret.encode(), source, hashlib.sha256).hexdigest()
        target = urlparse(UPSTREAM)
        conn = http.client.HTTPConnection(target.hostname, target.port, timeout=5)
        headers = {"X-App-Id": app_id, "X-App-Timestamp": timestamp, "X-App-Signature": signature, "Content-Type": "application/json"}
        conn.request(method, upstream_path, body=body if method == "POST" else None, headers=headers)
        response = conn.getresponse()
        payload = response.read(1024 * 1024)
        response_headers = {"Retry-After": response.getheader("Retry-After")} if response.getheader("Retry-After") else None
        self.send_value(response.status, payload, response_headers)

    def require_open_headers(self):
        expected = read_secret("opensubtitles_api_key")
        return self.headers.get("Api-Key") == expected and bool(self.headers.get("User-Agent"))

    def opensubtitles_get(self):
        record(self)
        parsed = urlparse(self.path)
        if parsed.path == "/downloads/9001.srt":
            return self.send_value(200, b"1\n00:00:01,000 --> 00:00:03,000\nSynthetic OpenSubtitles fixture\n")
        if not self.require_open_headers():
            return self.send_value(401, {"message": "missing fixture app identification"})
        if parsed.path == "/api/v1/subtitles":
            data = [{"id": "fixture-subtitle", "type": "subtitle", "attributes": {"language": "en", "hearing_impaired": False, "release": "Synthetic.Release.2026", "files": [{"file_id": 9001, "file_name": "synthetic.en.srt"}]}}]
            return self.send_value(200, {"total_pages": 1, "total_count": 1, "page": 1, "data": data}, {"X-RateLimit-Remaining": "99", "X-RateLimit-Reset": "60"})
        if parsed.path == "/api/v1/infos/user":
            if self.headers.get("Authorization") != "Bearer fixture-jwt":
                return self.send_value(401, {"message": "invalid token"})
            return self.send_value(200, {"data": {"allowed_downloads": 100, "remaining_downloads": 99}})
        if parsed.path == "/api/v1/rate-limited":
            return self.send_value(429, {"message": "fixture rate limit"}, {"Retry-After": "60", "X-RateLimit-Remaining": "0"})
        return self.send_value(404, {"message": "not found"})

    def opensubtitles_post(self, body):
        record(self, body)
        if not self.require_open_headers():
            return self.send_value(401, {"message": "missing fixture app identification"})
        parsed = urlparse(self.path)
        data = json.loads(body or b"{}")
        if parsed.path == "/api/v1/login":
            if data.get("username") != "fixture" or data.get("password") != "fixture-password":
                return self.send_value(401, {"message": "invalid credentials"})
            return self.send_value(200, {"token": "fixture-jwt", "status": 200, "base_url": "http://opensubtitles:8080/api/v1"})
        if parsed.path == "/api/v1/download":
            if data.get("file_id") != 9001:
                return self.send_value(406, {"message": "fixture file unavailable"})
            return self.send_value(200, {"link": "http://127.0.0.1:18082/downloads/9001.srt", "file_name": "synthetic.en.srt", "requests": 1, "remaining": 99, "message": "fixture"})
        return self.send_value(404, {"message": "not found"})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
