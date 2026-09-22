const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const yaml = require(process.env.TUNNEL_YAML_MODULE || 'yaml');

const root = path.resolve(__dirname, '..');
const workflowPath = path.join(root, '.github', 'workflows', 'region-transition-observation.yml');
const scriptPath = path.join(root, 'scripts', 'collect-region-transition-observation.sh');
const workflow = yaml.parse(fs.readFileSync(workflowPath, 'utf8'));
const script = fs.readFileSync(scriptPath, 'utf8');

assert.deepEqual(workflow.permissions, {contents: 'read'});
assert.equal(workflow.concurrency.group, 'region-transition-observation');
assert.equal(workflow.concurrency['cancel-in-progress'], false);
assert.ok(workflow.on.schedule.some(entry => entry.cron === '20 17 * * *'));
assert.ok(Object.hasOwn(workflow.on, 'workflow_dispatch'));

const steps = workflow.jobs.observe.steps;
const collect = steps.find(step => step.name === 'Collect production observation through Tunnel');
assert.ok(collect, 'Tunnel observation step is required');
assert.equal(collect.env.TUNNEL_SERVICE_TOKEN_ID, '${{ secrets.TUNNEL_DEPLOY_ACCESS_CLIENT_ID }}');
assert.equal(collect.env.TUNNEL_SERVICE_TOKEN_SECRET, '${{ secrets.TUNNEL_DEPLOY_ACCESS_CLIENT_SECRET }}');
assert.equal(collect.env.OBSERVATION_SSH_KEY, '${{ secrets.MINI_PC_KEY }}');
assert.equal(collect.env.TUNNEL_KNOWN_HOSTS, '${{ secrets.TUNNEL_DEPLOY_SSH_KNOWN_HOSTS }}');
assert.equal(collect.env.TUNNEL_SSH_HOST, '${{ vars.TUNNEL_DEPLOY_SSH_HOST }}');
assert.equal(collect.env.TUNNEL_SSH_USER, '${{ secrets.MINI_PC_USERNAME }}');

for (const guard of [
  'StrictHostKeyChecking=yes', 'BatchMode=yes', 'IdentitiesOnly=yes',
  'HostKeyAlgorithms=ssh-ed25519', 'ForwardAgent=no', 'ClearAllForwardings=yes',
  'timeout 10m ssh', 'ssh-deploy.geupddong.com', "ProxyCommand=cloudflared access ssh --hostname %h",
]) assert.ok(collect.run.includes(guard), `Missing SSH guard: ${guard}`);

assert.ok(script.includes("docker container inspect --format '{{.State.Running}}'"));
assert.ok(!script.includes('/home/luha/.local/bin/maintenance-preflight api'));
assert.ok(script.includes('mysql --protocol=socket --batch --raw --skip-column-names'));
assert.ok(script.includes("--init-command='SET SESSION TRANSACTION READ ONLY'"));
assert.ok(script.includes("trigger_type='SCHEDULED'"));
assert.ok(script.includes("started_at >= '2026-09-15 00:00:00'"));
assert.ok(script.includes('recent_assignment_mismatch'));
assert.ok(script.includes('recent_decision_mismatch'));
assert.ok(script.includes('current_view_revision_pollution'));
assert.ok(!/(^|[\s;])(insert|update|delete|replace|alter|drop|truncate|create|grant|revoke)\s/im.test(script),
  'Observation script must not contain mutating SQL');
assert.ok(!/docker\s+(compose|rm|stop|restart|kill)|set\s+-x/i.test(script),
  'Observation script must not mutate containers or print secrets');

console.log('PASS: region transition observation is read-only and uses pinned Tunnel SSH guards.');
