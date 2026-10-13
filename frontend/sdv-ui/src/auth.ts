const MODE = String(import.meta.env.VITE_SECURITY_MODE || (import.meta.env.DEV ? 'LOCAL' : 'OIDC')).toUpperCase()
const TOKEN_KEY = 'sdv.oidc.access-token'
const ID_TOKEN_KEY = 'sdv.oidc.id-token'
const STATE_KEY = 'sdv.oidc.state'
const VERIFIER_KEY = 'sdv.oidc.pkce-verifier'

// React StrictMode intentionally re-runs effects in development. OIDC authorization
// codes are single-use, so callback processing must be single-flight or two effects
// can race to redeem the same code and the losing request can clear a valid session.
let callbackExchange: Promise<boolean> | null = null

export function isLocalSecurityMode(): boolean {
  return MODE === 'LOCAL'
}

export function isOidcSecurityMode(): boolean {
  return !isLocalSecurityMode()
}

export function getAccessToken(): string | null {
  if (!isOidcSecurityMode()) return null
  return sessionStorage.getItem(TOKEN_KEY)
}

export function clearOidcSession(): void {
  sessionStorage.removeItem(TOKEN_KEY)
  sessionStorage.removeItem(ID_TOKEN_KEY)
  sessionStorage.removeItem(STATE_KEY)
  sessionStorage.removeItem(VERIFIER_KEY)
}

export const OIDC_SESSION_EXPIRED_EVENT = 'sdv:oidc-session-expired'

export function expireOidcSession(): void {
  clearOidcSession()
  window.dispatchEvent(new Event(OIDC_SESSION_EXPIRED_EVENT))
}

export async function bearerHeaders(): Promise<Record<string, string>> {
  const token = getAccessToken()
  if (!token || token.trim().length === 0) return {}
  return { Authorization: `Bearer ${token.trim()}` }
}

export async function handleOidcCallback(): Promise<boolean> {
  if (!isOidcSecurityMode()) return false

  const url = new URL(window.location.href)
  if (!url.searchParams.get('code') && !url.searchParams.get('error')) return false

  if (!callbackExchange) {
    callbackExchange = processOidcCallback(url)
  }
  return callbackExchange
}

async function processOidcCallback(url: URL): Promise<boolean> {
  const code = url.searchParams.get('code')
  const state = url.searchParams.get('state')
  const oidcError = url.searchParams.get('error')
  if (oidcError) {
    clearOidcSession()
    throw new Error(url.searchParams.get('error_description') || `OIDC sign-in failed: ${oidcError}`)
  }
  if (!code) return false

  const expectedState = sessionStorage.getItem(STATE_KEY)
  const verifier = sessionStorage.getItem(VERIFIER_KEY)
  if (!state || !expectedState || state !== expectedState || !verifier) {
    clearOidcSession()
    throw new Error('OIDC callback validation failed')
  }

  const config = oidcConfig()
  const discovery = await loadDiscovery(config.issuer)
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: config.clientId,
    code,
    redirect_uri: config.redirectUri,
    code_verifier: verifier,
  })
  const response = await fetch(discovery.token_endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  if (!response.ok) {
    const failure = await response.json().catch(() => null) as { error?: string; error_description?: string } | null
    clearOidcSession()
    const reason = failure?.error_description || failure?.error || `HTTP ${response.status}`
    throw new Error(`OIDC token exchange failed: ${reason}`)
  }
  const token = await response.json() as { access_token?: string; id_token?: string }
  if (!token.access_token) {
    clearOidcSession()
    throw new Error('OIDC token response did not include an access token')
  }
  sessionStorage.setItem(TOKEN_KEY, token.access_token)
  if (token.id_token) sessionStorage.setItem(ID_TOKEN_KEY, token.id_token)
  sessionStorage.removeItem(STATE_KEY)
  sessionStorage.removeItem(VERIFIER_KEY)
  window.history.replaceState({}, document.title, url.pathname || '/')
  return true
}

export async function beginOidcLogin(forceInteraction = false): Promise<void> {
  const config = oidcConfig()
  const discovery = await loadDiscovery(config.issuer)
  const verifier = randomBase64Url(64)
  const challenge = await sha256Base64Url(verifier)
  const state = randomBase64Url(32)
  sessionStorage.setItem(STATE_KEY, state)
  sessionStorage.setItem(VERIFIER_KEY, verifier)

  const authorize = new URL(discovery.authorization_endpoint)
  authorize.searchParams.set('client_id', config.clientId)
  authorize.searchParams.set('response_type', 'code')
  authorize.searchParams.set('redirect_uri', config.redirectUri)
  authorize.searchParams.set('scope', config.scope)
  authorize.searchParams.set('state', state)
  authorize.searchParams.set('code_challenge', challenge)
  authorize.searchParams.set('code_challenge_method', 'S256')
  if (forceInteraction) { authorize.searchParams.set('prompt', 'login'); authorize.searchParams.set('max_age', '0') }
  window.location.assign(authorize.toString())
}

export async function endOidcSession(): Promise<void> {
  if (!isOidcSecurityMode()) return
  const config = oidcConfig()
  const idToken = sessionStorage.getItem(ID_TOKEN_KEY)
  try {
    const discovery = await loadDiscovery(config.issuer)
    clearOidcSession()
    if (discovery.end_session_endpoint) {
      const logout = new URL(discovery.end_session_endpoint)
      if (idToken) logout.searchParams.set('id_token_hint', idToken)
      logout.searchParams.set('post_logout_redirect_uri', config.redirectUri)
      window.location.assign(logout.toString())
      return
    }
  } catch {
    clearOidcSession()
  }
  window.location.assign(config.redirectUri)
}

function oidcConfig() {
  const issuer = String(import.meta.env.VITE_OIDC_ISSUER || '').replace(/\/$/, '')
  const clientId = String(import.meta.env.VITE_OIDC_CLIENT_ID || '')
  if (!issuer || !clientId) throw new Error('OIDC issuer/client ID are not configured for the SDV UI')
  return {
    issuer,
    clientId,
    scope: String(import.meta.env.VITE_OIDC_SCOPE || 'openid profile email'),
    redirectUri: String(import.meta.env.VITE_OIDC_REDIRECT_URI || `${window.location.origin}/`),
  }
}

type OidcDiscovery = {
  authorization_endpoint: string
  token_endpoint: string
  end_session_endpoint?: string
}

async function loadDiscovery(issuer: string): Promise<OidcDiscovery> {
  const response = await fetch(`${issuer}/.well-known/openid-configuration`, { cache: 'no-store' })
  if (!response.ok) throw new Error('OIDC discovery failed')
  const json = await response.json() as Partial<OidcDiscovery>
  if (!json.authorization_endpoint || !json.token_endpoint) throw new Error('OIDC discovery document is incomplete')
  return {
    authorization_endpoint: json.authorization_endpoint,
    token_endpoint: json.token_endpoint,
    end_session_endpoint: json.end_session_endpoint,
  }
}

function randomBase64Url(bytes: number): string {
  const data = new Uint8Array(bytes)
  crypto.getRandomValues(data)
  return toBase64Url(data)
}

async function sha256Base64Url(value: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))
  return toBase64Url(new Uint8Array(digest))
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = ''
  bytes.forEach((value) => { binary += String.fromCharCode(value) })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}
