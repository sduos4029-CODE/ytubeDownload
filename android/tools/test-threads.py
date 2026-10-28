"""Offline exact-post, CDN policy, media identity and carousel regressions."""
import copy
import json
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent/'app/src/main/res/raw/ytdlp'))
from flux_threads import FluxThreadsIE, install, media_url
from yt_dlp import YoutubeDL
from yt_dlp.utils import ExtractorError
install()
URL = 'https://www.threads.com/@creator/post/Fixture01'

def photo(pk):
    return {'pk': str(pk), 'media_type': 1, 'image_versions2': {'candidates': [
        {'url': 'https://scontent.cdninstagram.com/pic.jpg', 'width': 1080, 'height': 1920},
        {'url': 'http://scontent.cdninstagram.com/pic.jpg'},
        {'url': 'https://cdninstagram.com.evil.invalid/pic.jpg'}]}}

video = {'pk': '200', 'media_type': 2,
         'video_versions': [{'url': 'https://video.fbcdn.net/source.mp4'}],
         'image_versions2': photo(1)['image_versions2']}
post = {'code': 'Fixture01', 'user': {'username': 'creator'}, 'caption': {'text': 'Public fixture'},
        'carousel_media': [photo(100), video]}

def page(p):
    unrelated = copy.deepcopy(p)
    unrelated['code'] = 'OtherPost'
    return '<script type="application/json">' + json.dumps({'recommended': unrelated, 'post': p}) + '</script>'

with YoutubeDL({'quiet': True}) as ydl:
    assert isinstance(ydl.get_info_extractor('FluxThreads'), FluxThreadsIE)
    ie = FluxThreadsIE(ydl)
    result = ie.parse_page(page(post), URL)
    assert len(result['entries']) == 2
    assert result['entries'][0]['id'] == 'Fixture01_100'
    assert len(result['entries'][0]['formats']) == 1
    assert result['entries'][1]['flux_media_kind'] == 'video'
    fmt = result['entries'][1]['formats'][0]
    assert fmt['width'] is None and fmt['height'] is None, 'Cover dimensions must never label video quality'
    result.update(extractor='FluxThreads', extractor_key='FluxThreads', webpage_url=URL)
    processed = ydl.process_ie_result(copy.deepcopy(result), download=False)
    assert processed['entries'][0]['ext'] == 'jpg'
    assert processed['entries'][1]['ext'] == 'mp4'
    for bad in [dict(post, code='WrongPost'), dict(post, user={'username': 'other'}), dict(post, carousel_media=[dict(video, video_versions=[])])]:
        try:
            ie.parse_page(page(bad), URL)
        except ExtractorError:
            pass
        else:
            raise AssertionError('Unavailable/mismatched media accepted')
with YoutubeDL({'quiet': True, 'extractor_args': {'fluxthreads': {'flux_item_id': ['Fixture01_200']}}}) as ydl:
    ie = FluxThreadsIE(ydl)
    assert ie.parse_page(page(post), URL)['id'] == 'Fixture01_200'
    reordered = copy.deepcopy(post)
    reordered['carousel_media'].reverse()
    assert ie.parse_page(page(reordered), URL)['id'] == 'Fixture01_200'
    removed = copy.deepcopy(post)
    removed['carousel_media'] = removed['carousel_media'][:1]
    try:
        ie.parse_page(page(removed), URL)
    except ExtractorError:
        pass
    else:
        raise AssertionError('Missing item silently substituted')
for url in ['http://video.fbcdn.net/file.mp4', 'https://fbcdn.net.evil.invalid/x', 'https://user:pass@video.fbcdn.net/x', 'https://127.0.0.1/x', 'https://video.fbcdn.net:444/x']:
    assert media_url(url) is None
print('PASS: Threads extractor registration, exact shortcode/author, recommended-post exclusion, thumbnail/video dimension separation, HTTPS CDN allowlist, mixed carousel processing, stable item selection and missing-item rejection')
