"""Record live availability separately from deterministic fixture test results."""
import json
import secrets
import threading
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from flux_server import Service

token = secrets.token_urlsafe(32)
service = Service(('127.0.0.1', 0), token)
thread = threading.Thread(target=service.serve_forever, daemon=True)
thread.start()
try:
    results = []
    for identity in ('DezDcSUR2Gg', 'Chunk8-jurw'):
        source = f'https://www.instagram.com/reel/{identity}/'
        request = Request(f'http://127.0.0.1:{service.server_port}/v1/resolve',
                          data=json.dumps({'url': source}).encode(),
                          headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token})
        try:
            with urlopen(request, timeout=60) as response:
                body = json.load(response)
            nodes = body['info'].get('entries', [body['info']])
            results.append({'post': identity, 'result': 'METADATA_RETRIEVED',
                            'items': len(nodes), 'formats': sum(len(x['formats']) for x in nodes),
                            'thumbnail_url_present': bool(nodes[0].get('thumbnail'))})
        except HTTPError as error:
            body = json.load(error)
            results.append({'post': identity, 'result': 'UNAVAILABLE', 'http': error.code,
                            'error': body.get('error'), 'message': body.get('message')})
        except Exception as error:
            results.append({'post': identity, 'result': 'CHECK_FAILED', 'type': type(error).__name__})
    print(json.dumps({'scope': 'CI server only; production host and user phone remain unverified',
                      'instagram_credentials': False, 'checks': results}, indent=2))
finally:
    service.shutdown(); service.server_close(); thread.join()
