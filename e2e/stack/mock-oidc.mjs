// A stand-in for Auth0, for the browser tests only: Auth0 can't be scripted through MFA. It serves what auth0-spa-js and
// Spring's resource server use (discovery, JWKS, /authorize, /oauth/token, /v2/logout) and signs RS256 tokens with a key
// made at startup. /authorize shows a one-field sign-in form: any email signs in, and an email starting with "admin" gets
// the ADMIN role with the MFA claim, as the Auth0 post-login Action sets them (docs/auth.md).
import { createHash, createSign, generateKeyPairSync, randomBytes } from 'node:crypto'
import { createServer } from 'node:http'
import { AUDIENCE, CLIENT_ID, ISSUER, PORTS } from './ports.mjs'

const ROLES_CLAIM = 'https://pokemans.app/roles'
const MFA_CLAIM = 'https://pokemans.app/mfa'
const base = ISSUER.replace(/\/$/, '')

const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 })
const kid = randomBytes(8).toString('hex')
const jwk = { ...publicKey.export({ format: 'jwk' }), kid, alg: 'RS256', use: 'sig' }

const codes = new Map() // code -> { email, nonce, challenge, redirectUri }
const refreshTokens = new Map() // token -> email

const b64url = (data) => Buffer.from(data).toString('base64url')

function jwt(claims) {
  const head = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT', kid }))
  const body = b64url(JSON.stringify(claims))
  const signature = createSign('RSA-SHA256').update(`${head}.${body}`).sign(privateKey)
  return `${head}.${body}.${b64url(signature)}`
}

function tokens(email, nonce) {
  const now = Math.floor(Date.now() / 1000)
  const sub = `e2e|${email}`
  const admin = email.startsWith('admin')
  const access = jwt({
    iss: ISSUER, sub, aud: [AUDIENCE], azp: CLIENT_ID, iat: now, exp: now + 3600, scope: 'openid profile email offline_access',
    email, [ROLES_CLAIM]: admin ? ['ADMIN'] : [], [MFA_CLAIM]: admin,
  })
  const id = jwt({ iss: ISSUER, sub, aud: CLIENT_ID, iat: now, exp: now + 3600, nonce, email, name: email, email_verified: true })
  const refresh = randomBytes(24).toString('hex')
  refreshTokens.set(refresh, email)
  return { access_token: access, id_token: id, refresh_token: refresh, token_type: 'Bearer', expires_in: 3600, scope: 'openid profile email offline_access' }
}

function send(res, status, body, headers = {}) {
  res.writeHead(status, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', ...headers })
  res.end(body)
}
const json = (res, status, body) => send(res, status, JSON.stringify(body), { 'Content-Type': 'application/json' })

async function readBody(req) {
  const chunks = []
  for await (const c of req) chunks.push(c)
  const text = Buffer.concat(chunks).toString()
  return (req.headers['content-type'] ?? '').includes('json') ? JSON.parse(text || '{}') : Object.fromEntries(new URLSearchParams(text))
}

const escape = (s) => s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`)

function signInPage(query) {
  const hidden = [...query.entries()].map(([k, v]) => `<input type="hidden" name="${escape(k)}" value="${escape(v)}">`).join('')
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Mock sign-in</title></head><body><main><h1>Mock sign-in (e2e only)</h1>
<form method="post" action="/authorize">${hidden}<label>Email <input name="email" type="email" required></label>
<button type="submit">Sign in</button></form></main></body></html>`
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, base)
  try {
    if (req.method === 'OPTIONS') return send(res, 204, '', { 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS' })
    if (url.pathname === '/.well-known/openid-configuration') {
      return json(res, 200, {
        issuer: ISSUER,
        authorization_endpoint: `${base}/authorize`,
        token_endpoint: `${base}/oauth/token`,
        jwks_uri: `${base}/.well-known/jwks.json`,
        end_session_endpoint: `${base}/v2/logout`,
        response_types_supported: ['code'],
        subject_types_supported: ['public'],
        id_token_signing_alg_values_supported: ['RS256'],
        code_challenge_methods_supported: ['S256'],
      })
    }
    if (url.pathname === '/.well-known/jwks.json') return json(res, 200, { keys: [jwk] })
    if (url.pathname === '/authorize' && req.method === 'GET') {
      if (url.searchParams.get('prompt') === 'none') {
        // Silent checks (no session here): answer as Auth0 does, so the SDK falls back to a login.
        const back = new URL(url.searchParams.get('redirect_uri'))
        back.searchParams.set('error', 'login_required')
        back.searchParams.set('state', url.searchParams.get('state') ?? '')
        return send(res, 302, '', { Location: back.href })
      }
      return send(res, 200, signInPage(url.searchParams), { 'Content-Type': 'text/html; charset=utf-8' })
    }
    if (url.pathname === '/authorize' && req.method === 'POST') {
      const form = await readBody(req)
      if (form.client_id !== CLIENT_ID) return send(res, 400, 'unknown client_id')
      const code = randomBytes(16).toString('hex')
      codes.set(code, { email: form.email.toLowerCase(), nonce: form.nonce, challenge: form.code_challenge, redirectUri: form.redirect_uri })
      const back = new URL(form.redirect_uri)
      back.searchParams.set('code', code)
      back.searchParams.set('state', form.state)
      return send(res, 302, '', { Location: back.href })
    }
    if (url.pathname === '/oauth/token' && req.method === 'POST') {
      const body = await readBody(req)
      if (body.grant_type === 'authorization_code') {
        const grant = codes.get(body.code)
        codes.delete(body.code)
        const verifier = createHash('sha256').update(body.code_verifier ?? '').digest('base64url')
        if (!grant || grant.challenge !== verifier || grant.redirectUri !== body.redirect_uri) {
          return json(res, 403, { error: 'invalid_grant', error_description: 'bad code, verifier or redirect_uri' })
        }
        return json(res, 200, tokens(grant.email, grant.nonce))
      }
      if (body.grant_type === 'refresh_token') {
        const email = refreshTokens.get(body.refresh_token)
        if (!email) return json(res, 403, { error: 'invalid_grant', error_description: 'unknown refresh token' })
        refreshTokens.delete(body.refresh_token)
        return json(res, 200, tokens(email, undefined))
      }
      return json(res, 400, { error: 'unsupported_grant_type' })
    }
    if (url.pathname === '/v2/logout') {
      return send(res, 302, '', { Location: url.searchParams.get('returnTo') ?? '/' })
    }
    send(res, 404, 'not found')
  } catch (e) {
    send(res, 500, String(e))
  }
})

server.listen(PORTS.oidc, '127.0.0.1', () => console.log(`mock OIDC issuer at ${ISSUER}`))
