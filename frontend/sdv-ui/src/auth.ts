// Task 0065: sign-in in the browser (authorization code with PKCE, public client). The login system and its addresses
// come from Vero (GET /api/v1/session/sign-in), never from the build. Tokens are kept in memory only: a reload or a new
// tab signs in again through the login system, which usually asks nothing. An ID token is never used or kept.
// What is in sessionStorage is the round trip only (state, PKCE verifier, where to come back to), used once.

// Local test sign-in exists in development builds only; a production build always signs in.
// DEV_MODE_KEY: the screen tour switches a development build to the real flow with it.
const DEV_MODE_KEY = 'sdv.dev.security-mode'
const devMode = (): string => {
  try { return window.sessionStorage.getItem(DEV_MODE_KEY) ?? '' } catch { return '' }
}
const MODE = import.meta.env.DEV
  ? (devMode() || String(import.meta.env.VITE_SECURITY_MODE || 'LOCAL')).toUpperCase()
  : 'OIDC'

export function isLocalSecurityMode(): boolean {
  return MODE === 'LOCAL'
}

export function isOidcSecurityMode(): boolean {
  return !isLocalSecurityMode()
}

export type SignInSettings = {
  mode: 'oidc' | 'local'; issuer: string | null; provider: string | null; clientId: string | null; scope: string | null
  authorizationEndpoint: string | null; tokenEndpoint: string | null; endSessionEndpoint: string | null; revocationEndpoint: string | null
}
/** code: a Vero code (error-codes.md), never the login system's own text. */
export class SignInProblem extends Error {
  constructor(public readonly code: string) { super(code); this.name = 'SignInProblem' }
}

const ROUND_TRIP_KEY = 'sdv.signin.round-trip'
/** Set by signing out (or a problem): don't go to the login system by itself. */
const STAY_KEY = 'sdv.signin.stay'
export const ROUND_TRIP_LIFE_MS = 10 * 60_000
const REFRESH_AT = 0.8
const NEAR_END_MS = 20_000

type Tokens = { access: string; refresh: string | null; since: number; until: number }
let tokens: Tokens | null = null
let settings: SignInSettings | null = null
let refreshing: Promise<boolean> | null = null
let refreshTimer: ReturnType<typeof setTimeout> | null = null
let completing: Promise<boolean> | null = null

export const OIDC_SESSION_EXPIRED_EVENT = 'sdv:oidc-session-expired'
/** The entity was switched: start the app again without losing the sign-in. */
export const RESTART_EVENT = 'sdv:restart'

const store = {
  get: (k: string) => { try { return window.sessionStorage.getItem(k) } catch { return null } },
  set: (k: string, v: string) => { try { window.sessionStorage.setItem(k, v) } catch { /* the sign-in will not complete; it says so */ } },
  drop: (k: string) => { try { window.sessionStorage.removeItem(k) } catch { /* nothing kept */ } },
}

/** Old versions kept tokens here: never again. */
function dropOldTokens() {
  for (const k of ['sdv.oidc.access-token', 'sdv.oidc.id-token', 'sdv.oidc.state', 'sdv.oidc.pkce-verifier']) store.drop(k)
}

export async function loadSignInSettings(): Promise<SignInSettings> {
  if (settings) return settings
  let response: Response
  try {
    response = await fetch('/api/v1/session/sign-in', { cache: 'no-store' })
  } catch {
    throw new SignInProblem('NETWORK')
  }
  const body = await response.json().catch(() => null) as (SignInSettings & { code?: string }) | null
  if (!response.ok || !body) throw new SignInProblem(/^SDV-[A-Z]{3}-\d{3}$/.test(body?.code ?? '') ? body!.code! : 'SDV-AUT-014')
  settings = body
  return body
}

export const hasSignIn = () => tokens !== null
export const shouldStay = () => store.get(STAY_KEY) === '1'
export const stayHere = () => store.set(STAY_KEY, '1')
const redirectUri = () => `${window.location.origin}/`

/** Goes to the login system. Comes back to the route the person was on. */
export async function startSignIn(): Promise<void> {
  const s = await loadSignInSettings()
  if (s.mode !== 'oidc' || !s.authorizationEndpoint || !s.clientId) throw new SignInProblem('SDV-AUT-014')
  const verifier = randomBase64Url(64)
  const state = randomBase64Url(32)
  store.set(ROUND_TRIP_KEY, JSON.stringify({ state, verifier, at: Date.now(), route: window.location.hash }))
  store.drop(STAY_KEY)
  const authorize = new URL(s.authorizationEndpoint)
  authorize.searchParams.set('client_id', s.clientId)
  authorize.searchParams.set('response_type', 'code')
  authorize.searchParams.set('redirect_uri', redirectUri())
  authorize.searchParams.set('scope', s.scope ?? 'openid')
  authorize.searchParams.set('state', state)
  authorize.searchParams.set('code_challenge', await sha256Base64Url(verifier))
  authorize.searchParams.set('code_challenge_method', 'S256')
  window.location.assign(authorize.toString())
}

/**
 * Completes a sign-in when this address is the login system's answer. True when signed in; false when it is no answer.
 * React's StrictMode runs effects twice in development: single flight, because a code works once.
 */
export function completeSignIn(): Promise<boolean> {
  if (!completing) completing = complete().finally(() => { completing = null })
  return completing
}

async function complete(): Promise<boolean> {
  const url = new URL(window.location.href)
  const code = url.searchParams.get('code'), state = url.searchParams.get('state'), refused = url.searchParams.get('error'), iss = url.searchParams.get('iss')
  if (!state || (!code && !refused)) return false
  const kept = store.get(ROUND_TRIP_KEY)
  store.drop(ROUND_TRIP_KEY) // used once, whatever happens
  let trip: { state?: string; verifier?: string; at?: number; route?: string } | null = null
  try { trip = kept ? JSON.parse(kept) : null } catch { trip = null }
  // The answer leaves the address bar and the history before anything else, so a code is never kept there.
  window.history.replaceState({}, document.title, url.pathname + (typeof trip?.route === 'string' && trip.route.startsWith('#/') ? trip.route : ''))
  const age = Date.now() - (trip?.at ?? 0)
  if (!trip || !trip.verifier || trip.state !== state || age < 0 || age > ROUND_TRIP_LIFE_MS) throw new SignInProblem('SDV-AUT-016')
  const s = await loadSignInSettings()
  if (iss && s.issuer && iss !== s.issuer) throw new SignInProblem('SDV-AUT-016')
  if (!code) throw new SignInProblem('SDV-AUT-017')
  await ask({ grant_type: 'authorization_code', code, redirect_uri: redirectUri(), code_verifier: trip.verifier })
  store.drop(STAY_KEY)
  return true
}

async function ask(form: Record<string, string>): Promise<void> {
  const s = await loadSignInSettings()
  if (!s.tokenEndpoint || !s.clientId) throw new SignInProblem('SDV-AUT-014')
  let response: Response
  try {
    response = await fetch(s.tokenEndpoint, {
      method: 'POST', credentials: 'omit', cache: 'no-store', referrerPolicy: 'no-referrer',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ ...form, client_id: s.clientId }),
    })
  } catch {
    throw new SignInProblem('SDV-AUT-015') // no answer, or the login system doesn't allow this web origin
  }
  if (!response.ok) throw new SignInProblem(response.status >= 500 ? 'SDV-AUT-015' : 'SDV-AUT-017')
  const body = await response.json().catch(() => null) as { access_token?: unknown; refresh_token?: unknown; expires_in?: unknown; token_type?: unknown } | null
  if (typeof body?.access_token !== 'string' || !body.access_token || String(body.token_type ?? 'Bearer').toLowerCase() !== 'bearer') throw new SignInProblem('SDV-AUT-017')
  const seconds = typeof body.expires_in === 'number' && body.expires_in > 0 ? Math.min(body.expires_in, 86_400) : 300
  const now = Date.now()
  tokens = {
    access: body.access_token,
    refresh: typeof body.refresh_token === 'string' && body.refresh_token ? body.refresh_token : tokens?.refresh ?? null,
    since: now, until: now + seconds * 1000,
  }
  if (refreshTimer) clearTimeout(refreshTimer)
  if (tokens.refresh) refreshTimer = setTimeout(() => { void refresh() }, seconds * 1000 * REFRESH_AT)
}

/** One refresh at a time. False: it did not work; the token in hand still counts until it ends. */
function refresh(): Promise<boolean> {
  if (!tokens?.refresh) return Promise.resolve(false)
  if (!refreshing) {
    refreshing = ask({ grant_type: 'refresh_token', refresh_token: tokens.refresh })
      .then(() => true, (e) => {
        // Refused: the refresh token is of no use any more. No answer: try again later with it.
        if (tokens && e instanceof SignInProblem && e.code === 'SDV-AUT-017') tokens = { ...tokens, refresh: null }
        return false
      })
      .finally(() => { refreshing = null })
  }
  return refreshing
}

export async function bearerHeaders(): Promise<Record<string, string>> {
  if (!isOidcSecurityMode()) return {}
  if (tokens && tokens.until - Date.now() < NEAR_END_MS) await refresh()
  if (!tokens) return {}
  if (tokens.until <= Date.now()) { expireOidcSession(); return {} }
  return { Authorization: `Bearer ${tokens.access}` }
}

/** The sign-in ended or was not accepted. fresh: it was just made, so signing in again will not help by itself. */
export function expireOidcSession(): void {
  const fresh = !!tokens && Date.now() - tokens.since < 30_000
  const had = !!tokens
  forget()
  if (had) window.dispatchEvent(new CustomEvent(OIDC_SESSION_EXPIRED_EVENT, { detail: { fresh } }))
}

function forget() {
  tokens = null
  if (refreshTimer) clearTimeout(refreshTimer)
  refreshTimer = null
}

const channel = typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel('sdv-sign-out')
// Signed out in another tab: nothing of this one stays on screen either.
if (channel) channel.onmessage = (e) => {
  if (e.data !== 'signed-out' || !tokens) return
  forget()
  stayHere()
  window.dispatchEvent(new CustomEvent(OIDC_SESSION_EXPIRED_EVENT, { detail: { fresh: false, signedOut: true } }))
}

/** Forgets the sign-in, here and in other tabs, and ends the login system's session when it can. Never an ID token. */
export async function endOidcSession(): Promise<void> {
  if (!isOidcSecurityMode()) return
  const refreshToken = tokens?.refresh ?? null
  forget()
  stayHere()
  channel?.postMessage('signed-out')
  const s = settings
  if (refreshToken && s?.revocationEndpoint && s.clientId) {
    const gone = fetch(s.revocationEndpoint, {
      method: 'POST', credentials: 'omit', keepalive: true, referrerPolicy: 'no-referrer',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ token: refreshToken, token_type_hint: 'refresh_token', client_id: s.clientId }),
    }).then(() => undefined, () => undefined)
    await Promise.race([gone, new Promise<void>((r) => { setTimeout(r, 2000) })])
  }
  if (s?.endSessionEndpoint && s.clientId) {
    const out = new URL(s.endSessionEndpoint)
    out.searchParams.set('client_id', s.clientId)
    out.searchParams.set('post_logout_redirect_uri', redirectUri())
    window.location.assign(out.toString())
    return
  }
  window.location.assign(redirectUri())
}

/** Task 0012: how people sign in, in words, for About this installation. Never a token or a secret. */
export function signInSummary(): { kind: 'local' | 'oidc'; address: string | null } {
  if (isLocalSecurityMode()) return { kind: 'local', address: null }
  return { kind: 'oidc', address: settings?.issuer ?? null }
}

function randomBase64Url(bytes: number): string {
  const data = new Uint8Array(bytes)
  crypto.getRandomValues(data)
  return toBase64Url(data)
}

async function sha256Base64Url(value: string): Promise<string> {
  return toBase64Url(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value))))
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = ''
  bytes.forEach((value) => { binary += String.fromCharCode(value) })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

dropOldTokens()
