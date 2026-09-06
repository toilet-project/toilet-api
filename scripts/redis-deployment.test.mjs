import {test} from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync, mkdtempSync, writeFileSync, rmSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {spawnSync} from 'node:child_process'

const workflow = readFileSync(new URL('../.github/workflows/deploy.yml', import.meta.url), 'utf8').replaceAll('\r\n', '\n')
const compose = workflow.match(/cat <<'EOF' > compose.yaml\n([\s\S]*?)^            EOF$/m)?.[1]
  .split('\n').map(line => line.replace(/^            /, '')).join('\n')

test('production Redis disables both persistence formats and cannot load the legacy volume', () => {
  assert.ok(compose)
  const redis = compose.split('  api:')[0]
  assert.ok(redis.includes("--save '' --appendonly no"))
  assert.ok(redis.includes('/data:rw,noexec,nosuid,size=64m'))
  assert.doesNotMatch(redis, /^\s*(volumes|ports):/m)
  assert.ok(redis.includes('$$REDIS_PASSWORD'))
  assert.ok(redis.includes('REDISCLI_AUTH='))
  assert.doesNotMatch(redis, /redis-cli -a/)
})

test('deployment preserves rollback materials and targets the commit image without pruning', () => {
  assert.ok(compose.includes('toilet-api:${{ github.sha }}'))
  assert.ok(workflow.includes('set -eu'))
  assert.ok(workflow.includes('cp -p -- "$file" "$rollback_dir/"'))
  assert.ok(workflow.indexOf('umask 077') < workflow.indexOf('cat <<EOF > .env'))
  assert.ok(workflow.includes('docker compose config --quiet'))
  assert.doesNotMatch(workflow, /docker image prune|--remove-orphans|docker volume rm/)
})

test('isolated Docker: literal password, no persistence, restart clears synthetic session',
  {skip: process.env.REDIS_DEPLOYMENT_DOCKER_TEST !== 'true', timeout: 180_000}, () => {
    const directory = mkdtempSync(join(tmpdir(), 'redis-deployment-test-'))
    const project = `redis-preparation-${process.pid}-${Date.now()}`
    const docker = (...args) => {
      const result = spawnSync('docker', args, {cwd:directory, encoding:'utf8', timeout:120_000})
      assert.equal(result.status, 0, `isolated Docker command failed: ${result.error?.message ?? result.stderr}`)
      return result.stdout.trim()
    }
    const dc = (...args) => docker('compose', '-p', project, ...args)
    try {
      // Exact production Redis stanza; replace only image placeholders and resource names.
      writeFileSync(join(directory, 'compose.yaml'), compose
        .replaceAll('${{ secrets.DOCKERHUB_USERNAME }}', 'synthetic')
        .replaceAll('${{ github.sha }}', 'synthetic')
        .replaceAll('${{ secrets.API_PORT }}', '8080')
        .replaceAll('container_name: toilet-redis', `container_name: ${project}`)
        .replaceAll('container_name: toilet-api', `container_name: ${project}-unused-api`)
        .replace('external: true', 'internal: true'))
      writeFileSync(join(directory, '.env'), "REDIS_PASSWORD='synthetic$VALUE#=password'\n")
      writeFileSync(join(directory, '.account-lifecycle.env'), '')
      dc('config', '--quiet')
      dc('up', '-d', '--wait', '--wait-timeout', '60', 'redis')
      const redis = (...args) => dc('exec', '-T', 'redis', 'sh', '-c',
        'export REDISCLI_AUTH="$REDIS_PASSWORD"; exec redis-cli --raw "$@"', 'sh', ...args)
      assert.equal(redis('PING'), 'PONG')
      assert.equal(redis('CONFIG','GET','appendonly'), 'appendonly\nno')
      assert.equal(redis('CONFIG','GET','save'), 'save')
      assert.ok(dc('exec','-T','redis','sh','-c','grep " /data tmpfs " /proc/mounts'))
      assert.equal(dc('exec','-T','redis','sh','-c','find /data -type f'), '')
      assert.equal(redis('SET','synthetic:refresh-session','not-personal-data'), 'OK')
      dc('restart','redis')
      dc('up','-d','--wait','--wait-timeout','60','redis')
      assert.equal(redis('EXISTS','synthetic:refresh-session'), '0')
      assert.equal(redis('CONFIG','GET','appendonly'), 'appendonly\nno')
      assert.equal(redis('CONFIG','GET','save'), 'save')
      assert.equal(dc('exec','-T','redis','sh','-c','find /data -type f'), '')
    } finally {
      // Only this unique synthetic Compose project; no production network, ports or volumes.
      dc('down', '--timeout', '10')
      rmSync(directory, {recursive:true, force:true})
    }
  })
