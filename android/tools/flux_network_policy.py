"""Flux network policy for the bundled, APK-authenticated extractor.

Only the urllib handler is enabled. Plain HTTP is rejected both at request
creation and at the HTTP connection layer, including redirect requests.
The debug resource enables loopback HTTP fixtures; the release resource never does.
"""
import http.client
import ssl
import urllib.parse

ALLOW_LOOPBACK_TESTS = False


def check_url(url):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme.lower() in ('http', 'ftp', 'ws'):
        if ALLOW_LOOPBACK_TESTS and parsed.scheme.lower() == 'http' and parsed.hostname == '127.0.0.1':
            return
        raise ssl.SSLError('Flux requires HTTPS for network requests')


def install():
    from yt_dlp.networking.common import Request, _REQUEST_HANDLERS
    from yt_dlp.networking._urllib import UrllibRH
    _REQUEST_HANDLERS.clear()
    _REQUEST_HANDLERS[UrllibRH.RH_KEY] = UrllibRH

    original_url = Request.url

    def set_url(self, url):
        original_url.fset(self, url)
        check_url(self.url)

    Request.url = property(original_url.fget, set_url)
    original_putrequest = http.client.HTTPConnection.putrequest

    def putrequest(self, method, url, *args, **kwargs):
        if not isinstance(self, http.client.HTTPSConnection):
            if not (ALLOW_LOOPBACK_TESTS and self.host == '127.0.0.1'):
                raise ssl.SSLError('Flux requires HTTPS, including redirects')
        return original_putrequest(self, method, url, *args, **kwargs)

    http.client.HTTPConnection.putrequest = putrequest
