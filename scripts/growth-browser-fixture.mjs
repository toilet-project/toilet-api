// Local acceptance proxy: real test API + local web build, synthetic member only.
// Never forward browser cookies, tokens or API traffic to a production service.
import http from 'node:http'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve, dirname, basename } from 'node:path'

const [metadataPath, outputPath, webPortText, listenPortText = '0'] = process.argv.slice(2)
if (!metadataPath || !outputPath || resolve(dirname(metadataPath)) !== resolve(dirname(outputPath))) throw new Error('Same fixture directory required')
const metadata = JSON.parse(await readFile(metadataPath, 'utf8'))
const webPort = Number(webPortText)
const validPort = port => Number.isInteger(port) && port > 1024 && port <= 65535 && port !== 3306
const listenPort = Number(listenPortText)
if (listenPort !== 0 && !validPort(listenPort)) throw new Error('Invalid local port')
if (!/^[a-f0-9]{10}$/.test(metadata.marker) || basename(dirname(resolve(metadataPath))) !== 'account-retention-mysql-' + metadata.marker
  || !validPort(metadata.port) || !validPort(webPort) || metadata.port === webPort) throw new Error('Invalid local fixture')
const token = metadata.tokens?.['1']
const claims = JSON.parse(Buffer.from(token?.split('.')[1] ?? '', 'base64url').toString())
if (claims.sub !== '1' || !claims.roles?.includes('USER') || !Number.isInteger(claims.exp)) throw new Error('Synthetic member required')
const expires = Math.min(claims.exp * 1000, Date.now() + 2 * 60 * 60 * 1000)
const privateHeaders = { 'Cache-Control': 'private, no-store', 'X-Robots-Tag': 'noindex, nofollow', 'X-Content-Type-Options': 'nosniff' }
const allowedRead = path => ['/api/v1/auth/me', '/api/v1/growth/me', '/api/v1/growth/history', '/api/v1/notifications/unread-count', '/api/v1/engagement/likes', '/api/v1/reviews/me'].includes(path)
const server = http.createServer(async (request, response) => {
  const reject = status => { response.writeHead(status, { ...privateHeaders, 'Content-Type': 'application/json' }); response.end(JSON.stringify({ error: { code: 'SYNTHETIC_FIXTURE_ONLY' } })) }
  try {
    if (Date.now() >= expires) return reject(410)
    if (request.headers.host !== `127.0.0.1:${server.address().port}`) return reject(403)
    if (request.headers.origin && request.headers.origin !== `http://${request.headers.host}`) return reject(403)
    const url = new URL(request.url, 'http://fixture.invalid')
    if (url.pathname.startsWith('/api/')) {
      const read = request.method === 'GET' && allowedRead(url.pathname)
      const write = request.method === 'POST' && url.pathname === '/api/v1/growth/check-in'
      if ((!read && !write) || url.search.length > 1000) return reject(403)
      const chunks = []; let size = 0
      for await (const chunk of request) { size += chunk.length; if (size > 8192) return reject(413); chunks.push(chunk) }
      const result = await fetch(`http://127.0.0.1:${metadata.port}${url.pathname}${url.search}`, {
        method: request.method, headers: { Authorization: `Bearer ${token}`, Origin: 'https://preview.geupddong.com', Accept: 'application/json', 'Content-Type': 'application/json' },
        body: write ? Buffer.concat(chunks) : undefined, redirect: 'manual', signal: AbortSignal.timeout(15000),
      })
      if (result.status >= 300 && result.status < 400) { await result.body?.cancel(); return reject(502) }
      const body = await result.arrayBuffer()
      response.writeHead(result.status, { ...privateHeaders, 'Content-Type': result.headers.get('content-type') ?? 'application/json' }); response.end(Buffer.from(body)); return
    }
    if (!['GET', 'HEAD'].includes(request.method)) return reject(403)
    const headers = { ...request.headers, host: `127.0.0.1:${webPort}` }
    delete headers.cookie; delete headers.authorization; delete headers.origin
    const upstream = http.request({ hostname: '127.0.0.1', port: webPort, method: request.method, path: url.pathname + url.search, headers }, result => {
      const outgoing = { ...result.headers, ...privateHeaders }; delete outgoing['set-cookie']
      if (result.statusCode >= 300 && result.statusCode < 400 && /^https?:/i.test(outgoing.location ?? '')) { result.resume(); reject(502); return }
      response.writeHead(result.statusCode, outgoing); result.pipe(response)
    })
    upstream.on('error', () => { if (!response.headersSent) reject(503); else response.end() }); upstream.end()
  } catch { if (!response.headersSent) reject(503); else response.end() }
})
// Local Next development handshake only. Never route API upgrades or credentials.
server.on('upgrade', (request, socket, head) => {
  if (Date.now() >= expires || request.headers.host !== `127.0.0.1:${server.address().port}`
    || !request.url?.startsWith('/_next/webpack-hmr')) { socket.destroy(); return }
  const headers = { ...request.headers, host: `127.0.0.1:${webPort}`, origin: `http://127.0.0.1:${webPort}` }
  delete headers.cookie; delete headers.authorization
  const upstream = http.request({ hostname: '127.0.0.1', port: webPort, path: request.url, headers })
  upstream.on('upgrade', (result, target, targetHead) => {
    socket.write(`HTTP/1.1 ${result.statusCode} Switching Protocols\r\n` + Object.entries(result.headers).map(([key,value]) => `${key}: ${value}\r\n`).join('') + '\r\n')
    if (head.length) target.write(head)
    if (targetHead.length) socket.write(targetHead)
    socket.pipe(target); target.pipe(socket)
    socket.on('error', () => target.destroy()); target.on('error', () => socket.destroy())
  })
  upstream.on('error', () => socket.destroy()); upstream.end()
})
server.requestTimeout = 20000
server.listen(listenPort, '127.0.0.1', async () => {
  await writeFile(outputPath, JSON.stringify({ port: server.address().port, expiresAt: new Date(expires).toISOString(), syntheticOnly: true }), { flag: 'wx' })
  console.log('GROWTH_BROWSER_FIXTURE_READY syntheticOnly=true noProductionCookies=true')
})
const stop = () => { clearTimeout(timer); server.closeAllConnections(); server.close() }
const timer = setTimeout(stop, Math.max(1, expires - Date.now()))
process.on('SIGINT', stop); process.on('SIGTERM', stop)
