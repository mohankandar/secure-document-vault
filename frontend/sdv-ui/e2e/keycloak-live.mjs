// Task 0088, run by Test-SDV-KeycloakLive.ps1: the built screens, a real Keycloak and a real browser.
// Reads the test people's passwords from their file itself; prints no password and no token.
import fs from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from '@playwright/test'

const [usersFile, issuer, apiPort] = process.argv.slice(2)
const ORIGIN = 'http://localhost:5183'
const DIST = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../dist')
const people = {}
for (const line of fs.readFileSync(usersFile, 'utf8').split(/\r?\n/)) {
  const m = /^([A-Z_]+)\.(username|password)=(.+)$/.exec(line)
  if (m) (people[m[1]] ??= {})[m[2]] = m[3]
}
const secrets = Object.values(people).map((p) => p.password).filter(Boolean)
const clean = (s) => secrets.reduce((t, x) => t.split(x).join('***'), String(s)).replace(/eyJ[\w-]+\.[\w.-]+/g, '***')
let failures = 0
const check = (name, ok, seen = '') => { if (!ok) failures += 1; console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${seen ? `: ${clean(seen)}` : ''}`) }

const keycloak = new URL(issuer).origin
const CSP = `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; img-src 'self' blob: data:; font-src 'self' https://fonts.gstatic.com; connect-src 'self' ${keycloak}; frame-src 'self' blob:; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2' }
// The screens as a customer's proxy serves them; the API passed on.
const server = http.createServer((q, r) => {
  if (q.url.startsWith('/api/')) {
    const p = http.request({ host: '127.0.0.1', port: apiPort, path: q.url, method: q.method, headers: { ...q.headers, host: 'localhost' } }, (a) => { r.writeHead(a.statusCode, a.headers); a.pipe(r) })
    p.on('error', () => { r.writeHead(502); r.end() })
    q.pipe(p)
    return
  }
  let f = path.join(DIST, decodeURIComponent(new URL(q.url, ORIGIN).pathname))
  if (!f.startsWith(DIST) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) f = path.join(DIST, 'index.html')
  let body = fs.readFileSync(f)
  // A local http login server is not covered by the page's own policy (https only).
  if (f.endsWith('index.html')) body = body.toString().replace("connect-src 'self' https:", `connect-src 'self' https: ${keycloak}`)
  r.writeHead(200, { 'Content-Type': types[path.extname(f)] ?? 'application/octet-stream', 'Content-Security-Policy': CSP, 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'no-referrer' })
  r.end(body)
})
await new Promise((ok, no) => server.once('error', no).listen(5183, 'localhost', ok))

const api = async (token, entity = null) => {
  const a = await fetch(`http://127.0.0.1:${apiPort}/api/v1/session`, { headers: { ...(token && { Authorization: `Bearer ${token}` }), ...(entity && { 'X-SDV-Entity-Id': entity }) } })
  const body = await a.json().catch(() => ({}))
  return { status: a.status, code: body.code ?? '', body }
}
const browser = await chromium.launch()
async function keycloakForm(page, who) {
  await page.locator('#username').waitFor({ timeout: 30_000 })
  await page.locator('#username').fill(people[who].username)
  await page.locator('#password').fill(people[who].password)
  await page.locator('#kc-login').click()
}
/** A browser of its own, signed in to Vero as this person. */
async function visit(who) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
  const page = await context.newPage()
  const seen = { tokens: [], entity: null, exchanges: 0, refreshes: 0, life: 0, csp: 0, refused: [], authorize: '' }
  page.on('console', (m) => { if (/Content Security Policy/i.test(m.text())) seen.csp += 1 })
  page.on('request', (q) => {
    if (q.url().startsWith(`${issuer}/protocol/openid-connect/auth`)) seen.authorize = q.url()
    if (q.url() === `${issuer}/protocol/openid-connect/token`) { if (/grant_type=refresh_token/.test(q.postData() ?? '')) seen.refreshes += 1; else seen.exchanges += 1 }
    const t = /^Bearer (.+)$/.exec(q.headers().authorization ?? ''); if (t) seen.tokens.push(t[1])
    seen.entity = q.headers()['x-sdv-entity-id'] ?? seen.entity
  })
  page.on('response', async (a) => {
    if (a.url().startsWith(`${ORIGIN}/api/`) && a.status() >= 400) seen.refused.push(a.status() + ' ' + ((await a.json().catch(() => ({}))).code ?? ''))
    if (a.url() === `${issuer}/protocol/openid-connect/token` && a.ok()) seen.life = (await a.json().catch(() => ({}))).expires_in ?? seen.life
  })
  await page.clock.install()
  await page.goto(`${ORIGIN}/#/workspace/search`)
  await keycloakForm(page, who)
  return { context, page, seen }
}
const account = (page) => page.getByRole('button', { name: /^Account:/ })

try {
  const none = await api()
  check('no token is refused', none.status === 401, `${none.status} ${none.code}`)

  for (const who of ['BUSINESS_USER', 'RESTRICTED_STEWARD', 'BUSINESS_ADMIN', 'IT_ADMIN']) {
    const { context, page, seen } = await visit(who)
    await account(page).waitFor({ timeout: 30_000 })
    // Somebody with two entities (the demo's Business Admin) chooses one first, as the screens do.
    const list = page.getByRole('list', { name: 'Your entities' }).getByRole('button')
    const started = page.getByRole('combobox', { name: 'Search records' })
    if (who === 'BUSINESS_ADMIN') {
      await list.first().or(started).first().waitFor({ timeout: 30_000 })
      if (await list.count() > 0) await list.first().click()
      for (let i = 0; i < 60 && !seen.entity; i += 1) await new Promise((ok) => setTimeout(ok, 250))
    }
    const s = await api(seen.tokens.at(-1), seen.entity)
    const roles = JSON.stringify(s.body.roles ?? s.body.user?.roles ?? s.body)
    check(`${who} signs in with that role`, s.status === 200 && roles.includes(`"${who}"`), s.status === 200 ? '' : `${s.status} ${s.code}`)
    await context.close()
  }

  const { context, page, seen } = await visit('DOCUMENT_STEWARD')
  await account(page).waitFor({ timeout: 30_000 })
  check('DOCUMENT_STEWARD signs in', true, await account(page).getAttribute('aria-label'))
  check('PKCE S256 is used', /code_challenge_method=S256/.test(seen.authorize))
  check('the address is clean after signing in', !/[?&#](code|state)=/.test(page.url()))
  check('no token is kept in storage', !(await page.evaluate(() => JSON.stringify([{ ...sessionStorage }, { ...localStorage }]))).includes('eyJ'))

  await page.getByRole('combobox', { name: 'Search records' }).fill('300000000001')
  await page.keyboard.press('Enter')
  await page.getByRole('row', { name: /300000000001/ }).first().click({ timeout: 20_000 })
  await page.getByRole('button', { name: /Open documents/ }).click()
  await page.getByRole('img', { name: /Document page 1/ }).waitFor({ timeout: 30_000 })
  check('a document opens', true)

  const before = seen.refreshes
  await page.clock.fastForward(Math.ceil(seen.life * 0.85) * 1000)
  for (let i = 0; i < 40 && seen.refreshes === before; i += 1) await new Promise((ok) => setTimeout(ok, 250))
  check('the sign-in is renewed by itself', seen.refreshes > before && seen.exchanges === 1, `token life ${seen.life} s`)

  const exchanges = seen.exchanges
  await page.reload()
  await account(page).waitFor({ timeout: 30_000 })
  check('a reload signs in again without asking', seen.exchanges === exchanges + 1)
  const mine = await api(seen.tokens.at(-1))
  check('the renewed sign-in works on the API', mine.status === 200, `${mine.status} ${mine.code}`)

  await account(page).click()
  await page.getByRole('button', { name: 'Sign out' }).click()
  const confirm = page.locator('#kc-logout')
  const out = page.getByRole('heading', { name: /^You are signed out of/ })
  await confirm.or(out).first().waitFor({ timeout: 30_000 })
  if (await confirm.isVisible()) await confirm.click()
  await out.waitFor({ timeout: 30_000 })
  check('signs out', true)
  await page.reload()
  await out.waitFor({ timeout: 30_000 })
  check('still signed out after a reload', true)
  const again = await context.newPage()
  await again.goto(`${ORIGIN}/#/workspace/search`)
  await again.locator('#username').waitFor({ timeout: 30_000 })
  check('Keycloak asks again after signing out', true)
  check('no CSP violation', seen.csp === 0, String(seen.csp))
  await context.close()

  const no = await visit('NO_GROUP')
  for (let i = 0; i < 60 && no.seen.refused.length === 0; i += 1) await new Promise((ok) => setTimeout(ok, 250))
  const refused = no.seen.tokens.length ? await api(no.seen.tokens.at(-1)) : { status: 0, code: 'no token' }
  check('a person with no group is refused', [401, 403].includes(refused.status), `${refused.status} ${refused.code}`)
  await no.page.getByText('SDV-AUT-005').first().waitFor({ timeout: 15_000 })
  check('and sees only the refusal', await no.page.getByRole('combobox', { name: 'Search records' }).count() === 0 && await no.page.getByRole('row').count() === 0)
  await no.context.close()

  // Another client of the same realm: Keycloak's own account page, signed in as the same steward.
  const other = await browser.newContext()
  const page2 = await other.newPage()
  let foreign = null
  page2.on('request', (q) => { const t = /^Bearer (.+)$/i.exec(q.headers().authorization ?? ''); if (t) foreign = t[1] })
  await page2.goto(`${issuer}/account/`)
  await keycloakForm(page2, 'DOCUMENT_STEWARD')
  for (let i = 0; i < 80 && !foreign; i += 1) await new Promise((ok) => setTimeout(ok, 250))
  const client = foreign ? JSON.parse(Buffer.from(foreign.split('.')[1], 'base64url').toString()).azp : 'no token'
  const f = foreign ? await api(foreign) : { status: 0, code: 'no token' }
  check('a token from another client is refused', f.status === 401, `${client}: ${f.status} ${f.code}`)
  await other.close()
} catch (e) {
  failures += 1
  console.log(clean(`FAIL  ${String(e.message ?? e).split('\n')[0]}`))
  for (const c of browser.contexts()) for (const p of c.pages()) console.log(clean(`page ${p.url().split('?')[0]}: ${(await p.innerText('body').catch(() => '')).slice(0, 300)}`))
}
await browser.close()
server.close()
console.log(failures === 0 ? 'RESULT: PASS' : `RESULT: FAIL (${failures})`)
process.exit(failures === 0 ? 0 : 1)
