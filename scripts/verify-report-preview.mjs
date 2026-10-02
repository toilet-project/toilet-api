// Bounded HTTP checks against only the guarded local fixture. Production is GET-only.
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { dirname, basename, resolve } from 'node:path'
const [metadataPath, outputPath] = process.argv.slice(2)
const metadata = JSON.parse(await readFile(metadataPath, 'utf8'))
assert.equal(basename(dirname(resolve(metadataPath))), `account-retention-mysql-${metadata.marker}`)
assert.ok(metadata.port > 1024 && metadata.port !== 3306 && Date.parse(metadata.expiresAt) > Date.now())
const base = `http://127.0.0.1:${metadata.port}`, receipts = []
async function api(path, { body, member, headers = {}, method = body ? 'POST' : 'GET' } = {}) {
  const response = await fetch(base + path, { method, headers: { ...(body ? { 'Content-Type': 'application/json', Origin: 'https://preview.geupddong.com', 'Idempotency-Key': randomUUID(), 'X-Report-Guest': randomUUID() } : {}), ...(member ? { Authorization: `Bearer ${metadata.tokens[member]}` } : {}), ...headers }, body: body ? JSON.stringify(body) : undefined, redirect: 'error' })
  return { status: response.status, data: await response.json().catch(() => null) }
}
const source = await fetch('https://api.geupddong.com/api/v1/toilets/53585', { redirect: 'error' }).then(response => { assert.equal(response.status, 200); return response.json() })
assert.equal((await api('/api/admin/preview/source', { member: '3', body: source })).status, 200)
const guest = randomUUID(), key = randomUUID()
for (const type of ['FACILITY_MISSING', 'TEMPORARILY_CLOSED', 'COORDINATE_CORRECTION', 'NEW_FACILITY']) {
  const body = { reportType: type, ...(type === 'NEW_FACILITY' ? { name: '격리 검증 신규 화장실' } : { toiletId: source.id }),
    ...(['COORDINATE_CORRECTION', 'NEW_FACILITY'].includes(type) ? { latitude: source.latitude, longitude: source.longitude, roadAddress: source.roadAddress } : {}) }
  const response = await api('/api/v1/reports/guest', { body, headers: type === 'FACILITY_MISSING' ? { 'X-Report-Guest': guest, 'Idempotency-Key': key } : {} })
  assert.equal(response.status, 201, JSON.stringify(response.data))
  assert.deepEqual(Object.keys(response.data).sort(), ['id', 'status']); assert.equal(response.data.status, 'PENDING')
  receipts.push({ type, id: response.data.id })
}
const body = { reportType: 'FACILITY_MISSING', toiletId: source.id }
const repeated = await api('/api/v1/reports/guest', { body, headers: { 'X-Report-Guest': guest, 'Idempotency-Key': key } })
assert.equal(repeated.status, 201); assert.equal(repeated.data.id, receipts[0].id)
assert.equal((await api('/api/v1/reports/guest', { body: { ...body, reason: 'changed' }, headers: { 'X-Report-Guest': guest, 'Idempotency-Key': key } })).status, 400)
assert.equal((await api('/api/v1/reports/guest', { body, headers: { Origin: 'https://untrusted.invalid' } })).status, 403)
assert.equal((await api('/api/v1/reports/guest', { body: { reportType: 'NEW_FACILITY', name: 'invalid', latitude: 0, longitude: 0 } })).status, 400)
assert.equal((await api('/api/v1/reports/me')).status, 401)
assert.equal((await api('/api/admin/v1/reports/summary')).status, 401)
assert.equal((await api('/api/admin/v1/reports/summary', { member: '1' })).status, 403)
const member = await api('/api/v1/reports/quick', { member: '1', body })
assert.equal(member.status, 201, JSON.stringify(member.data))
assert.equal((await api('/api/v1/reports/me', { member: '1' })).data.length, 1)
assert.equal((await api('/api/v1/reports/me', { member: '2' })).data.length, 0)
const detail = await api(`/api/admin/v1/reports/${receipts[0].id}`, { member: '3' })
assert.equal(detail.status, 200); assert.equal(detail.data.reporterDisplayName, '비회원')
assert.equal(detail.data.report.latitude, source.latitude); assert.equal(detail.data.report.openTime, source.openTime)
assert.ok(detail.data.report.observedAt)
const newDetail = await api(`/api/admin/v1/reports/${receipts[3].id}`, { member: '3' })
assert.equal(newDetail.data.report.toiletId, null)
const search = await api('/api/admin/v1/reports/search?keyword=' + encodeURIComponent('격리 검증 신규'), { member: '3' })
assert.equal(search.status, 200, JSON.stringify(search.data)); assert.equal(search.data.totalElements, 1)
assert.equal((await api(`/api/admin/v1/reports/${receipts[1].id}/approve`, { member: '3', body: { note: '격리 검증 · 현재 미개방 확인' } })).status, 200)
assert.equal((await api(`/api/admin/v1/reports/${receipts[1].id}/approve`, { member: '3', body: {} })).status, 400)
assert.equal((await api(`/api/admin/v1/reports/${receipts[3].id}/reject`, { member: '3', body: { note: '격리 검증 종료' } })).status, 200)
const evidence = { verifiedAt: new Date().toISOString(), publicSource: { id: source.id, name: source.name }, receipts, checks: ['four-persisted-types', 'guest-vs-withdrawn', 'server-observation-snapshot', 'idempotent-retry', 'changed-payload-rejected', 'bad-origin-rejected', 'invalid-coordinate-rejected', 'guest-private-read-denied', 'member-admin-denied', 'member-history-isolation', 'new-facility-not-public', 'new-name-search', 'observation-approval', 'double-approval-rejected', 'new-rejection'], productionWrites: 0 }
await writeFile(outputPath, JSON.stringify(evidence, null, 2) + '\n')
console.log(JSON.stringify(evidence))
