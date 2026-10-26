"""Exercise real bundled Instagram parsing without contacting Meta."""
import copy
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'app/src/main/res/raw/ytdlp'))
import flux_instagram
flux_instagram.install()
from yt_dlp import YoutubeDL
from yt_dlp.extractor.instagram import InstagramIE
from yt_dlp.utils import ExtractorError

def photo(pk):
    return {'pk':str(pk),'media_type':1,'image_versions2':{'candidates':[
        {'url':'https://cdn.example.org/large.jpg','width':1440,'height':1920},
        {'url':'https://cdn.example.org/small.webp','width':720,'height':960},
        {'url':'http://cdn.example.org/insecure.jpg','width':9999,'height':9999}]}}

video={'pk':'300','media_type':2,'video_versions':[{'id':'v','url':'https://cdn.example.org/movie.mp4','width':720,'height':1280}], 'image_versions2':photo(0)['image_versions2']}
node={'pk':'100','user':{'username':'fixture'},'carousel_media':[photo(200),video,photo(400)]}
with YoutubeDL({'quiet':True,'no_warnings':True}) as ydl:
    ie=InstagramIE(ydl)
    result=ie._extract_product(copy.deepcopy(node),video_id='fixture',get_comments=False)
    assert len(result['entries'])==3
    assert result['entries'][0]['flux_media_kind']=='photo' and len(result['entries'][0]['formats'])==2
    assert 'flux_media_kind' not in result['entries'][1]
    result.update(extractor='Instagram',extractor_key='Instagram',webpage_url='https://www.instagram.com/p/Fixture01/')
    processed=ydl.process_ie_result(copy.deepcopy(result),download=False)
    assert len(processed['entries'])==3 and processed['entries'][0]['ext']=='jpg'
    item_id=result['entries'][2]['id']
with YoutubeDL({'quiet':True,'extractor_args':{'instagram':{'flux_item_id':[item_id]}}}) as ydl:
    ie=InstagramIE(ydl)
    item=ie._extract_product(copy.deepcopy(node),video_id='fixture',get_comments=False)
    assert item['id']==item_id and item['flux_media_kind']=='photo' and 'entries' not in item
    reordered=copy.deepcopy(node);reordered['carousel_media'].reverse()
    assert ie._extract_product(reordered,video_id='fixture',get_comments=False)['id']==item_id
    missing=copy.deepcopy(node);missing['carousel_media']=missing['carousel_media'][:2]
    try:ie._extract_product(missing,video_id='fixture',get_comments=False)
    except ExtractorError as error:assert 'no longer available' in str(error)
    else:raise AssertionError('Changed post selected another item')
print('PASS: bundled photo formats, HTTPS candidates, mixed carousel order, native metadata processing, stable selection after reorder and missing-item rejection')

# Real bundled CookieJar + authenticated extractor branch, with generated
# credentials and a mocked API response. No fixture credential leaves this host.
import tempfile
with tempfile.TemporaryDirectory() as temp:
    cookiefile=Path(temp)/'session.txt'
    cookiefile.write_text('# Netscape HTTP Cookie File\n.instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\tfixture-session\n')
    with YoutubeDL({'quiet':True,'cookiefile':str(cookiefile)}) as ydl:
        ie=InstagramIE(ydl)
        assert ie._is_logged_in
        assert 'fixture-session' in ydl.cookiejar.get_cookie_header('https://www.instagram.com/')
        assert not ydl.cookiejar.get_cookie_header('https://cdninstagram.com/video.mp4')
        assert not ydl.cookiejar.get_cookie_header('https://youtube.com/')
        assert not ydl.cookiejar.get_cookie_header('http://www.instagram.com/')
        from yt_dlp.extractor.instagram import _id_to_pk
        api={'pk':str(_id_to_pk('DezDcSUR2Gg')),'media_type':2,'user':{'username':'fixture'},
             'video_versions':[{'id':'video','url':'https://cdn.example.org/fixture.mp4','width':720,'height':1280}]}
        calls=[]
        def response(url,*args,**kwargs):
            calls.append(url)
            assert '/media/' in url and url.endswith('/info/')
            return {'items':[api]}
        ie._download_json=response
        info=ie._real_extract('https://www.instagram.com/reel/DezDcSUR2Gg/')
        assert info['id']=='DezDcSUR2Gg' and info['formats'] and len(calls)==1
print('PASS: bundled CookieJar scopes session to Instagram HTTPS and saved connection selects authenticated media-info API without network access')
