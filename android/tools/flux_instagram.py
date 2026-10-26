"""Photo formats and stable item selection for public Instagram post extraction.

Uses the upstream public post parser and its access checks. No login, cookies,
profile enumeration or alternate/private endpoint is added.
"""
import re
import urllib.parse


def install():
    from yt_dlp.extractor.instagram import InstagramBaseIE
    from yt_dlp.utils import ExtractorError

    media = InstagramBaseIE._extract_product_media
    product = InstagramBaseIE._extract_product

    def extract_media(self, node):
        info = media(self, node)
        # A video's preview image must never become a downloadable photo.
        if node.get('media_type') != 1 or info.get('formats'):
            return info
        formats = []
        for index, image in enumerate(node.get('image_versions2', {}).get('candidates', [])):
            url = image.get('url', '')
            parsed = urllib.parse.urlsplit(url)
            if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password:
                continue
            ext = parsed.path.rsplit('.', 1)[-1].lower()
            if ext == 'jpeg':
                ext = 'jpg'
            if ext not in ('jpg', 'png', 'webp'):
                continue
            width, height = image.get('width'), image.get('height')
            formats.append({'format_id': f'photo-{index}', 'url': url, 'ext': ext,
                            'width': width, 'height': height, 'vcodec': 'mjpeg',
                            'acodec': 'none', 'format_note': 'Original source photo'})
        if formats:
            info.update(formats=formats, flux_media_kind='photo')
        return info

    def extract_product(self, node, *args, **kwargs):
        info = product(self, node, *args, **kwargs)
        selected = self._configuration_arg('flux_item_id', ie_key='Instagram', casesense=True)
        if not selected:
            return info
        item_id = selected[0]
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', item_id):
            raise ExtractorError('Invalid selected Instagram item', expected=True)
        items = info.get('entries', [info])
        match = next((item for item in items if str(item.get('id')) == item_id), None)
        if match is None:
            raise ExtractorError('Selected Instagram item is no longer available. Load the post again.', expected=True)
        return match

    InstagramBaseIE._extract_product_media = extract_media
    InstagramBaseIE._extract_product = extract_product
