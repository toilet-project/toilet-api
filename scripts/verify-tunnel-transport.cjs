// Offline only: compare the reviewed baseline and validate shell syntax. Never execute deployment.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {execFileSync, spawnSync} = require('node:child_process');
const yaml = require(process.env.TUNNEL_YAML_MODULE || 'yaml');
const root = path.resolve(__dirname, '..').replaceAll('\\', '/');
const baselineCommit = '9c497083fed491d4195d852b35803cc30a805175';
const baseline = yaml.parse(execFileSync('git', ['-c', 'safe.directory='+root, '-C', root, 'show', baselineCommit+':.github/workflows/deploy.yml'], {encoding:'utf8'}));
const candidate = yaml.parse(fs.readFileSync(path.join(root,'.github/workflows/deploy.yml'),'utf8'));
const {jobs:oldJobs,...oldWorkflow}=baseline;
const {jobs:newJobs,...newWorkflow}=candidate;
assert.deepEqual(oldWorkflow,newWorkflow,'Trigger/permissions/concurrency must not change');
assert.deepEqual(Object.keys(oldJobs),Object.keys(newJobs));
const key=Object.keys(oldJobs)[0];
const {steps:oldSteps,...oldJob}=oldJobs[key];
const {steps:newSteps,...newJob}=newJobs[key];
assert.equal(newJob.if,"github.repository == 'toilet-project/toilet-api' && vars.ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED == 'true' && vars.ACCOUNT_LIFECYCLE_DEPLOYMENT_APPROVED_SHA == github.sha",'Exact commit deploy gate required');
delete newJob.if;
assert.deepEqual(oldJob,newJob);
const i=oldSteps.findIndex(s=>s.uses?.startsWith('appleboy/ssh-action@'));
assert.equal(i,oldSteps.length-1);
// Permit only the reviewed LOCAL preparation delta; all unrelated fields stay pinned.
const lifecycleBaseline = oldSteps.find(s => s.id === 'lifecycle');
assert.ok(lifecycleBaseline, 'Pinned lifecycle preparation step required');
const cacheBaseline = oldSteps.find(s => s.name === 'Validate cache destination and matching signing key');
assert.ok(cacheBaseline, 'Pinned cache deployment validation step required');
cacheBaseline.env.CACHE_CONTRACT_VERSION = "${{ vars.WEB_CACHE_CONTRACT_VERSION || '1' }}";
Object.assign(lifecycleBaseline.env, {
 ERASURE_LEDGER_DEPLOYMENT_PROFILE: 'local-paused',
 ERASURE_LEDGER_PROVIDER: 'LOCAL',
 ERASURE_LEDGER_LOCAL_DEPLOYMENT_APPROVED: "${{ vars.ERASURE_LEDGER_LOCAL_DEPLOYMENT_APPROVED || 'false' }}",
 ERASURE_LEDGER_LOCAL_ACCEPTANCE_VERIFIED: 'false',
 ERASURE_LEDGER_LOCAL_DIRECTORY: '/home/luha/geupddong-erasure-ledger',
 ERASURE_LEDGER_LOCAL_STORE_ID: '${{ vars.ERASURE_LEDGER_LOCAL_STORE_ID }}',
 LOCAL_LEDGER_RUNTIME_UID: '1000',
 LOCAL_LEDGER_RUNTIME_GID: '1000',
});
for (const field of ['ERASURE_LEDGER_ENDPOINT','ERASURE_LEDGER_BUCKET',
 'ERASURE_LEDGER_ACCESS_KEY_ID','ERASURE_LEDGER_SECRET_ACCESS_KEY']) delete lifecycleBaseline.env[field];
assert.deepEqual(oldSteps.slice(0,i),newSteps.slice(0,i),'Build steps must not change');
assert.equal(newSteps.length,oldSteps.length+2);
const [prepare,deploy,cleanup]=newSteps.slice(i);
// Exact allowlisted edits to the old script, NOT a blanket exemption for remote commands.
const role = 'api';
let expectedScript=oldSteps[i].with.script;
function replaceOnce(before,after) {
 assert.equal(expectedScript.split(before).length,2,'Pinned remote baseline drift');
 expectedScript=expectedScript.replace(before,after);
}
replaceOnce('set -eu\numask 077', 'set -eu\numask 077\n'
 +'# Read-only preflight must finish BEFORE touching operational configuration.\n'
 +'test -x /home/luha/erasure-tools/local-ledger-preflight\n'
 +"printf '%s' '${{ steps.lifecycle.outputs.payload }}' | /home/luha/erasure-tools/local-ledger-preflight "+role+" '${{ vars.ERASURE_LEDGER_LOCAL_STORE_ID }}'");
const service=role==='api'?'api':'toilet-batch';
replaceOnce('  '+service+':','  '+service+':\n    user: "1000:1000"');
const mount='      - type: bind\n'
 +'        source: /home/luha/geupddong-erasure-ledger\n'
 +'        target: /home/luha/geupddong-erasure-ledger\n'
 +'        read_only: false\n'
 +'        bind:\n'
 +'          create_host_path: false';
if(role==='api'){
 replaceOnce('    container_name: toilet-api','    container_name: toilet-api\n    volumes:\n'+mount);
 replaceOnce('docker compose pull','docker compose pull api');
 replaceOnce('docker compose up -d --wait --wait-timeout 120 redis api',
  'docker compose up -d --no-deps --wait --wait-timeout 120 api');
} else {
 replaceOnce('      - ./region-results:/var/lib/toilet-region',
  '      - ./region-results:/var/lib/toilet-region\n'+mount);
}
// Approved maintenance preparation adds exactly one preflight, two env fields and one bind.
replaceOnce('test -x /home/luha/erasure-tools/local-ledger-preflight',
 '/home/luha/.local/bin/maintenance-preflight '+role+'\ntest -x /home/luha/erasure-tools/local-ledger-preflight');
replaceOnce('    container_name: toilet-'+role,
 '    container_name: toilet-'+role+'\n    environment:\n'
 +"      ERASURE_MAINTENANCE_LOCK_ENABLED: 'true'\n"
 +"      ERASURE_MAINTENANCE_DIRECTORY: '/home/luha/geupddong-maintenance'");
replaceOnce(mount,mount+'\n      - type: bind\n'
 +'        source: /home/luha/geupddong-maintenance\n'
 +'        target: /home/luha/geupddong-maintenance\n'
 +'        read_only: false\n        bind:\n          create_host_path: false');
replaceOnce("printf '%s' '${{ steps.lifecycle.outputs.payload }}' | /home/luha/erasure-tools/local-ledger-preflight api '${{ vars.ERASURE_LEDGER_LOCAL_STORE_ID }}'",
 "printf '%s' '${{ steps.lifecycle.outputs.payload }}' | /home/luha/erasure-tools/local-ledger-preflight api '${{ vars.ERASURE_LEDGER_LOCAL_STORE_ID }}'\n"
 +'profile_photo_env=/home/luha/.config/geupddong/profile-photo.env\n'
 +'test -f "$profile_photo_env"\n'
 +'test "$(stat -c \'%a:%u:%g\' "$profile_photo_env")" = "600:$(id -u):$(id -g)"\n'
 +"profile_photo_names=$(sed -n 's/^\\([A-Z0-9_]*\\)=.*$/\\1/p' \"$profile_photo_env\" | sort | paste -sd, -)\n"
 +"profile_photo_base_names='PROFILE_PHOTO_R2_ACCESS_KEY_ID,PROFILE_PHOTO_R2_BUCKET,PROFILE_PHOTO_R2_ENDPOINT,PROFILE_PHOTO_R2_SECRET_ACCESS_KEY'\n"
 +"profile_photo_cdn_names='PROFILE_PHOTO_CDN_TOKEN,PROFILE_PHOTO_CDN_ZONE_ID,PROFILE_PHOTO_R2_ACCESS_KEY_ID,PROFILE_PHOTO_R2_BUCKET,PROFILE_PHOTO_R2_ENDPOINT,PROFILE_PHOTO_R2_SECRET_ACCESS_KEY'\n"
 +"profile_photo_cdn_enabled='${{ vars.PROFILE_PHOTO_CDN_ENABLED || 'false' }}'\n"
 +'case "$profile_photo_cdn_enabled:$profile_photo_names" in\n'
 +'  false:"$profile_photo_base_names"|false:"$profile_photo_cdn_names"|true:"$profile_photo_cdn_names") ;;\n'
 +'  *) exit 1 ;;\n'
 +'esac');
replaceOnce('KAKAO_CLIENT_SECRET=${{ secrets.KAKAO_CLIENT_SECRET }}',
 'KAKAO_CLIENT_SECRET=${{ secrets.KAKAO_CLIENT_SECRET }}\n'
 +"PROFILE_PHOTO_ENABLED=${{ vars.PROFILE_PHOTO_ENABLED || 'false' }}\n"
 +"PROFILE_PHOTO_CDN_ENABLED=${{ vars.PROFILE_PHOTO_CDN_ENABLED || 'false' }}\n"
 +"KAKAO_LOGIN_SCOPES=${{ vars.PROFILE_PHOTO_ENABLED == 'true' && 'profile_nickname,account_email,profile_image' || 'profile_nickname,account_email' }}");
replaceOnce('KAKAO_REST_API_KEY=${{ secrets.KAKAO_REST_API_KEY }}',
 'KAKAO_REST_API_KEY=${{ secrets.KAKAO_REST_API_KEY }}\n'
 +'GOOGLE_TRANSLATION_API_KEY=${{ secrets.GOOGLE_TRANSLATION_API_KEY }}\n'
 +'DISPLAY_GROUP_TRANSLATION_ENABLED=true');
replaceOnce('JWT_SECRET=${{ secrets.JWT_SECRET }}',
 'JWT_SECRET=${{ secrets.JWT_SECRET }}\n'
 +'SERVICE_ANALYTICS_ENABLED=true\n'
 +'ANALYTICS_VISITOR_HMAC_SECRET=${{ secrets.JWT_SECRET }}\n'
 +'SERVICE_ANALYTICS_DAILY_CRON=0 30 2 * * *');
replaceOnce('WEB_CACHE_ORIGIN=${{ vars.WEB_CACHE_ORIGIN }}',
 'WEB_CACHE_ORIGIN=${{ vars.WEB_CACHE_ORIGIN }}\n'
 +"WEB_CACHE_CONTRACT_VERSION=${{ vars.WEB_CACHE_CONTRACT_VERSION || '1' }}");
replaceOnce("WEB_CACHE_REVALIDATION_SECRET=${{ secrets[vars.WEB_CACHE_ORIGIN == 'https://geupddong.com' && 'WEB_CACHE_PRODUCTION_REVALIDATION_SECRET' || 'WEB_CACHE_REVALIDATION_SECRET'] }}",
 "WEB_CACHE_REVALIDATION_SECRET=${{ secrets[vars.WEB_CACHE_ORIGIN == 'https://geupddong.com' && 'WEB_CACHE_PRODUCTION_REVALIDATION_SECRET' || 'WEB_CACHE_REVALIDATION_SECRET'] }}\n"
 +'WEB_CACHE_CLUSTER_PREVIEW_SECRET=${{ secrets.WEB_CACHE_REVALIDATION_SECRET }}');
replaceOnce('      - .account-lifecycle.env',
 '      - .account-lifecycle.env\n      - /home/luha/.config/geupddong/profile-photo.env');
assert.equal(deploy.env.DEPLOY_SCRIPT,expectedScript,'Remote commands must match only the allowlisted LOCAL and maintenance delta');
assert.equal(cleanup.if,'always()');
assert.equal(deploy.env.TUNNEL_SERVICE_TOKEN_ID,'${{ secrets.TUNNEL_DEPLOY_ACCESS_CLIENT_ID }}');
assert.equal(deploy.env.TUNNEL_SERVICE_TOKEN_SECRET,'${{ secrets.TUNNEL_DEPLOY_ACCESS_CLIENT_SECRET }}');
assert.equal(deploy.env.DEPLOY_SSH_KEY,'${{ secrets.MINI_PC_KEY }}');
assert.equal(deploy.env.TUNNEL_KNOWN_HOSTS,'${{ secrets.TUNNEL_DEPLOY_SSH_KNOWN_HOSTS }}');
assert.equal(deploy.env.TUNNEL_SSH_HOST,'${{ vars.TUNNEL_DEPLOY_SSH_HOST }}');
assert.equal(deploy.env.TUNNEL_SSH_USER,'${{ secrets.MINI_PC_USERNAME }}');
assert.equal(Object.keys(deploy.env).length,7);
assert.match(prepare.run,/660b348d473bba81997445b534e7eaefaf4c4e16331866922326c338a7013dd9/);
assert.match(prepare.run,/sha256sum -c -/);
assert.match(prepare.run,/--proto-redir '=https'/);
for(const guard of ['StrictHostKeyChecking=yes','BatchMode=yes','IdentitiesOnly=yes','HostKeyAlgorithms=ssh-ed25519','ForwardAgent=no','ClearAllForwardings=yes','timeout 10m ssh','bash -n','ssh-deploy.geupddong.com','umask 077']) assert.ok(deploy.run.includes(guard),'Missing guard '+guard);
assert.ok(!/set -x|ssh-keyscan|StrictHostKeyChecking=no|--retry/.test(deploy.run));
for(const script of [...newSteps.filter(s=>s.run).map(s=>s.run),deploy.env.DEPLOY_SCRIPT]){
 const check=spawnSync(process.env.TUNNEL_BASH || 'bash',['-n'],{input:script,encoding:'utf8',timeout:10000});
 assert.equal(check.status,0,check.stderr || String(check.error));
}
console.log('PASS: baseline '+baselineCommit+' plus exact LOCAL preparation delta; remaining build/remote/transport invariants and shell syntax verified.');
console.log('No credentials, SSH, image push, or deployment executed.');
