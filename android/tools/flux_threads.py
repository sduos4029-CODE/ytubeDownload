"""Anonymous public Threads post extraction. No third-party resolver or session."""
import json
import re
from html.parser import HTMLParser
from urllib.parse import urlsplit

from yt_dlp.extractor.common import InfoExtractor
from yt_dlp.utils import ExtractorError, int_or_none


class Scripts(HTMLParser):
    def __init__(self):
        super().__init__()
        self.active = False
        self.current = []
        self.payloads = []

    def handle_starttag(self, tag, attrs):
        if tag == 'script':
            self.active = dict(attrs).get('type') == 'application/json'
            self.current = []

    def handle_data(self, data):
        if self.active:
            self.current.append(data)

    def handle_endtag(self, tag):
        if tag == 'script' and self.active:
            self.payloads.append(''.join(self.current))
            self.active = False


def media_url(value):
    if not isinstance(value, str):
        return None
    u = urlsplit(value)
    host = (u.hostname or '').lower()
    if (u.scheme == 'https' and not u.username and not u.password
            and u.port in (None, 443)
            and any(host.endswith('.' + suffix) or host == suffix
                    for suffix in ('cdninstagram.com', 'fbcdn.net'))):
        return value


class FluxThreadsIE(InfoExtractor):
    IE_NAME = 'FluxThreads'
    _VALID_URL = r'https://(?:www\.)?threads\.(?:com|net)/@(?P<user>[A-Za-z0-9_.]{1,30})/post/(?P<id>[A-Za-z0-9_-]{5,80})/?(?:[?#]|$)'

    def parse_page(self, html, url):
        match = self._match_valid_url(url)
        code, username = match.group('id', 'user')
        parser = Scripts()
        parser.feed(html)
        candidates = []
        for payload in parser.payloads:
            try:
                stack = [json.loads(payload)]
            except (ValueError, RecursionError):
                continue
            while stack:
                node = stack.pop()
                if isinstance(node, dict):
                    if (node.get('code') == code and isinstance(node.get('user'), dict)
                            and node['user'].get('username', '').lower() == username.lower()
                            and (node.get('video_versions') or node.get('image_versions2') or node.get('carousel_media'))):
                        candidates.append(node)
                    stack.extend(v for v in node.values() if isinstance(v, (list, dict)))
                elif isinstance(node, list):
                    stack.extend(v for v in node if isinstance(v, (list, dict)))
        if not candidates:
            raise ExtractorError('Threads returned no media for this exact public post. It may be limited, unavailable, or its page format changed. No login or Flux server was used.', expected=True)
        post = candidates[0]
        nodes = post.get('carousel_media') or [post]
        if not 1 <= len(nodes) <= 20:
            raise ExtractorError('Threads post exceeds the supported 20 items.', expected=True)
        entries = []
        for node in nodes:
            pk = str(node.get('pk') or node.get('id') or '')
            if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', pk):
                raise ExtractorError('Threads item identity is missing.', expected=True)
            item_id = f'{code}_{pk}'
            images = node.get('image_versions2', {}).get('candidates', [])
            thumbnails = [{'url': u, 'width': int_or_none(i.get('width')), 'height': int_or_none(i.get('height'))}
                          for i in images if (u := media_url(i.get('url')))]
            formats = []
            seen = set()
            photo = node.get('media_type') == 1
            sources = images if photo else node.get('video_versions', [])
            for source in sources:
                u = media_url(source.get('url'))
                if not u or u in seen:
                    continue
                seen.add(u)
                ext = urlsplit(u).path.rsplit('.', 1)[-1].lower()
                if photo and ext not in ('jpg', 'jpeg', 'png', 'webp'):
                    continue
                formats.append({'format_id': f'{"photo" if photo else "video"}-{len(formats)}',
                                'url': u, 'ext': 'jpg' if ext == 'jpeg' else ext if photo else 'mp4',
                                'width': int_or_none(source.get('width')), 'height': int_or_none(source.get('height')),
                                'vcodec': 'mjpeg' if photo else 'unknown', 'acodec': 'none' if photo else 'unknown'})
            if not formats:
                raise ExtractorError('Threads returned no supported media for a post item.', expected=True)
            caption = (post.get('caption') or {}).get('text') or f'Threads post {code}'
            entries.append({'id': item_id, 'title': caption[:180], 'uploader': username,
                            'webpage_url': url, 'formats': formats, 'thumbnails': thumbnails,
                            'duration': node.get('video_duration'), 'flux_media_kind': 'photo' if photo else 'video'})
        selected = self._configuration_arg('flux_item_id', casesense=True)
        if selected:
            result = next((e for e in entries if e['id'] == selected[0]), None)
            if result is None:
                raise ExtractorError('Selected Threads item is no longer available. Load the post again.', expected=True)
            return result
        return entries[0] if len(entries) == 1 else self.playlist_result(entries, code)

    def _real_extract(self, url):
        m = self._match_valid_url(url)
        canonical = f'https://www.threads.com/@{m.group("user")}/post/{m.group("id")}'
        html = self._download_webpage(canonical, m.group('id'), headers={'User-Agent': 'Mozilla/5.0'})
        return self.parse_page(html, canonical)


def install():
    from yt_dlp.extractor import import_extractors
    from yt_dlp.globals import extractors
    import_extractors()
    extractors.value = {'FluxThreadsIE': FluxThreadsIE, **extractors.value}
