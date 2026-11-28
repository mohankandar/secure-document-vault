// Task 0097: the install proof's people part, in a real browser through the customer's web server and login system.
// Nothing secret is printed: passwords and the client secret are read from the scratch folder, tokens stay in memory.
const { createRequire } = require('module')
const path = require('path')
const fs = require('fs')
const req = createRequire('C:/dev/sdv/secure-document-vault/frontend/sdv-ui/package.json')
const { chromium } = req('playwright')

const here = __dirname
const secret = (n) => fs.readFileSync(path.join(here, 'install-0097-secrets', n), 'utf8').trim()
const WEB = 'http://localhost:18299'
const KC = 'http://localhost:18298/realms/acme'
const work = 'C:\\dev\\sdv\\secure-document-vault\\.runtime\\install-0097\\server'
const shots = path.join(here, 'install-0097-shots')
fs.mkdirSync(shots, { recursive: true })
const times = []
const step = async (name, fn) => {
  const t = Date.now()
  const r = await fn()
  const line = `${new Date().toTimeString().slice(0, 8)}  ${name.padEnd(55)} ${((Date.now() - t) / 1000).toFixed(1).padStart(7)} s`
  times.push(line); console.log('PASS  ' + line)
  return r
}

async function signIn(browser, user) {
  const context = await browser.newContext()
  const page = await context.newPage()
  let token = null
  page.on('request', (r) => { const a = r.headers()['authorization']; if (a && r.url().includes('/api/')) token = a })
  await page.goto(WEB + '/')
  // The screens send the person to the login system; maybe after a "Sign in" button.
  const button = page.getByRole('button', { name: /sign in/i })
  await Promise.race([page.waitForURL(/realms\/acme/, { timeout: 20000 }), button.waitFor({ timeout: 20000 }).then(() => button.click())])
  await page.waitForURL(/realms\/acme/, { timeout: 20000 })
  await page.fill('#username', user)
  await page.fill('#password', secret(`kc-user-${user}`))
  await page.click('#kc-login')
  await page.waitForURL(WEB + '/**', { timeout: 30000 })
  return { context, page, token: () => token }
}

async function api(token, method, url, body, headers = {}) {
  const r = await fetch(WEB + url, { method, headers: { Authorization: token, 'Content-Type': 'application/json', ...headers }, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await r.text()
  if (!r.ok) throw new Error(`${method} ${url}: ${r.status} ${text.slice(0, 300)}`)
  return text ? JSON.parse(text) : null
}

;(async () => {
  const browser = await chromium.launch()
  try {
    // 1. The IT Admin's first sign-in: the first-start screen, the name and the licence file.
    const it = await step('H. IT Admin signs in (login system, PKCE)', () => signIn(browser, 'it.admin'))
    await step('H. First-start screen: activate the first entity', async () => {
      await it.page.getByRole('heading', { name: 'Activate the first entity' }).waitFor({ timeout: 30000 })
      await it.page.screenshot({ path: path.join(shots, '1-first-start.png'), fullPage: true })
      await it.page.getByLabel('Name').fill('Acme Mortgage')
      await it.page.getByLabel('Licence file').setInputFiles(path.join(work, 'setup-files', 'licence.json'))
      await it.page.getByRole('button', { name: 'Activate' }).click()
      await it.page.getByRole('heading', { name: 'Activate the first entity' }).waitFor({ state: 'detached', timeout: 30000 })
      await it.page.waitForTimeout(3000)
      await it.page.screenshot({ path: path.join(shots, '2-after-activation.png'), fullPage: true })
    })
    const gone = await fetch(WEB + '/api/v1/first-start', { headers: { Authorization: it.token() } })
    if (gone.status !== 404) throw new Error(`the first-start address should be gone, answered ${gone.status}`)
    console.log('PASS  the first-start address is gone (404)')

    // 2. IT: a storage volume and the sending system (IT, Storage and Integrations).
    const it2 = await step('H. IT Admin signs in again (now in the entity)', async () => { await it.context.close(); return signIn(browser, 'it.admin') })
    await it2.page.waitForTimeout(3000)
    const itToken = it2.token()
    if (!itToken) throw new Error('no token seen from the screens')
    const source = await step('H. IT: storage volume and the sending system', async () => {
      await api(itToken, 'POST', '/api/v1/admin/storage-volumes', { displayName: 'primary', rootPath: path.join(work, 'data'), state: 'ACTIVE', priority: 1, warningPercent: 80, stopWritePercent: 95 })
      const s = await api(itToken, 'POST', '/api/v1/admin/ingestion-sources', { sourceKey: 'loan-system', displayName: 'Loan servicing system', authType: 'OAUTH2_CLIENT_CREDENTIALS', credentialReference: 'login-system/acme-loans' })
      await api(itToken, 'PUT', `/api/v1/admin/ingestion-sources/${s.sourceId}`, { displayName: s.displayName, status: 'ACTIVE', authType: s.authType, credentialReference: s.credentialReference })
      return s
    })

    // 3. The Business Admin: the fields of the sending system.
    const ba = await step('H. Business Admin signs in', () => signIn(browser, 'ba.admin'))
    await ba.page.waitForTimeout(3000)
    await ba.page.screenshot({ path: path.join(shots, '3-business-admin.png'), fullPage: true })
    await step('H. Business Admin: the fields (contract number, borrower)', async () => {
      const field = (label, identifier, normalization, mode) => ({ displayLabel: label, fieldType: 'TEXT', required: true, businessIdentifier: identifier, searchable: true, normalization, searchMode: mode, validationRules: {} })
      await api(ba.token(), 'PUT', `/api/v1/admin/ingestion-sources/${source.sourceId}/metadata-fields/contractNumber`, field('Contract number', true, 'DIGITS_ONLY', 'EXACT'))
      await api(ba.token(), 'PUT', `/api/v1/admin/ingestion-sources/${source.sourceId}/metadata-fields/borrowerName`, field('Borrower', false, 'TRIM', 'PARTIAL'))
    })

    // 4. The sending system files one document with its own token (client credentials).
    await step('H. Sending system: one record, one document', async () => {
      const t = await fetch(`${KC}/protocol/openid-connect/token`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ grant_type: 'client_credentials', client_id: 'acme-loans', client_secret: secret('kc-acme-loans') }) })
      if (!t.ok) throw new Error(`token: ${t.status}`)
      const machine = 'Bearer ' + (await t.json()).access_token
      const s = await api(machine, 'POST', '/api/v1/ingestion/submissions', { sourceSubmissionId: 'LN-40001', expectedDocumentCount: 1,
        expectedDocuments: [{ sourceDocumentId: 'note-1' }], metadata: { contractNumber: '4000-1', borrowerName: 'Jordan Example' } })
      const pdf = fs.readFileSync('C:/dev/sdv/secure-document-vault/scripts/demo-files/' + fs.readdirSync('C:/dev/sdv/secure-document-vault/scripts/demo-files').find((f) => f.endsWith('.pdf')))
      const d = await fetch(`${WEB}/api/v1/ingestion/submissions/${s.submissionId}/documents`, { method: 'POST', body: pdf,
        headers: { Authorization: machine, 'Content-Type': 'application/pdf', 'X-SDV-Source-Document-Id': 'note-1', 'X-SDV-Filename': 'note.pdf' } })
      if (!d.ok) throw new Error(`document: ${d.status} ${(await d.text()).slice(0, 300)}`)
    })

    // 5. A person finds it.
    await step('H. Business User finds the record by its contract number', async () => {
      const reader = await signIn(browser, 'reader')
      await reader.page.waitForTimeout(3000)
      let found = null
      for (let i = 0; i < 20 && !found; i++) {
        const r = await api(reader.token(), 'POST', '/api/v1/business/search', { sourceId: source.sourceId, criteria: { contractNumber: '40001' }, page: 1, pageSize: 10 })
        found = r.records && r.records.length ? r.records[0] : null
        if (!found) await new Promise((res) => setTimeout(res, 1500))
      }
      if (!found) throw new Error('the record was not found')
      await reader.page.screenshot({ path: path.join(shots, '4-business-user.png'), fullPage: true })
    })
  } finally {
    fs.appendFileSync(path.join(here, 'install-0097-times.txt'), times.join('\n') + '\n')
    await browser.close()
  }
})().catch((e) => { console.error('FAIL  ' + e.message); process.exit(1) })
