"""Small authenticated, anonymous Instagram resolver. No Instagram credentials.

Run behind an HTTPS reverse proxy; PORT is internal HTTP only. The API is not
an arbitrary URL proxy and never downloads media on behalf of callers.
"""
import hmac
import json
import math
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
EXTRACTOR = ROOT / 'android/app/src/main/res/raw/ytdlp'
MAX_RESPONSE = 4 * 1024 * 1024
ID = re.compile(r'[A-Za-z0-9_-]{1,100}')


class Unavailable(Exception):
    pass


def canonical(value):
    if not isinstance(value, str) or len(value) > 2048 or any(ord(c) < 32 for c in value):
        raise ValueError('Use a direct public Instagram post link.')
    u = urlsplit(value)
    if (u.scheme != 'https' or u.hostname not in ('www.instagram.com', 'instagram.com', 'm.instagram.com')
            or u.username is not None or u.password is not None or u.port not in (None, 443)):
        raise ValueError('Use a direct public Instagram post link.')
    match = re.fullmatch(r'/(p|reel|reels|tv)/([A-Za-z0-9_-]{5,80})/?', u.path)
    if not match:
        raise ValueError('Profiles, stories and Threads are not supported.')
    return f'https://www.instagram.com/{"reel" if match[1] == "reels" else match[1]}/{match[2]}/'


def media_url(value):
    if not isinstance(value, str) or len(value) > 16384 or any(ord(c) < 32 for c in value):
        raise ValueError('Invalid media address.')
    u = urlsplit(value)
    host = u.hostname or ''
    if (u.scheme != 'https' or u.username is not None or u.password is not None or u.port not in (None, 443) or u.fragment
            or not any(host == d or host.endswith('.' + d) for d in ('cdninstagram.com', 'fbcdn.net', 'instagram.com'))):
        raise ValueError('Unexpected media address.')
    return value


def text(value, limit=300):
    return ''.join(c for c in str(value or '') if ord(c) >= 32)[:limit]


def number(value):
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and 0 <= value <= 10**15 else 0


def sanitize(raw, url):
    """Construct fresh yt-dlp info; never pass postprocessors/paths/headers through."""
    url = canonical(url)
    post_id = urlsplit(url).path.split('/')[-2]
    root_url = raw.get('webpage_url') or raw.get('original_url')
    if root_url and canonical(root_url) != url:
        # /p and /reel may identify the same media.
        if urlsplit(canonical(root_url)).path.split('/')[-2] != post_id:
            raise ValueError('The server returned a different post.')
    nodes = raw.get('entries', [raw])
    if not isinstance(nodes, list) or not 1 <= len(nodes) <= 20:
        raise ValueError('Invalid post item count.')
    entries = []
    for node in nodes:
        identity = str(node.get('id', ''))
        if not ID.fullmatch(identity):
            raise ValueError('Missing media identity.')
        kind = node.get('flux_media_kind', 'video')
        if kind not in ('photo', 'video'):
            raise ValueError('Invalid media type.')
        formats = []
        for f in node.get('formats', []):
            if not ID.fullmatch(str(f.get('format_id', ''))):
                continue
            ext = f.get('ext')
            if ext not in (('jpg', 'jpeg', 'png', 'webp') if kind == 'photo' else ('mp4', 'webm', 'm4a', 'mov')):
                continue
            # Direct HTTPS only in v1. Fail explicitly if the source supplies
            # only manifests rather than accepting unvalidated segment URLs.
            if f.get('protocol', 'https') != 'https' or f.get('fragments'):
                continue
            try:
                address = media_url(f.get('url'))
            except ValueError:
                continue
            clean = {k: number(f.get(k)) for k in ('width', 'height', 'fps', 'abr', 'tbr', 'asr', 'audio_channels', 'filesize', 'filesize_approx')}
            clean.update(format_id=str(f['format_id']), ext=ext, url=address, protocol='https',
                         vcodec=text(f.get('vcodec') or ('mjpeg' if kind == 'photo' else 'none'), 100),
                         acodec=text(f.get('acodec') or 'none', 100),
                         http_headers={'Referer': 'https://www.instagram.com/'})
            formats.append(clean)
        if not formats or len(formats) > 100 or len({f['format_id'] for f in formats}) != len(formats):
            raise Unavailable('The server could not retrieve supported downloadable formats for this public post.')
        try:
            thumbnail = media_url(node.get('thumbnail', ''))
        except ValueError:
            thumbnail = ''
        entries.append(dict(id=identity, title=text(node.get('title')) or 'Instagram post',
                            uploader=text(node.get('uploader'), 100), thumbnail=thumbnail,
                            duration=number(node.get('duration')), formats=formats, flux_media_kind=kind,
                            webpage_url=url, original_url=url, extractor='instagram', extractor_key='Instagram'))
    if len({e['id'] for e in entries}) != len(entries):
        raise ValueError('Duplicate post item identities.')
    return entries[0] if len(entries) == 1 else dict(_type='playlist', id=post_id, entries=entries, webpage_url=url)


def extract(url):
    """Bound subprocess duration and disk output. Never log signed CDN addresses."""
    with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
        command = [sys.executable, str(Path(__file__).with_name('extract.py')), str(EXTRACTOR), '--ignore-config', '--no-plugin-dirs', '--no-cache-dir',
                   '--skip-download', '--dump-single-json', '--no-playlist', '--socket-timeout', '12',
                   '--retries', '0', '--extractor-retries', '0', '--', canonical(url)]
        try:
            result = subprocess.run(command, stdout=output, stderr=errors, timeout=45)
        except subprocess.TimeoutExpired:
            raise Unavailable('Instagram did not respond in time. Retry later.') from None
        if result.returncode:
            raise Unavailable('The server could not retrieve this public post. Instagram may limit this server. No Instagram login is requested.')
        output.seek(0)
        try:
            return sanitize(json.loads(output.read(MAX_RESPONSE + 1)), url)
        except (ValueError, TypeError, AttributeError, KeyError):
            raise Unavailable('Instagram returned incomplete or unsupported post data.') from None


class Service(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 8
    def __init__(self, address, token, resolver=extract):
        if not re.fullmatch(r'[A-Za-z0-9_-]{32,128}', token):
            raise ValueError('FLUX_API_TOKEN must contain 32–128 letters, digits, underscores or hyphens.')
        self.token = token
        self.resolver = resolver
        self.slots = threading.BoundedSemaphore(2)
        self.rate_lock = threading.Lock()
        self.requests = []
        super().__init__(address, Handler)


class Handler(BaseHTTPRequestHandler):
    server_version = 'FluxResolver/1'
    sys_version = ''
    def setup(self):
        super().setup()
        self.connection.settimeout(10)

    def log_message(self, *_):
        pass  # No tokens, source links, response bodies or signed CDN URLs in logs.

    def send_json(self, code, data):
        body = json.dumps(data, allow_nan=False, separators=(',', ':')).encode()
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Connection', 'close')
        self.end_headers()
        self.wfile.write(body)

    def authorized(self):
        return hmac.compare_digest(self.headers.get('Authorization', '').encode(), ('Bearer ' + self.server.token).encode())

    def do_GET(self):
        if self.path == '/healthz':
            self.send_json(200, {'service': 'flux-resolver', 'protocol': 1, 'status': 'running'})
        elif self.path == '/v1/status' and self.authorized():
            self.send_json(200, {'service': 'flux-resolver', 'protocol': 1, 'authentication': 'no-instagram-login'})
        else:
            self.send_json(401 if self.path == '/v1/status' else 404, {'error': 'unauthorized' if self.path == '/v1/status' else 'not_found'})

    def do_POST(self):
        if self.path != '/v1/resolve':
            return self.send_json(404, {'error': 'not_found'})
        if not self.authorized():
            return self.send_json(401, {'error': 'unauthorized', 'message': 'Check the Flux server access key in Settings.'})
        with self.server.rate_lock:
            now = time.monotonic()
            self.server.requests = [t for t in self.server.requests if now - t < 60]
            if len(self.server.requests) >= 20:
                return self.send_json(429, {'error': 'rate_limited', 'message': 'Flux server request limit reached. Wait one minute.'})
            self.server.requests.append(now)
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 1 <= size <= 4096 or self.headers.get('Transfer-Encoding') or self.headers.get_content_type() != 'application/json':
                raise ValueError('Send one Instagram URL as JSON (maximum 4096 bytes).')
            payload = json.loads(self.rfile.read(size))
            if not isinstance(payload, dict) or set(payload) != {'url'}:
                raise ValueError('Only a public Instagram URL is accepted. Cookies and credentials are not accepted.')
            url = canonical(payload['url'])
        except (ValueError, TypeError, KeyError, socket.timeout):
            return self.send_json(400, {'error': 'invalid_request', 'message': 'Use a direct public Instagram Reel or post link; send no Instagram credentials.'})
        if not self.server.slots.acquire(blocking=False):
            return self.send_json(503, {'error': 'busy', 'message': 'Flux server is busy. Retry shortly.'})
        try:
            info = self.server.resolver(url)
            self.send_json(200, {'protocol': 1, 'source_url': url, 'expires_in': 120, 'info': info})
        except Unavailable as error:
            self.send_json(422, {'error': 'source_unavailable', 'message': str(error)})
        except Exception:
            self.send_json(502, {'error': 'resolver_failed', 'message': 'Flux server could not resolve this post. Retry later.'})
        finally:
            self.server.slots.release()


if __name__ == '__main__':
    Service(('0.0.0.0', int(os.environ.get('PORT', '8080'))), os.environ.get('FLUX_API_TOKEN', '')).serve_forever()
