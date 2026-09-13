import {test} from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {renderLocalDeployment} from './render-local-deployment.mjs'
import {renderMaintenancePreparation} from './render-maintenance-deployment.mjs'
const source=readFileSync(new URL('../deploy/us-paused.baseline.yml',import.meta.url),'utf8')
const role=source.includes('name: Toilet API') ? 'api' : 'batch'
test('candidate keeps account actions paused and does not read R2 credentials',()=>{
  const out=renderLocalDeployment(source,role)
  assert.match(out,/ERASURE_LEDGER_PROVIDER: 'LOCAL'/)
  assert.doesNotMatch(out,/secrets.ERASURE_LEDGER_US_RUNTIME|ERASURE_LEDGER_ENDPOINT:|ERASURE_LEDGER_BUCKET:/)
  for(const flag of ['ACCOUNT_RETENTION_ENABLED','ACCOUNT_ERASURE_ENABLED','ERASURE_LEDGER_ENABLED','ERASURE_LEDGER_CATALOGUE_ENABLED','ERASURE_CHECKPOINT_ENABLED'])
    assert.ok(out.includes(`${flag}: 'false'`))
  assert.ok(out.includes("ACCOUNT_LIFECYCLE_MAINTENANCE: 'true'"))
  assert.ok(out.includes("ERASURE_LEDGER_LOCAL_DEPLOYMENT_APPROVED || 'false'"))
  assert.ok(out.includes("vars.ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED_SHA == github.sha"))
})
test('candidate rejects absent preflight before touching configuration and does not auto-create ledger',()=>{
  const out=renderLocalDeployment(source,role)
  assert.ok(out.includes("printf '%s' '${{ steps.lifecycle.outputs.payload }}' | /home/luha/erasure-tools/local-ledger-preflight "+role))
  assert.ok(out.indexOf('/home/luha/erasure-tools/local-ledger-preflight '+role)<out.indexOf('mkdir -p ~/toilet-'))
  assert.match(out,/user: "1000:1000"/)
  assert.match(out,/create_host_path: false/)
  assert.doesNotMatch(out,/chmod 777|chown -R|docker.*prune|docker.*volume rm/)
  assert.match(out,/cp -p -- "\$file" "\$rollback_dir\/"/)
  if(role==='api') assert.match(out,/up -d --no-deps --wait --wait-timeout 120 api/)
  else assert.ok(out.includes('./region-results:/var/lib/toilet-region'))
})
test('API candidate requires the private profile credential file and couples Kakao scope to the feature flag',()=>{
  if(role!=='api') return
  const out=renderLocalDeployment(source,role)
  assert.ok(out.includes("test \"$profile_photo_names\" = 'PROFILE_PHOTO_R2_ACCESS_KEY_ID,PROFILE_PHOTO_R2_BUCKET,PROFILE_PHOTO_R2_ENDPOINT,PROFILE_PHOTO_R2_SECRET_ACCESS_KEY'"))
  assert.ok(out.includes("PROFILE_PHOTO_ENABLED=${{ vars.PROFILE_PHOTO_ENABLED || 'false' }}"))
  assert.ok(out.includes("KAKAO_LOGIN_SCOPES=${{ vars.PROFILE_PHOTO_ENABLED == 'true' && 'profile_nickname,account_email,profile_image' || 'profile_nickname,account_email' }}"))
  assert.ok(out.includes('- /home/luha/.config/geupddong/profile-photo.env'))
  assert.ok(out.indexOf('profile_photo_env=/home/luha/.config/geupddong/profile-photo.env')<out.indexOf('mkdir -p ~/toilet-api'))
})
test('template drift and unknown roles fail closed',()=>{
  assert.throws(()=>renderLocalDeployment(source.replace("'us-runtime'","'other'"),role))
  assert.throws(()=>renderLocalDeployment(source+source,role))
  assert.throws(()=>renderLocalDeployment(source,'admin'))
  assert.equal(renderLocalDeployment(source.replaceAll('\r\n','\n'),role),renderLocalDeployment(source,role))
})

test('active workflow exactly matches reviewed LOCAL preparation candidate',()=>{
  const active=readFileSync(new URL('../.github/workflows/deploy.yml',import.meta.url),'utf8')
  const body=text=>text.replaceAll('\r\n','\n').replace(/^(#.*\n)+/,'').trim()
  assert.equal(body(active),body(renderMaintenancePreparation(renderLocalDeployment(source,role),role)))
  assert.ok(active.includes('branches: [ "main" ]'))
  assert.ok(!active.includes('workflow_dispatch:'))
})
