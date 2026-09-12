// Temporary synthetic acceptance gateway. Not a production entry point or authentication mechanism.
import http from 'node:http'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve, dirname, basename } from 'node:path'

const [metadataPath, outputPath] = process.argv.slice(2)
if (!metadataPath || !outputPath || resolve(dirname(metadataPath)) !== resolve(dirname(outputPath))) throw new Error('Same fixture directory required')
const metadata = JSON.parse(await readFile(metadataPath, 'utf8'))
if (!/^[a-f0-9]{10}$/.test(metadata.marker) || basename(dirname(resolve(metadataPath))) !== 'account-retention-mysql-' + metadata.marker
  || !Number.isInteger(metadata.port) || metadata.port < 1024 || metadata.port > 65535 || metadata.port === 3306) throw new Error('Invalid synthetic fixture')
const token = metadata.tokens?.['1']
const claims = JSON.parse(Buffer.from(token?.split('.')[1] ?? '', 'base64url').toString())
if (claims.sub !== '1' || !claims.roles?.includes('USER') || !Number.isInteger(claims.exp)) throw new Error('Synthetic token required')
const expires = Math.min(claims.exp * 1000, Date.now() + 2 * 60 * 60 * 1000)
const headers = { 'Content-Type': 'application/json', 'Cache-Control': 'private, no-store', 'X-Robots-Tag': 'noindex, nofollow', 'X-Content-Type-Options': 'nosniff' }
let windowStart = Date.now(), count = 0
const server = http.createServer(async (request, response) => {
  const reject = (status) => { response.writeHead(status, headers); response.end(JSON.stringify({ error: { code: 'REVIEW_VERIFICATION_UNAVAILABLE' } })) }
  try {
    if (Date.now() >= expires) return reject(410)
    if (Date.now() - windowStart > 60000) { windowStart = Date.now(); count = 0 }
    if (++count > 180) return reject(429)
    if (request.headers['x-review-verification'] !== 'synthetic-only') return reject(403)
    const url = new URL(request.url, 'http://fixture.invalid'), path = url.pathname
    const read = request.method === 'GET' && (/^\/api\/v1\/reviews(?:\/me|\/creation-status|\/[1-9]\d*)?$/.test(path)
      || /^\/api\/v1\/toilets(?:\/[1-9]\d*(?:\/reviews(?:\/summary)?)?)?$/.test(path)
      || ['/api/v1/auth/me', '/api/v1/notifications/unread-count'].includes(path))
    const write = request.method === 'POST' && /^\/api\/v1\/reviews(?:\/[1-9]\d*\/detach-author)?$/.test(path)
      || request.method === 'PATCH' && /^\/api\/v1\/reviews\/[1-9]\d*$/.test(path)
    if (!read && !write || url.search.length > 1000) return reject(403)
    const chunks = []; let size = 0
    for await (const chunk of request) { size += chunk.length; if (size > 8192) return reject(413); chunks.push(chunk) }
    const forwarded = { Authorization: 'Bearer ' + token, Origin: 'https://preview.geupddong.com', Accept: 'application/json' }
    if (write) forwarded['Content-Type'] = 'application/json'
    const key = request.headers['idempotency-key']
    if (typeof key === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(key)) forwarded['Idempotency-Key'] = key
    const result = await fetch(`http://127.0.0.1:${metadata.port}${path}${url.search}`, { method: request.method, headers: forwarded, body: write ? Buffer.concat(chunks) : undefined, redirect: 'manual', signal: AbortSignal.timeout(12000) })
    if (result.status >= 300 && result.status < 400) { await result.body?.cancel(); return reject(502) }
    const body = await result.arrayBuffer()
    if (body.byteLength > 1024 * 1024) return reject(502)
    response.writeHead(result.status, headers); response.end(Buffer.from(body))
  } catch { if (!response.headersSent) reject(503); else response.end() }
})
server.requestTimeout = 15000
server.headersTimeout = 10000
server.listen(0, '127.0.0.1', async () => {
  await writeFile(outputPath, JSON.stringify({ port: server.address().port, expiresAt: new Date(expires).toISOString(), syntheticOnly: true }), { flag: 'wx' })
  console.log('REVIEW_FIXTURE_GATEWAY_READY syntheticOnly=true noProductionCookies=true')
})
const stop = () => { server.closeAllConnections(); server.close(); clearTimeout(timer) }
const timer = setTimeout(stop, Math.max(1, expires - Date.now()))
process.on('SIGINT', stop); process.on('SIGTERM', stop)
