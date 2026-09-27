"""Publish immutable artifacts first, then conditionally replace the manifest."""
import argparse
import gzip
import hashlib
import json
import os
import urllib.error
import urllib.request
from pathlib import Path
from catalog import build, canonical

def request(origin, path, token=None, method='GET', body=None, headers=None):
    request_headers = {'User-Agent':'geupddong-mobile-catalog/1','Accept-Encoding':'identity', **(headers or {})}
    if token: request_headers['Authorization'] = 'Bearer ' + token
    req = urllib.request.Request(origin+'/'+path,data=body,method=method,headers=request_headers)
    with urllib.request.urlopen(req,timeout=180) as response:
        raw = response.read()
        return gzip.decompress(raw) if response.headers.get('Content-Encoding') == 'gzip' else raw

def publish(source, output, origin, token):
    if not origin.startswith('https://') or '/' in origin[8:]: raise ValueError('HTTPS origin required')
    output = Path(output); output.mkdir(parents=True,exist_ok=True)
    old = None
    try: old = json.loads(request(origin,'admin/latest',token))
    except urllib.error.HTTPError as error:
        if error.code != 404: raise
    previous_db = previous_manifest = None
    if old:
        previous_manifest = output/'previous.json'
        previous_manifest.write_text(canonical(old),encoding='utf-8')
        previous_db = output/'previous.sqlite'
        raw = request(origin,old['full']['key'])
        if len(raw) != old['full']['bytes'] or hashlib.sha256(raw).hexdigest() != old['full']['sha256']: raise ValueError('Previous full snapshot integrity failed')
        previous_db.write_bytes(raw)
    manifest = build(source,output,previous_db,previous_manifest)
    if manifest:
        for entry in [manifest['full'], *[d for d in manifest['deltas'] if d['toVersion']==manifest['version']]]:
            body = (output/entry['key']).read_bytes()
            request(origin,'admin/object/'+entry['key'],token,'PUT',body,{'Content-Type':'application/octet-stream','X-Content-SHA256':entry['gzipSha256']})
        request(origin,'admin/commit',token,'POST',canonical(manifest).encode(),{'Content-Type':'application/json','X-Previous-Version':old['version'] if old else ''})
    # Also prune on unchanged days; grace protects in-progress downloads and stale manifest caches.
    cleanup=json.loads(request(origin,'admin/prune',token,'POST',b''))
    current=manifest or old
    report=dict(changed=bool(manifest),version=current['version'],count=current['count'],locales=current['locales'],
                translationCounts=current['translationCounts'],fullBytes=current['full']['bytes'],downloadBytes=current['full']['gzipBytes'],removed=cleanup['removed'])
    (output/'report.json').write_text(canonical(report)+'\n',encoding='utf-8')
    print(canonical(report))

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('source'); parser.add_argument('output'); args=parser.parse_args()
    publish(args.source,args.output,os.environ['MOBILE_CATALOG_ORIGIN'].rstrip('/'),os.environ['MOBILE_CATALOG_PUBLISH_TOKEN'])
