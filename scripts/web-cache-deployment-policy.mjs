const targets = new Map([
  ['https://preview.geupddong.com', 'WEB_CACHE_REVALIDATION_SECRET'],
  ['https://geupddong.com', 'WEB_CACHE_PRODUCTION_REVALIDATION_SECRET'],
])

// Never include supplied values in errors: some fields are deployment secrets.
export function validateWebCacheDeployment({enabled = 'false', origin = '', secretName = '', secret = ''}) {
  if (!['true', 'false'].includes(enabled)) throw new Error('Cache enabled flag must be true or false')
  if (origin && !targets.has(origin)) throw new Error('Cache origin must exactly match an approved target')
  if (secret && !/^[A-Za-z0-9_+/=-]{32,512}$/.test(secret)) throw new Error('Cache secret is invalid or contains unsafe whitespace/characters')
  if (enabled === 'false') return {enabled:false, target:'disabled'}
  if (!targets.has(origin)) throw new Error('Enabled cache delivery requires a configured origin')
  if (secretName !== targets.get(origin)) throw new Error('Cache origin and secret name do not match')
  if (!secret) throw new Error('Required cache secret is missing; no cross-environment fallback is permitted')
  return {enabled:true, target:origin === 'https://geupddong.com' ? 'production' : 'preview'}
}
