import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'
import {validateWebCacheDeployment as validate} from './web-cache-deployment-policy.mjs'
const preview={enabled:'true',origin:'https://preview.geupddong.com',secretName:'WEB_CACHE_REVALIDATION_SECRET',secret:'a'.repeat(64)}
const production={...preview,origin:'https://geupddong.com',secretName:'WEB_CACHE_PRODUCTION_REVALIDATION_SECRET'}
test('preview retains existing key selection',()=>assert.deepEqual(validate(preview),{enabled:true,target:'preview'}))
test('production requires its dedicated key',()=>assert.deepEqual(validate(production),{enabled:true,target:'production'}))
test('unconfigured disabled sender remains supported',()=>assert.deepEqual(validate({}),{enabled:false,target:'disabled'}))
test('enabled sender rejects missing origin',()=>assert.throws(()=>validate({...preview,origin:''})))
test('production never silently falls back to preview secret',()=>{
  assert.throws(()=>validate({...production,secret:''}))
  assert.throws(()=>validate({...production,secretName:preview.secretName}))
})
test('preview cannot use production secret name',()=>assert.throws(()=>validate({...preview,secretName:production.secretName})))
test('rejects deceptive origins, paths, credentials and whitespace',()=>{
  for(const origin of ['http://geupddong.com','https://geupddong.com.evil.test','https://geupddong.com/path',
    'https://geupddong.com/','https://geupddong.com?x=1','https://geupddong.com#x',
    'https://user@geupddong.com','https://geupddong.com@evil.test',' https://geupddong.com',"https://geupddong.com\nEOF"]) assert.throws(()=>validate({...production,origin}))
})
test('rejects invalid flags and shell-interpretable key material without echoing it',()=>{
  for(const enabled of ['yes','TRUE','1','']) assert.throws(()=>validate({...preview,enabled}))
  for(const secret of ['', 'short', 'a'.repeat(64)+'\n', '$('+ 'a'.repeat(40)+')', 'a'.repeat(513)]) {
    assert.throws(()=>validate({...preview,secret}),error=>!secret || !error.message.includes(secret))
  }
})
test('disabled sender also refuses unsafe supplied interpolation values',()=>{
  assert.throws(()=>validate({...preview,enabled:'false',origin:'wrong'}))
  assert.throws(()=>validate({...preview,enabled:'false',secret:'unsafe\nvalue'}))
})
test('workflow preflight and actual injection use identical no-value-fallback selection',async()=>{
  const workflow=await readFile(new URL('../.github/workflows/deploy.yml',import.meta.url),'utf8')
  const expression="secrets[vars.WEB_CACHE_ORIGIN == 'https://geupddong.com' && 'WEB_CACHE_PRODUCTION_REVALIDATION_SECRET' || 'WEB_CACHE_REVALIDATION_SECRET']"
  assert.equal(workflow.split(expression).length-1,2)
  assert.ok(workflow.indexOf('node scripts/check-web-cache-deployment.mjs')<workflow.indexOf('Log in to Docker Hub'))
  assert.ok(workflow.indexOf('node scripts/check-web-cache-deployment.mjs')<workflow.indexOf('Deploy to Mini PC via SSH'))
})
