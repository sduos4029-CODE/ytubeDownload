import copy
import json
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from flux_server import Service, Unavailable, canonical, sanitize

URL = 'https://www.instagram.com/reel/DezDcSUR2Gg/'
TOKEN = 'fixture_' + 'a' * 36


def info():
    return {'id': 'DezDcSUR2Gg', 'webpage_url': URL, 'title': 'Test',
            'formats': [{'format_id': 'video', 'ext': 'mp4', 'vcodec': 'avc1', 'acodec': 'mp4a',
                         'url': 'https://scontent.cdninstagram.com/test.mp4', 'height': 1080}],
            'thumbnail': 'https://scontent.cdninstagram.com/test.jpg'}


class Safety(unittest.TestCase):
    def test_canonical_and_rejected_inputs(self):
        self.assertEqual(canonical(URL + '?psln=test'), URL)
        for value in ('http://www.instagram.com/reel/DezDcSUR2Gg/', 'https://www.instagram.com.evil.invalid/reel/DezDcSUR2Gg/',
                      'https://www.instagram.com@127.0.0.1/reel/DezDcSUR2Gg/', 'file:///etc/passwd',
                      'https://www.instagram.com:444/reel/DezDcSUR2Gg/', 'https://www.instagram.com/stories/person/123/',
                      'https://www.instagram.com/person/', 'https://threads.net/test', URL + '\n'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                canonical(value)

    def test_rebuild_metadata_and_discard_commands_credentials(self):
        raw = info()
        raw.update(postprocessors=[{'key': 'Exec', 'exec_cmd': 'bad'}], _filename='/etc/passwd', cookies='secret')
        raw['formats'][0]['http_headers'] = {'Authorization': 'secret', 'Cookie': 'secret'}
        safe = sanitize(raw, URL)
        encoded = json.dumps(safe)
        self.assertNotIn('secret', encoded)
        self.assertNotIn('postprocessors', encoded)
        self.assertNotIn('_filename', encoded)
        self.assertEqual(safe['formats'][0]['http_headers'], {'Referer': 'https://www.instagram.com/'})

    def test_reject_malicious_or_manifest_media_and_wrong_identity(self):
        for address in ('http://scontent.cdninstagram.com/test.mp4', 'https://127.0.0.1/test.mp4',
                        'https://cdninstagram.com.evil.invalid/test.mp4', 'https://user:pass@cdninstagram.com/test.mp4',
                        'https://scontent.fbcdn.net:444/test.mp4', 'file:///etc/passwd'):
            raw = info(); raw['formats'][0]['url'] = address
            with self.subTest(address=address), self.assertRaises(Unavailable):
                sanitize(raw, URL)
        raw = info(); raw['webpage_url'] = 'https://www.instagram.com/reel/OtherPost/'
        with self.assertRaises(ValueError):
            sanitize(raw, URL)
        raw = info(); raw['formats'][0]['protocol'] = 'm3u8_native'
        with self.assertRaises(Unavailable):
            sanitize(raw, URL)

    def test_carousel_identity_photo_and_bounds(self):
        photo = info(); photo.update(id='Photo01', flux_media_kind='photo')
        photo['formats'][0].update(ext='jpg', url='https://scontent.fbcdn.net/photo.jpg', vcodec='mjpeg', acodec='none')
        result = sanitize({'entries': [info(), photo]}, URL)
        self.assertEqual(result['entries'][1]['flux_media_kind'], 'photo')
        with self.assertRaises(ValueError):
            sanitize({'entries': [info(), info()]}, URL)
        with self.assertRaises(ValueError):
            sanitize({'entries': [info()] * 21}, URL)


class API(unittest.TestCase):
    def setUp(self):
        self.calls = []
        def resolver(url):
            self.calls.append(url)
            return sanitize(info(), url)
        self.server = Service(('127.0.0.1', 0), TOKEN, resolver)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown(); self.server.server_close(); self.thread.join()

    def request(self, path='/v1/resolve', body=None, token=TOKEN):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        data = json.dumps(body).encode() if body is not None else None
        try:
            response = urlopen(Request(self.base + path, data=data, headers=headers), timeout=5)
        except HTTPError as error:
            response = error
        return response.code, json.load(response), response.headers

    def test_health_auth_and_identity(self):
        code, data, _ = self.request('/healthz', token=None)
        self.assertEqual((code, data['status']), (200, 'running'))
        self.assertEqual(self.request('/v1/status', token=None)[0], 401)
        self.assertEqual(self.request('/v1/status')[1]['protocol'], 1)
        code, result, headers = self.request(body={'url': URL + '?psln=test'})
        self.assertEqual(code, 200); self.assertEqual(result['source_url'], URL)
        self.assertEqual(self.calls, [URL]); self.assertEqual(headers['Cache-Control'], 'no-store')

    def test_reject_before_extraction(self):
        self.assertEqual(self.request(body={'url': URL}, token='wrong')[0], 401)
        for body in ({'url': URL, 'cookies': 'not accepted'}, {'url': 'https://127.0.0.1/'}, ['invalid'], {'url': 'x' * 5000}):
            self.assertEqual(self.request(body=body)[0], 400)
        self.assertEqual(self.calls, [])

    def test_rate_busy_unavailable(self):
        self.server.requests = [__import__('time').monotonic()] * 20
        self.assertEqual(self.request(body={'url': URL})[0], 429)
        self.server.requests = []
        self.server.slots.acquire(); self.server.slots.acquire()
        self.assertEqual(self.request(body={'url': URL})[0], 503)
        self.server.slots.release(); self.server.slots.release()
        def fail(_):
            raise Unavailable('Instagram did not return media.')
        self.server.resolver = fail
        self.assertEqual(self.request(body={'url': URL})[0], 422)


if __name__ == '__main__':
    unittest.main()
