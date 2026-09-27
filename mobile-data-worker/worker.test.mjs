import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import worker, { validManifest } from './worker.mjs';
const version='20260927T000000Z-0123456789abcdef';
const artifact={key:`full/${version}.sqlite.gz`,bytes:1000,gzipBytes:3,md5:'a'.repeat(32),sha256:'b'.repeat(64),gzipSha256:createHash('sha256').update('abc').digest('hex'),createdAt:'2026-09-27T00:00:00Z'};
const manifest=()=>({schemaVersion:1,version,count:3,locales:['ko','en','ja','zh-cn','zh-tw','zh-hk'],full:artifact,deltas:[]});
function harness() {
 const objects=new Map(); let sequence=0;
 const wrap=o=>o&&({...o,body:new Blob([o.raw]).stream(),json:async()=>JSON.parse(o.raw.toString()),writeHttpMetadata:headers=>{
   for(const [key,value] of Object.entries(o.httpMetadata??{})) headers.set(({contentType:'Content-Type',contentEncoding:'Content-Encoding'})[key],value);
 }});
 const bucket={get:async k=>wrap(objects.get(k)),head:async k=>wrap(objects.get(k)),put:async(k,body,options={})=>{
  const old=objects.get(k), cond=options.onlyIf;
  if ((cond?.etagDoesNotMatch==='*'&&old)||(cond?.etagMatches&&old?.etag!==cond.etagMatches)) return null;
  const raw=typeof body==='string'?Buffer.from(body):Buffer.from(await new Response(body).arrayBuffer());
  if(options.sha256&&createHash('sha256').update(raw).digest('hex')!==options.sha256) throw new Error('checksum mismatch');
  const result={key:k,raw,size:raw.length,etag:String(++sequence),httpEtag:`"${sequence}"`,uploaded:new Date(),...options}; objects.set(k,result); return result;
 },list:async({prefix})=>({objects:[...objects.values()].filter(o=>o.key.startsWith(prefix)),truncated:false}),delete:async keys=>keys.forEach(k=>objects.delete(k))};
 globalThis.caches={default:{match:async()=>null,put:async()=>{}}};
 const request=(path,method='GET',body,headers={})=>worker.fetch(new Request('https://catalog.example/'+path,{method,body,...(body?{duplex:'half'}:{}),headers}),{CATALOG:bucket,PUBLISH_TOKEN:'test-secret'},{waitUntil:()=>{}});
 const admin=(path,method='GET',body,headers={})=>request('admin/'+path,method,body,{Authorization:'Bearer test-secret',...headers});
 return {objects,bucket,request,admin};
}
test('manifest includes separate mainland, Taiwan and Hong Kong translations',()=>{
 assert.equal(validManifest(manifest()),true);
 const m=manifest(); m.locales.pop(); assert.equal(validManifest(m),false);
});
test('publishing and pruning require credentials; arbitrary public paths are rejected',async()=>{
 const h=harness(); assert.equal((await h.request('admin/prune','POST')).status,401);
 assert.equal((await h.request('private/accounts.json')).status,404);
});
test('manifest cannot appear before verified artifacts, uploads immutable, stale publishers rejected',async()=>{
 const h=harness(); const body=JSON.stringify(manifest());
 assert.equal((await h.admin('commit','POST',body)).status,409);
 assert.equal((await h.admin('object/'+artifact.key,'PUT','abc',{'Content-Length':'3','X-Content-SHA256':artifact.gzipSha256})).status,200);
 assert.equal((await h.admin('object/'+artifact.key,'PUT','xyz',{'Content-Length':'3','X-Content-SHA256':'c'.repeat(64)})).status,409);
 assert.equal((await h.admin('commit','POST',body)).status,200);
 assert.equal((await h.admin('commit','POST',body)).status,409);
 const response=await h.request(artifact.key); assert.equal(response.headers.get('Content-Encoding'),'gzip');
 assert.match(response.headers.get('Cache-Control'),/immutable/);
 assert.equal((await h.request('latest.json')).headers.get('Cache-Control'),'public,max-age=60');
});
test('pruning keeps both referenced full copies and recent in-flight uploads',async()=>{
 const h=harness(), m=manifest(); m.previousFull={...artifact,key:artifact.key.replace('20260927','20260926')};
 await h.bucket.put('latest.json',JSON.stringify(m));
 for(const key of [m.full.key,m.previousFull.key,artifact.key.replace('20260927','20260925'),artifact.key.replace('20260927','20260924')]) await h.bucket.put(key,'abc');
 for(const o of h.objects.values()) o.uploaded=new Date(Date.now()-72*3600_000);
 const recent=artifact.key.replace('20260927','20260924'); h.objects.get(recent).uploaded=new Date();
 assert.equal((await h.admin('prune','POST')).status,200);
 assert.ok(h.objects.has(m.full.key)); assert.ok(h.objects.has(m.previousFull.key)); assert.ok(h.objects.has(recent));
 assert.equal(h.objects.has(artifact.key.replace('20260927','20260925')),false);
});
