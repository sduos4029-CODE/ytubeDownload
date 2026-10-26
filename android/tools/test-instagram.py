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
