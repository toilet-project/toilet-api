"""Run on the mini PC. Synthetic private R2 read/write/delete verification, no member data."""
import base64, hashlib, hmac, json, pathlib, time, urllib.request, urllib.error, urllib.parse, uuid, sys
from datetime import datetime, timezone

def run():
    target=pathlib.Path.home()/'.config/geupddong/profile-photo.env'
    assert not target.is_symlink() and target.stat().st_mode & 0o777 == 0o600
    env=dict(line.split('=',1) for line in target.read_text().splitlines() if '=' in line)
    host=urllib.parse.urlsplit(env['PROFILE_PHOTO_R2_ENDPOINT']).hostname
    import re
    assert host and re.fullmatch(r'[a-f0-9]{32}\.r2\.cloudflarestorage\.com',host)
    bucket='geupddong-profile-photos'
    assert env['PROFILE_PHOTO_R2_ENDPOINT']=='https://'+host and env['PROFILE_PHOTO_R2_BUCKET']==bucket
    access,secret=env['PROFILE_PHOTO_R2_ACCESS_KEY_ID'],env['PROFILE_PHOTO_R2_SECRET_ACCESS_KEY']
    sha=lambda value:hashlib.sha256(value).hexdigest()
    mac=lambda key,value:hmac.new(key,value.encode(),hashlib.sha256).digest()
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs): return None
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    def request(method,object_key='',body=b'',query='',signed=True,target_bucket=bucket):
        path='/'+target_bucket+'/'+object_key if object_key else '/'+target_bucket
        stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ'); date=stamp[:8]
        headers={'host':host,'content-type':'image/webp','cache-control':'private, no-store'}
        if signed:
            headers.update({'x-amz-date':stamp,'x-amz-content-sha256':sha(body)})
            names=sorted(headers); scope=date+'/auto/s3/aws4_request'
            canonical='\n'.join([method,path,query,''.join(k+':'+headers[k]+'\n' for k in names),';'.join(names),sha(body)])
            signing=mac(mac(mac(mac(('AWS4'+secret).encode(),date),'auto'),'s3'),'aws4_request')
            signature=mac(signing,'AWS4-HMAC-SHA256\n'+stamp+'\n'+scope+'\n'+sha(canonical.encode())).hex()
            headers['Authorization']='AWS4-HMAC-SHA256 Credential='+access+'/'+scope+', SignedHeaders='+';'.join(names)+', Signature='+signature
        req=urllib.request.Request('https://'+host+path+('?' + query if query else ''),data=body if method=='PUT' else None,headers=headers,method=method)
        start=time.monotonic()
        try:
            with opener.open(req,timeout=12) as response: return response.status,response.read(65537),round((time.monotonic()-start)*1000)
        except urllib.error.HTTPError as error: return error.code,b'',round((time.monotonic()-start)*1000)
    if '--apply-synthetic' not in sys.argv:
        import xml.etree.ElementTree as ET
        status,data,_=request('GET',query='list-type=2&max-keys=100&prefix=verification%2F')
        result={'inventoryStatus':status}
        if status==200:
            tree=ET.fromstring(data)
            result['syntheticObjectCount']=int(tree.findtext('{*}KeyCount'))
        print(json.dumps(result));return
    # Small synthetic WebP; no original/user photograph is sent.
    image=base64.b64decode('UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA')
    key='verification/'+str(uuid.uuid4())+'.webp'
    receipt={'passed':False,'checks':[],'bytes':len(image),'latencyMs':{},'objectKey':key}
    def check(ok,label):
        if not ok: raise RuntimeError(label)
        receipt['checks'].append(label)
    put=False
    try:
        status,_,ms=request('PUT',key,image); check(status==200,'put');put=True;receipt['latencyMs']['put']=ms
        status,data,ms=request('GET',key);check(status==200 and data==image,'byte-match');receipt['latencyMs']['get']=ms
        status,_,_=request('GET',key,signed=False)
        receipt['anonymousStatus']=status
        # R2 S3 may reject an unsigned request as malformed (400) before permission evaluation.
        check(status in (400,401,403),'anonymous-denied')
        status,_,_=request('GET',query='list-type=2&max-keys=1&prefix=verification-'+str(uuid.uuid4()),target_bucket='geupddong-next-production-cache')
        check(status==403,'other-bucket-denied')
    finally:
        if put:
            status,data,_=request('GET',key)
            check(status==200 and data==image,'owned-object-rechecked')
            status,_,ms=request('DELETE',key);check(status==204,'delete');receipt['latencyMs']['delete']=ms
            status,_,_=request('HEAD',key);check(status==404,'deleted-object-absent')
    receipt['passed']=True
    print(json.dumps(receipt))

try: run()
except Exception as error:
    # Never emit response bodies, credentials, URLs or tracebacks.
    label=str(error) if type(error) is RuntimeError and str(error) in ('put','byte-match','anonymous-denied','other-bucket-denied','owned-object-rechecked','delete','deleted-object-absent') else None
    print(json.dumps({'passed':False,'failureType':type(error).__name__,'failedCheck':label}))
    raise SystemExit(1)
