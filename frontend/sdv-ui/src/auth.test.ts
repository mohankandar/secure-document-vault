// Task 0065: the browser sign-in against a fake login system.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const SETTINGS = {
  mode: 'oidc', issuer: 'https://login.test/realm', provider: 'customer-oidc', clientId: 'vero-web', scope: 'openid profile',
  authorizationEndpoint: 'https://login.test/auth', tokenEndpoint: 'https://login.test/token', endSessionEndpoint: 'https://login.test/out', revocationEndpoint: 'https://login.test/revoke',
}
let kept: Map<string, string>
let location: { origin: string; href: string; hash: string; assign: ReturnType<typeof vi.fn> }
let replaced: string[]
let events: Event[]
let asked: { url: string; body: URLSearchParams | null }[]
let answer: (url: string, body: URLSearchParams | null) => Response
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const token = (n: string, extra = {}) => json({ access_token: n, token_type: 'Bearer', expires_in: 300, id_token: 'ID-TOKEN', ...extra })

beforeEach(() => {
  vi.resetModules()
  vi.useFakeTimers({ now: new Date('2026-11-24T12:00:00Z') })
  kept = new Map([['sdv.dev.security-mode', 'OIDC']])
  replaced = []; events = []; asked = []
  location = { origin: 'https://vero.test', href: 'https://vero.test/#/workspace/search', hash: '#/workspace/search', assign: vi.fn() }
  const target = new EventTarget()
  vi.stubGlobal('window', {
    location, sessionStorage: { getItem: (k: string) => kept.get(k) ?? null, setItem: (k: string, v: string) => { kept.set(k, v) }, removeItem: (k: string) => { kept.delete(k) } },
    history: { replaceState: (_: unknown, __: string, u: string) => { replaced.push(u) } },
    addEventListener: target.addEventListener.bind(target), removeEventListener: target.removeEventListener.bind(target),
    dispatchEvent: (e: Event) => { events.push(e); return true },
  })
  vi.stubGlobal('document', { title: 'Vero' })
  vi.stubGlobal('BroadcastChannel', undefined)
  answer = (url) => (url === '/api/v1/session/sign-in' ? json(SETTINGS) : token('T1', { refresh_token: 'R1' }))
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    const body = init?.body instanceof URLSearchParams ? init.body : null
    asked.push({ url, body })
    return answer(url, body)
  }))
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

const load = () => import('./auth')
/** Starts, then comes back with these parameters (state filled in unless given). */
async function roundTrip(auth: Awaited<ReturnType<typeof load>>, params: Record<string, string> = { code: 'C1' }) {
  await auth.startSignIn()
  const authorize = new URL(location.assign.mock.calls.at(-1)![0])
  const back = new URL('https://vero.test/')
  for (const [k, v] of Object.entries({ state: authorize.searchParams.get('state')!, ...params })) back.searchParams.set(k, v)
  location.href = back.toString()
  return { authorize, done: auth.completeSignIn() }
}
const code = async (p: Promise<unknown>) => p.then(() => 'no problem', (e) => e.code)

describe('browser sign-in (task 0065)', () => {
  it('asks with PKCE and state, and without a nonce', async () => {
    const auth = await load()
    const { authorize, done } = await roundTrip(auth)
    expect(authorize.origin + authorize.pathname).toBe('https://login.test/auth')
    expect(Object.fromEntries(authorize.searchParams)).toMatchObject({ client_id: 'vero-web', response_type: 'code', redirect_uri: 'https://vero.test/', scope: 'openid profile', code_challenge_method: 'S256' })
    expect(authorize.searchParams.has('nonce')).toBe(false)
    expect(await done).toBe(true)
    const exchange = asked.find((a) => a.url === 'https://login.test/token')!.body!
    expect(exchange.get('grant_type')).toBe('authorization_code')
    expect(exchange.get('code')).toBe('C1')
    expect(exchange.get('code_verifier')!.length).toBeGreaterThanOrEqual(43)
    expect(await auth.bearerHeaders()).toEqual({ Authorization: 'Bearer T1' })
  })

  it('keeps no token anywhere and takes the code out of the address first', async () => {
    const auth = await load()
    const { done } = await roundTrip(auth)
    await done
    expect(JSON.stringify([...kept])).not.toMatch(/T1|R1|ID-TOKEN|C1|verifier/)
    expect(replaced).toEqual(['/#/workspace/search'])
  })

  it('removes what older versions kept', async () => {
    kept.set('sdv.oidc.access-token', 'OLD')
    await load()
    expect(kept.has('sdv.oidc.access-token')).toBe(false)
  })

  it('refuses an answer that is not this sign-in’s, and asks nobody', async () => {
    for (const params of <Record<string, string>[]>[{ code: 'C1', state: 'other' }, { code: 'C1', iss: 'https://evil.test' }]) {
      const auth = await load()
      asked = []
      const { done } = await roundTrip(auth, params)
      expect(await code(done)).toBe('SDV-AUT-016')
      expect(asked.some((a) => a.url === 'https://login.test/token')).toBe(false)
    }
    const auth = await load()
    const { done } = await roundTrip(auth, { error: 'access_denied', error_description: 'their text' })
    expect(await code(done)).toBe('SDV-AUT-017')
    expect(await code(auth.completeSignIn())).toBe('SDV-AUT-016') // used once
  })

  it('refuses an answer after ten minutes', async () => {
    const auth = await load()
    await auth.startSignIn()
    const state = new URL(location.assign.mock.calls.at(-1)![0]).searchParams.get('state')!
    vi.setSystemTime(Date.now() + auth.ROUND_TRIP_LIFE_MS + 1)
    location.href = `https://vero.test/?code=C1&state=${state}`
    expect(await code(auth.completeSignIn())).toBe('SDV-AUT-016')
  })

  it('is no answer without state', async () => {
    const auth = await load()
    location.href = 'https://vero.test/?code=C1'
    expect(await auth.completeSignIn()).toBe(false)
  })

  it('refreshes before the end, once, and keeps the new refresh token', async () => {
    const auth = await load()
    await (await roundTrip(auth)).done
    let n = 1
    answer = (_, body) => { expect(body!.get('grant_type')).toBe('refresh_token'); n += 1; return token(`T${n}`, { refresh_token: `R${n}` }) }
    await vi.advanceTimersByTimeAsync(241_000)
    expect(await auth.bearerHeaders()).toEqual({ Authorization: 'Bearer T2' })
    await vi.advanceTimersByTimeAsync(241_000)
    expect(asked.at(-1)!.body!.get('refresh_token')).toBe('R2')
    vi.setSystemTime(Date.now() + 295_000)
    await Promise.all([auth.bearerHeaders(), auth.bearerHeaders(), auth.bearerHeaders()])
    expect(n).toBe(4)
  })

  it('ends when the token ends and the refresh is refused', async () => {
    const auth = await load()
    await (await roundTrip(auth)).done
    answer = () => json({ error: 'invalid_grant' }, 400)
    vi.setSystemTime(Date.now() + 301_000)
    expect(await auth.bearerHeaders()).toEqual({})
    expect(events.map((e) => e.type)).toEqual([auth.OIDC_SESSION_EXPIRED_EVENT])
    expect((events[0] as CustomEvent).detail).toEqual({ fresh: false })
    expect(auth.hasSignIn()).toBe(false)
  })

  it('says when a fresh sign-in was not accepted', async () => {
    const auth = await load()
    await (await roundTrip(auth)).done
    auth.expireOidcSession()
    expect((events[0] as CustomEvent).detail).toEqual({ fresh: true })
    auth.expireOidcSession()
    expect(events.length).toBe(1)
  })

  it('signs out without an ID token, revokes, and stays signed out', async () => {
    const auth = await load()
    await (await roundTrip(auth)).done
    answer = () => json({})
    await auth.endOidcSession()
    const out = new URL(location.assign.mock.calls.at(-1)![0])
    expect(Object.fromEntries(out.searchParams)).toEqual({ client_id: 'vero-web', post_logout_redirect_uri: 'https://vero.test/' })
    expect(asked.find((a) => a.url === 'https://login.test/revoke')!.body!.get('token')).toBe('R1')
    expect(auth.hasSignIn()).toBe(false)
    expect(auth.shouldStay()).toBe(true)
    expect(await auth.bearerHeaders()).toEqual({})
  })

  it('gives its own codes, never the login system’s text', async () => {
    const auth = await load()
    answer = (url) => (url === '/api/v1/session/sign-in' ? json({ code: 'SDV-AUT-015', message: 'x' }, 503) : json({}))
    expect(await code(auth.startSignIn())).toBe('SDV-AUT-015')
    answer = () => { throw new TypeError('Failed to fetch') }
    expect(await code(auth.startSignIn())).toBe('NETWORK')
    for (const [reply, want] of [[() => json({ error: 'invalid_grant', error_description: 'secret text' }, 400), 'SDV-AUT-017'], [() => json({}, 502), 'SDV-AUT-015'], [() => { throw new TypeError('CORS') }, 'SDV-AUT-015'], [() => json({ access_token: 'T', token_type: 'DPoP' }), 'SDV-AUT-017']] as const) {
      answer = (url) => (url === '/api/v1/session/sign-in' ? json(SETTINGS) : reply())
      const fresh = (vi.resetModules(), await load())
      const { done } = await roundTrip(fresh)
      expect(await code(done)).toBe(want)
    }
  })
})
