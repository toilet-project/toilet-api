// Temporary, allowlisted gateway: real public facility reads + isolated report writes only.
import http from 'node:http'
import { readFile, writeFile } from 'node:fs/promises'
import { randomBytes, timingSafeEqual } from 'node:crypto'
import { resolve, dirname, basename } from 'node:path'
const [metadataPath, outputPath] = process.argv.slice(2)
if (!metadataPath || !outputPath || dirname(resolve(metadataPath)) !== dirname(resolve(outputPath))) throw new Error('Same isolated directory required')
const metadata = JSON.parse(await readFile(metadataPath, 'utf8'))
if (basename(dirname(resolve(metadataPath))) !== `account-retention-mysql-${metadata.marker}` || !/^[a-f0-9]{10}$/.test(metadata.marker)) throw new Error('Invalid fixture directory')
const expires = Date.parse(metadata.expiresAt)
if (!(expires > Date.now() && expires - Date.now() <= 2 * 3600000) || !(metadata.port > 1024 && metadata.port <= 65535) || !metadata.tokens?.['3']) throw new Error('Invalid isolated metadata')
const previousPath = process.env.REPORT_PREVIEW_REUSE_CONNECTION
const previous = previousPath ? JSON.parse(await readFile(previousPath,'utf8')) : null
if (previous && (dirname(resolve(previousPath)) !== dirname(resolve(metadataPath)) || previous.expiresAt !== metadata.expiresAt || !(previous.port>1024&&previous.port<65536) || !/^[a-f0-9]{64}$/.test(previous.token))) throw new Error('Existing trial connection invalid')
const token = previous?.token || randomBytes(32).toString('hex'), seeded = new Set()
let minute = 0, count = 0
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
const server = http.createServer(async (req, res) => {
  res.setHeader('Cache-Control', 'private, no-store'); res.setHeader('Content-Type', 'application/json')
  const fail = (status, code) => { res.writeHead(status); res.end(JSON.stringify({ error: { code } })) }
  const received = Buffer.from(String(req.headers['x-report-preview-key'] || ''))
  if (received.length !== token.length || !timingSafeEqual(received, Buffer.from(token))) return fail(403, 'PREVIEW_ONLY')
  if (Date.now() >= expires) return fail(410, 'PREVIEW_EXPIRED')
  if (req.method !== 'POST' || req.url !== '/api/v1/reports/guest') return fail(403, 'PATH_DENIED')
  if (!uuid.test(req.headers['idempotency-key'] || '') || !uuid.test(req.headers['x-report-guest'] || '')) return fail(400, 'IDENTIFIER_REQUIRED')
  const now = Math.floor(Date.now() / 60000); if (now !== minute) { minute = now; count = 0 }
  if (++count > 100) return fail(429, 'RATE_LIMITED')
  try {
    let size = 0; const chunks = []
    for await (const chunk of req) { size += chunk.length; if (size > 8192) return fail(413, 'BODY_TOO_LARGE'); chunks.push(chunk) }
    const body = Buffer.concat(chunks), value = JSON.parse(body)
    if (value.toiletId != null) {
      const id = value.toiletId
      if (!Number.isSafeInteger(id) || id < 1 || id > 1000000) return fail(400, 'INVALID_FACILITY')
      if (!seeded.has(id)) {
        if (seeded.size >= 1000) return fail(429, 'SNAPSHOT_LIMIT')
        const source = await fetch(`https://api.geupddong.com/api/v1/toilets/${id}`, { signal: AbortSignal.timeout(10000), redirect: 'error' })
        if (!source.ok) { await source.body?.cancel(); return fail(404, 'FACILITY_NOT_FOUND') }
        const data = await source.json()
        if (data.id !== id) return fail(502, 'SOURCE_MISMATCH')
        const imported = await fetch(`http://127.0.0.1:${metadata.port}/api/admin/preview/source`, { method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${metadata.tokens['3']}` }, body: JSON.stringify(data), signal: AbortSignal.timeout(10000) })
        if (!imported.ok) { await imported.body?.cancel(); return fail(503, 'SNAPSHOT_UNAVAILABLE') }
        await imported.body?.cancel(); seeded.add(id)
      }
    }
    const result = await fetch(`http://127.0.0.1:${metadata.port}/api/v1/reports/guest`, { method: 'POST',
      headers: { 'Content-Type': 'application/json', Origin: 'https://preview.geupddong.com', 'Idempotency-Key': req.headers['idempotency-key'], 'X-Report-Guest': req.headers['x-report-guest'] },
      body, signal: AbortSignal.timeout(12000), redirect: 'error' })
    res.writeHead(result.status); res.end(Buffer.from(await result.arrayBuffer()))
  } catch { if (!res.headersSent) fail(503, 'PREVIEW_UNAVAILABLE'); else res.end() }
})
server.listen(previous?.port || 0, '127.0.0.1', async () => {
  await writeFile(outputPath, JSON.stringify({ port: server.address().port, token, expiresAt: metadata.expiresAt }), { flag: 'wx', mode: 0o600 })
  console.log('REPORT_GATEWAY_READY isolated=true source=public-api')
})
setTimeout(() => server.close(), expires - Date.now()).unref()
