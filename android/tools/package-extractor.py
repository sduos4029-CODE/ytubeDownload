"""Repackage the pinned upstream zipapp with the reviewed Flux transport policy.
Usage: python3 tools/package-extractor.py /path/to/upstream-yt-dlp
"""
import hashlib
import io
import sys
import zipfile
from pathlib import Path

VERSION = '2026.08.19'
UPSTREAM_SHA256 = '1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6'
root = Path(__file__).resolve().parent.parent
upstream = Path(sys.argv[1]).read_bytes()
assert hashlib.sha256(upstream).hexdigest() == UPSTREAM_SHA256, 'Unreviewed upstream artifact'
policy = (root / 'tools/flux_network_policy.py').read_text()
for variant in ('main', 'debug'):
    out = io.BytesIO()
    with zipfile.ZipFile(io.BytesIO(upstream)) as source, zipfile.ZipFile(out, 'w') as target:
        for info in source.infolist():
            data = source.read(info.filename)
            if info.filename == '__main__.py':
                data = data.replace(b"    yt_dlp.main()", b"    import flux_network_policy\n    flux_network_policy.install()\n    yt_dlp.main()")
            target.writestr(info, data)
        info = zipfile.ZipInfo('flux_network_policy.py', (2026, 10, 15, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        target.writestr(info, policy.replace('ALLOW_LOOPBACK_TESTS = False', 'ALLOW_LOOPBACK_TESTS = True') if variant == 'debug' else policy)
    data = out.getvalue()
    resource = root / f'app/src/{variant}/res'
    (resource / 'raw').mkdir(parents=True, exist_ok=True)
    (resource / 'values').mkdir(parents=True, exist_ok=True)
    (resource / 'raw/ytdlp').write_bytes(data)
    digest = hashlib.sha256(data).hexdigest()
    (resource / 'values/extractor.xml').write_text(f'<resources><string name="extractor_sha256" translatable="false">{digest}</string><string name="extractor_version" translatable="false">{VERSION}</string></resources>\n')
    print(variant, VERSION, digest)
