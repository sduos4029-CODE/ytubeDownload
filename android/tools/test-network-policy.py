"""Validate release transport policy without downloading third-party content."""
import http.client
import importlib
import ssl
import sys
from pathlib import Path

archive = Path(__file__).resolve().parent.parent / 'app/src/main/res/raw/ytdlp'
sys.path.insert(0, str(archive))
policy = importlib.import_module('flux_network_policy')
assert policy.ALLOW_LOOPBACK_TESTS is False
policy.install()
from yt_dlp.networking.common import Request, _REQUEST_HANDLERS
for url in ['http://example.org/video', 'http://127.0.0.1/video', '//example.org/video', 'ftp://example.org/video', 'ws://example.org/video']:
    try:
        Request(url)
    except ssl.SSLError:
        pass
    else:
        raise AssertionError(f'Unsafe URL accepted: {url}')
assert Request('https://www.youtube.com/watch?v=test').url.startswith('https://')
request = Request('https://example.org/video')
try:
    request.url = 'http://example.org/redirected'
except ssl.SSLError:
    pass
else:
    raise AssertionError('Downgrade accepted')
try:
    http.client.HTTPConnection('example.org').putrequest('GET', '/')
except ssl.SSLError:
    pass
else:
    raise AssertionError('Plain HTTP connection accepted')
assert set(_REQUEST_HANDLERS) == {'Urllib'}, _REQUEST_HANDLERS
print('PASS: release rejects HTTP, loopback HTTP, FTP, WS, scheme-relative HTTP and HTTPS downgrade; only guarded urllib enabled')
