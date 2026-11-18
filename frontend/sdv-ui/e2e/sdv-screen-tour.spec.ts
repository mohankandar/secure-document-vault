import { expect, test, type Page, type TestInfo } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'

// Screen tour of the R7 UI (served at /). Needs the app running locally in LOCAL security mode,
// where the account menu offers the development-only "Local test role" switch.

const demoRecord = process.env.SDV_DEMO_CONTRACT ?? '100000000001' // created by Seed-SDV-Demo-Records.ps1

/** Readable names the account button shows for each local test role (never the technical subject). */
const ACCOUNT_BY_ROLE: Record<string, string> = {
  'Business User': 'Account: Local business user, Business user',
  'Document Steward': 'Account: Local document steward, Document steward',
  'Business Administrator': 'Account: Local business administrator, Business administrator',
  'Tenant IT Administrator': 'Account: Local Tenant IT administrator, Tenant IT administrator',
  'Platform Operations Administrator': 'Account: Local platform operations administrator, Platform operations administrator',
  'Local Administrator (Tenant IT + Business admin)': 'Account: Local administrator, Business administrator, Tenant IT administrator',
}
const AREA_TAB_BY_ROLE: Record<string, string> = {
  'Business User': 'Home',
  'Document Steward': 'Home',
  'Business Administrator': 'Business administration',
  'Tenant IT Administrator': 'Tenant IT',
  'Platform Operations Administrator': 'Platform operations',
  'Local Administrator (Tenant IT + Business admin)': 'Business administration',
}

const accountButton = (page: Page) => page.getByRole('banner').getByRole('button', { name: /^Account: / })

async function openAccountMenu(page: Page) {
  const button = accountButton(page)
  await expect(button).toBeVisible({ timeout: 15000 })
  if ((await button.getAttribute('aria-expanded')) !== 'true') await button.click()
  const menu = page.getByRole('dialog', { name: 'Account menu' })
  await expect(menu).toBeVisible()
  return menu
}

async function selectRole(page: Page, role: string) {
  const expected = ACCOUNT_BY_ROLE[role]
  if ((await accountButton(page).getAttribute('aria-label').catch(() => null)) !== expected) {
    const menu = await openAccountMenu(page)
    const roleSelect = menu.getByLabel('Local test role')
    await expect(roleSelect).toBeVisible()
    // Selecting a role reloads the page with the new local test identity.
    await Promise.all([page.waitForEvent('load'), roleSelect.selectOption({ label: role })])
  }
  // Wait for the actual principal: the account button names the person and role, never the login ID.
  await expect(accountButton(page)).toHaveAttribute('aria-label', expected, { timeout: 15000 })
  await expect(page.getByRole('banner')).not.toContainText('local-')
  await expect(page.getByRole('banner').getByRole('navigation', { name: 'Areas' }).getByRole('button', { name: new RegExp(`^${AREA_TAB_BY_ROLE[role]}( .+)?$`) }))
    .toHaveAttribute('aria-current', 'page')
}

async function capture(page: Page, testInfo: TestInfo, name: string) {
  const screenshot = await page.screenshot({ path: testInfo.outputPath(`${name}.png`), fullPage: true, animations: 'disabled' })
  await testInfo.attach(name, { body: screenshot, contentType: 'image/png' })
}

/** An area button; it may carry a count of things needing attention ("Platform operations 2 need attention"). */
const areaTab = (page: Page, name: string) => page.getByRole('banner').getByRole('navigation', { name: 'Areas' }).getByRole('button', { name: new RegExp(`^${name}( .+)?$`) })

/** Opening the app without an address lands on the person's first area (Home for search roles). */
async function expectLanding(page: Page, area: string) {
  await page.goto('/')
  await expect(areaTab(page, area)).toHaveAttribute('aria-current', 'page', { timeout: 15000 })
}

/** Roles without SEARCH_RECORDS: they land on their own area, have no Home or Records tab, and the Records address shows the access page. */
async function expectNoRecordsAccess(page: Page, testInfo: TestInfo, ownArea: string) {
  await expectLanding(page, ownArea)
  await expect(areaTab(page, 'Home')).toHaveCount(0)
  await expect(areaTab(page, 'Records')).toHaveCount(0)
  const back = await page.evaluate(() => window.location.hash)
  await page.evaluate(() => { window.location.hash = '#/workspace/search' })
  await expect(page.getByRole('heading', { name: /This page isn.t part of your role/ })).toBeVisible({ timeout: 15000 })
  await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible()
  await expect(page.getByText(/enforced by the server|only reflects it/)).toHaveCount(0)
  await expect(page.getByRole('searchbox', { name: 'Search records' })).toBeHidden()
  await capture(page, testInfo, 'records-not-assigned')
  await page.evaluate((h) => { window.location.hash = h }, back)
}

/** Business administration (task 0008): People & roles, Roles & permissions save, and a card that fails to load. */
async function businessAdminChecks(page: Page, testInfo: TestInfo) {
  const sections = page.getByRole('tablist', { name: 'Business administration sections' })
  await sections.getByRole('tab', { name: 'People & roles' }).click()
  const people = page.locator('table.v-table')
  await expect(people).toContainText('Name in your sign-in system')
  await expect(people).toContainText('Can change in Vero')
  await expect(people.locator('tbody tr')).toHaveCount(4)
  await expect(people.locator('tbody')).not.toContainText(/Platform operations/i)
  await expect(page.getByText(/Claim value/)).toHaveCount(0)

  await sections.getByRole('tab', { name: 'Roles & permissions' }).click()
  const print = page.getByRole('switch', { name: 'Print for Business user' })
  await expect(print).toBeVisible({ timeout: 15000 })
  await expect(page.getByText(/Baseline|PRINT_DOCUMENT|PERMANENT_REDACTION/)).toHaveCount(0)
  await expect(page.locator('table.v-table tbody tr').first()).toContainText('Always on')
  const before = await print.getAttribute('aria-checked')
  for (const _ of [0, 1]) {
    // Change, save, then change back and save again, so the role rules end as they started.
    await print.click()
    await page.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Permissions saved' }).last()).toBeVisible({ timeout: 15000 })
  }
  await expect(print).toHaveAttribute('aria-checked', before ?? 'false')

  // A card that fails to load: plain words, a reference, and Try again reloads only that card.
  let fail = true
  await page.route('**/api/v1/admin/role-capabilities', (route) => (fail && route.request().method() === 'GET'
    ? route.fulfill({ status: 500, json: { message: 'ApiRequestError: not mocked' } })
    : route.continue()))
  await sections.getByRole('tab', { name: 'People & roles' }).click()
  await sections.getByRole('tab', { name: 'Roles & permissions' }).click()
  const card = page.getByRole('alert', { name: "Roles & permissions didn't load" })
  await expect(card).toBeVisible({ timeout: 15000 })
  await expect(card).toContainText('The rest of the page still works.')
  await expect(card).toContainText('Ref ')
  await expect(page.getByText(/ApiRequestError|not mocked/)).toHaveCount(0)
  await capture(page, testInfo, 'card-error')
  fail = false
  await card.getByRole('button', { name: 'Try again' }).click()
  await expect(page.getByRole('switch', { name: 'Print for Business user' })).toBeVisible({ timeout: 15000 })
  await page.unroute('**/api/v1/admin/role-capabilities')
}

// ---- Keeping documents, Search fields, Record title (task 0009) ----

const ADMIN_HEADERS = { 'X-SDV-Tenant-Id': '11111111-1111-1111-1111-111111111111', 'X-SDV-Subject': 'local-business-admin', 'X-SDV-Roles': 'BUSINESS_ADMIN' }
/** A demo record with First Name Ava and Last Name Patel (seeded with -FreshTestData). */
const titleRecord = process.env.SDV_DEMO_TITLE_CONTRACT ?? '300000000076'
const sectionTab = (page: Page, name: string) => page.getByRole('tablist', { name: 'Business administration sections' }).getByRole('tab', { name })

/** Starts a keeping rule through the New rule panel. amount is ignored for Forever. */
async function startKeepingRule(page: Page, amount: string, unit: 'years' | 'months' | 'days' | 'forever', why: string) {
  await page.getByRole('button', { name: 'New rule' }).click()
  const panel = page.getByRole('dialog', { name: 'New rule for how long to keep' })
  await panel.getByLabel('Unit').selectOption(unit)
  if (unit !== 'forever') await panel.getByLabel('How many').fill(amount)
  await panel.getByLabel('Why this changed').fill(why)
  await panel.getByLabel('This change has been approved').check()
  return panel
}

/** A new rule in years saves and is listed under Earlier rules; the earlier rule is then put back. */
async function keepingDocumentsChecks(page: Page, testInfo: TestInfo) {
  await sectionTab(page, 'Keeping documents').click()
  const current = page.getByRole('region', { name: 'Rule in use' }).locator('.keep-rule')
  await expect(current).toHaveText(/^Keep (forever|for )/, { timeout: 15000 })
  await expect(page.getByText(/Superseded|Version \d|Indefinite/)).toHaveCount(0)
  await expect(page.getByText('Counted from the day each document arrives.')).toBeVisible()
  const before = (await current.innerText()).trim()

  const panel = await startKeepingRule(page, '7', 'years', 'e2e tour: seven years')
  await expect(panel).toContainText('2,555 days. Or choose “Forever”.')
  await capture(page, testInfo, 'keeping-documents-new-rule')
  await panel.getByRole('button', { name: 'Start using this rule' }).click()
  await expect(page.getByRole('status').filter({ hasText: 'New rule in use' }).last()).toBeVisible({ timeout: 15000 })
  await expect(current).toHaveText('Keep for 7 years')
  const earlier = page.locator('table.v-table').filter({ hasText: 'Set by' })
  await expect(earlier.locator('tbody tr').first()).toContainText('Keep for 7 years')
  await expect(earlier.locator('tbody tr').first()).toContainText('In use')
  await expect(earlier.locator('tbody tr').first()).toContainText('e2e tour: seven years')
  await expect(earlier.locator('tbody tr').nth(1)).toContainText(before)
  await capture(page, testInfo, 'keeping-documents-earlier-rules')

  // Put the earlier rule back (rules are never edited, so this is one more rule in the list).
  const m = /^Keep for ([\d,]+) (year|month|day)s?$/.exec(before)
  const back = await startKeepingRule(page, m ? m[1].replace(/,/g, '') : '', m ? (`${m[2]}s` as 'years' | 'months' | 'days') : 'forever', 'e2e tour: back to the earlier rule')
  await back.getByRole('button', { name: 'Start using this rule' }).click()
  await expect(current).toHaveText(before, { timeout: 15000 })
}

/** Search fields: words only, a match change saves, drag reorder saves, and the Record title is set. Returns what to put back. */
async function searchFieldsChecks(page: Page, testInfo: TestInfo) {
  const sources = await (await page.request.get('/api/v1/admin/metadata-sources', { headers: ADMIN_HEADERS })).json() as { sourceId: string; displayName: string }[]
  // The demo source by name: a test sending system added by the Tenant IT tour must not be picked instead.
  const sourceId = (sources.find((s) => s.displayName === 'LexaVault') ?? sources[0]).sourceId
  const path = (what: string) => `/api/v1/admin/metadata-sources/${sourceId}/${what}`
  const storedOrder = await (await page.request.get(path('display-order'), { headers: ADMIN_HEADERS })).json() as string[]
  const fields = await (await page.request.get(`/api/v1/admin/ingestion-sources/${sourceId}/metadata-fields`, { headers: ADMIN_HEADERS })).json() as { fieldKey: string; displayLabel: string }[]
  const saved = {
    // No stored order means label order (A to Z); the server only accepts a full list, so that is what is put back.
    order: storedOrder.length ? storedOrder : [...fields].sort((a, b) => a.displayLabel.localeCompare(b.displayLabel, undefined, { sensitivity: 'base' }) || a.fieldKey.localeCompare(b.fieldKey)).map((f) => f.fieldKey),
    title: await (await page.request.get(path('record-title'), { headers: ADMIN_HEADERS })).json() as string[],
  }
  const restore = async () => {
    for (const [what, fieldKeys] of [['display-order', saved.order], ['record-title', saved.title]] as const) {
      const r = await page.request.put(path(what), { headers: { ...ADMIN_HEADERS, 'Content-Type': 'application/json' }, data: { fieldKeys } })
      expect(r.ok(), `restoring ${what}`).toBeTruthy()
    }
  }

  await sectionTab(page, 'Search fields').click()
  const table = page.getByRole('table', { name: 'Search fields' })
  await expect(table).toBeVisible({ timeout: 15000 })
  await expect(page.getByRole('button', { name: /Reorder/ })).toHaveCount(0)
  await expect(page.getByText(/current UI|Field key|DIGITS_ONLY|PARTIAL|PREFIX/)).toHaveCount(0)
  await expect(page.getByText(/^Records per page: \d+ \(most 50\)$/)).toBeVisible()
  await expect(table.locator('tbody tr').filter({ hasText: 'Contract Number' })).toContainText('Identifier · always searchable')

  // How it matches: change Last Name to Starts with, save, then put it back.
  const lastName = table.locator('tbody tr').filter({ hasText: 'Last Name' })
  const match = lastName.locator('td[data-label="How it matches"]')
  const originalMatch = (await match.innerText()).trim()
  const choose = async (choice: RegExp) => {
    await lastName.getByRole('button', { name: 'Edit Last Name' }).click()
    const panel = page.getByRole('dialog', { name: 'Last Name' })
    await panel.getByRole('radio', { name: choice }).check()
    await panel.getByRole('button', { name: 'Save', exact: true }).click()
    await expect(panel).toHaveCount(0, { timeout: 15000 })
  }
  try {
    await choose(/^Starts with/)
    await expect(match).toHaveText('Starts with, any case')
    await choose(originalMatch.startsWith('Contains') ? /^Contains/ : originalMatch.startsWith('Starts') ? /^Starts with/ : /^Exact/)
    await expect(match).toHaveText(originalMatch)

    // Drag the last field above the first one.
    const labels = () => table.locator('tbody tr td.lead strong').allInnerTexts()
    const start = await labels()
    const moved = start[start.length - 1]
    const handle = table.getByRole('button', { name: new RegExp(`^Move ${moved}\\.`) })
    const from = (await handle.boundingBox())!
    const to = (await table.locator('tbody tr').first().boundingBox())!
    await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2)
    await page.mouse.down()
    await page.mouse.move(from.x + from.width / 2, to.y + 4, { steps: 12 })
    await page.mouse.up()
    await expect(page.getByRole('status').filter({ hasText: 'Field order saved' }).last()).toBeVisible({ timeout: 15000 })
    const order = [moved, ...start.slice(0, -1)]
    expect(await labels()).toEqual(order)

    // Record title: Last Name + First Name.
    await page.getByLabel('Record title, first field').selectOption({ label: 'Last Name' })
    await page.getByLabel('Record title, second field').selectOption({ label: 'First Name' })
    await page.locator('.sf-title').getByRole('button', { name: 'Save', exact: true }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Record title saved' }).last()).toBeVisible({ timeout: 15000 })
    await capture(page, testInfo, 'search-fields-changed')

    // A business user sees the new order in Filters and the new title on Records, the viewer and Home.
    await selectRole(page, 'Business User')
    await areaTab(page, 'Records').click()
    await waitForBusinessSearchReady(page)
    await page.getByRole('button', { name: /^Filters/ }).click()
    const filterLabels = await page.locator('.ws-filter-fields .v-field > span').allInnerTexts()
    const searchable = order.filter((label) => filterLabels.some((t) => t.startsWith(label)))
    expect(filterLabels.map((t) => searchable.find((label) => t.startsWith(label)))).toEqual(searchable)
    expect(searchable[0]).toBe(moved)
    await page.locator('.ws-filter-fields input').first().press('Escape')
    await expect(page.getByRole('dialog', { name: 'Filters' })).toHaveCount(0)

    await openViewerFor(page, titleRecord)
    await expect(viewer(page)).toContainText('Patel Ava')
    await page.getByRole('button', { name: 'Back to results' }).click()
    const inspector = page.getByRole('complementary', { name: 'Record inspector' }).or(page.getByRole('dialog').filter({ hasText: titleRecord }))
    await expect(inspector.getByRole('heading', { name: 'Patel Ava' })).toBeVisible()
    await capture(page, testInfo, 'records-record-title')
    const drawer = page.getByRole('dialog').filter({ hasText: titleRecord })
    if (await drawer.isVisible().catch(() => false)) await drawer.getByRole('button', { name: 'Close', exact: true }).click()
    await areaTab(page, 'Home').click()
    const recent = page.getByRole('region', { name: 'Recently viewed' }).getByRole('listitem').first()
    await expect(recent).toContainText(titleRecord, { timeout: 15000 })
    await expect(recent).toContainText('Patel Ava')
    await capture(page, testInfo, 'home-record-title')
  } finally {
    await restore()
  }
  // Back to the default title: the first preview field, A to Z (First Name).
  await page.reload()
  await expect(page.getByRole('region', { name: 'Recently viewed' }).getByRole('listitem').first()).toContainText(/Ava/, { timeout: 15000 })
}

// ---- Tenant IT: Integrations and Storage (task 0010) ----

const IT_HEADERS = { 'X-SDV-Tenant-Id': '11111111-1111-1111-1111-111111111111', 'X-SDV-Subject': 'local-tenant-it', 'X-SDV-Roles': 'TENANT_IT_ADMIN' }
/** Saving a new sending system or volume adds rows that can't be deleted, so it only runs with SDV_ADMIN_CREATE_REGRESSION=1. */
const createRegression = process.env.SDV_ADMIN_CREATE_REGRESSION === '1'
/** Test folders live under the repository's .runtime folder only (the tour runs from frontend/sdv-ui). */
const tourRuntime = path.resolve(process.cwd(), '..', '..', '.runtime', 'ui-checks', '0010')
const stamp = () => new Date().toISOString().replace(/\D/g, '').slice(0, 14)
const tenantTab = (page: Page, name: string) => page.getByRole('tablist', { name: 'Tenant IT sections' }).getByRole('tab', { name: new RegExp(`^${name}( needs attention)?$`) })
const visible = (page: Page, text: string | RegExp) => page.getByText(text).filter({ visible: true })

/** Integrations: status lines, detail in words, pause asks first, and the three-step add. */
async function integrationsChecks(page: Page, testInfo: TestInfo) {
  await tenantTab(page, 'Integrations').click()
  const list = page.getByRole('region', { name: 'Sending systems' })
  const item = list.getByRole('button', { name: /LexaVault/ })
  await expect(item).toContainText(/Working · last delivery|Waiting for the first delivery/, { timeout: 15000 })
  const detail = page.getByRole('region', { name: 'LexaVault' })
  await expect(detail).toContainText('Sends documents to Vero through the API.')
  await expect(detail).toContainText(/\d+ deliver(y|ies), \d+ documents?\./)
  const sends = page.getByRole('table', { name: 'What it sends' })
  await expect(sends).toContainText('Text, digits only')
  await expect(sends).toContainText('Yes · exact match')
  await expect(visible(page, /Not exposed|OAUTH2_CLIENT_CREDENTIALS|DIGITS_ONLY|Monitoring/)).toHaveCount(0)
  await expect(page.getByText('Vero stores only where the secret is kept, never the secret.')).toBeVisible()
  await expect(visible(page, "Vero's standard limit")).toHaveCount(4)
  await capture(page, testInfo, 'integrations')

  // Pause asks first, then shows "Paused since…"; start again afterwards (and always put the status back).
  const sources = await (await page.request.get('/api/v1/admin/ingestion-sources', { headers: IT_HEADERS })).json() as { sourceId: string; displayName: string; status: string; authType: string; credentialReference: string; limits: unknown }[]
  const lexa = sources.find((s) => s.displayName === 'LexaVault')!
  try {
    await detail.getByRole('button', { name: 'Pause receiving' }).click()
    const ask = page.getByRole('alertdialog', { name: 'Pause receiving from LexaVault?' })
    await expect(ask).toContainText('While paused, Vero refuses deliveries from this system. It will need to send them again.')
    await capture(page, testInfo, 'integrations-pause-asks')
    await ask.getByRole('button', { name: 'Pause receiving' }).click()
    await expect(item).toContainText(/Paused since \d+ \w+ \d{4}/, { timeout: 15000 })
    await detail.getByRole('button', { name: 'Start receiving' }).click()
    await expect(item).toContainText(/Working/, { timeout: 15000 })
  } finally {
    const now = await (await page.request.get('/api/v1/admin/ingestion-sources', { headers: IT_HEADERS })).json() as { sourceId: string; status: string }[]
    if (lexa.status === 'ACTIVE' && now.find((s) => s.sourceId === lexa.sourceId)?.status !== 'ACTIVE') {
      const r = await page.request.put(`/api/v1/admin/ingestion-sources/${lexa.sourceId}`, { headers: { ...IT_HEADERS, 'Content-Type': 'application/json' }, data: { displayName: lexa.displayName, status: 'ACTIVE', authType: lexa.authType, credentialReference: lexa.credentialReference, limits: lexa.limits } })
      expect(r.ok(), 'starting LexaVault again').toBeTruthy()
    }
  }

  // Three-step add with two fields.
  const id = stamp()
  await list.getByRole('button', { name: 'Add', exact: true }).click()
  const editor = page.getByRole('dialog', { name: 'Add a sending system' })
  await editor.getByLabel('Name', { exact: true }).fill(`Tour test system ${id}`)
  await editor.getByLabel('Source key').fill(`tour-${id}`)
  await expect(editor).toContainText('It can’t change later.')
  await capture(page, testInfo, 'integrations-add-1')
  await editor.getByRole('button', { name: 'Next' }).click()
  for (const [label, key, kind] of [['Policy number', 'policyNumber', 'Text, digits only'], ['Holder name', 'holderName', 'Text, spaces trimmed']]) {
    await editor.getByRole('button', { name: 'Add a field' }).click()
    const field = editor.locator('fieldset.int-field').last()
    await field.getByLabel('Label').fill(label)
    await field.getByLabel('Key it sends').fill(key)
    await field.getByLabel('Kind of value').selectOption({ label: kind })
  }
  await editor.locator('fieldset.int-field').first().getByLabel('Required').check()
  await expect(editor).toContainText('Changing a field later affects new deliveries only.')
  await capture(page, testInfo, 'integrations-add-2')
  await editor.getByRole('button', { name: 'Next' }).click()
  await editor.getByRole('radio', { name: /^Signed token/ }).check()
  await editor.getByLabel('Where the secret is kept').fill('vault/tour-test-client')
  await capture(page, testInfo, 'integrations-add-3')
  await expect(editor.getByRole('button', { name: 'Save as paused' })).toBeEnabled()
  if (!createRegression) {
    await editor.getByRole('button', { name: 'Close' }).click()
    return
  }
  await editor.getByRole('button', { name: 'Save as paused' }).click()
  await expect(editor).toHaveCount(0, { timeout: 15000 })
  const added = list.getByRole('button', { name: new RegExp(`Tour test system ${id}`) })
  await expect(added).toContainText(/Paused since/)
  await expect(page.getByRole('table', { name: 'What it sends' }).locator('tbody tr')).toHaveCount(2, { timeout: 15000 })
  await capture(page, testInfo, 'integrations-added-paused')
}

/** Storage: roles in words, banner wording, and the add steps with an unwritable and a writable folder. */
async function storageChecks(page: Page, testInfo: TestInfo) {
  await tenantTab(page, 'Storage').click()
  const demo = page.getByRole('article', { name: 'Local demo volume' })
  await expect(demo).toContainText('In use, new documents go here', { timeout: 15000 })
  await expect(demo).toContainText(/\d+% full · .+ free/)
  await expect(demo).toContainText(/Warns at \d+% · stops at \d+%/)
  await expect(visible(page, /^ACTIVE$|^STANDBY$|Priority/)).toHaveCount(0)
  await capture(page, testInfo, 'storage')

  // Banner wording, with the server's answer changed in the browser only: 82% full, with and without a standby volume.
  for (const withStandby of [true, false]) {
    await page.route('**/api/v1/admin/storage-volumes', async (route) => {
      if (route.request().method() !== 'GET') return route.continue()
      const real = await (await route.fetch()).json() as { volume: Record<string, unknown>; capacity: Record<string, unknown> | null; warningThresholdReached: boolean }[]
      const first = { ...real[0], capacity: { ...real[0].capacity, usedPercent: 82 }, warningThresholdReached: true }
      const standby = { ...real[0], volume: { ...real[0].volume, volumeId: '00000000-0000-0000-0000-0000000000b2', displayName: 'primary-b', priority: Number(real[0].volume.priority) + 1 }, warningThresholdReached: false }
      await route.fulfill({ json: withStandby ? [first, standby] : [first] })
    })
    await tenantTab(page, 'Integrations').click()
    await tenantTab(page, 'Storage').click()
    const banner = page.getByRole('alert').filter({ hasText: 'Local demo volume is 82% full.' })
    await expect(banner).toBeVisible({ timeout: 15000 })
    await expect(banner).toHaveText(withStandby
      ? 'Local demo volume is 82% full. At 95% it stops accepting documents and Vero switches to primary-b. Add space or a new volume before then.'
      : 'Local demo volume is 82% full. At 95% Vero stops accepting new documents. Add space or a new volume before then.')
    if (withStandby) await expect(page.getByRole('article', { name: 'primary-b' })).toContainText('Standby, used when Local demo volume fills up')
    await capture(page, testInfo, withStandby ? 'storage-banner-standby' : 'storage-banner-no-standby')
    await page.unroute('**/api/v1/admin/storage-volumes')
  }
  await tenantTab(page, 'Integrations').click()
  await tenantTab(page, 'Storage').click()

  // Add a volume: an unwritable folder (a path through a file) is refused plainly; a writable one shows free space.
  fs.mkdirSync(tourRuntime, { recursive: true })
  const notAFolder = path.join(tourRuntime, 'not-a-folder.txt')
  fs.writeFileSync(notAFolder, 'Used by the screen tour as a folder Vero cannot write in.\n')
  const writable = path.join(tourRuntime, `volume-${stamp()}`)
  await page.getByRole('button', { name: 'Add a volume' }).click()
  const add = page.getByRole('dialog', { name: 'Add a volume' })
  await add.getByLabel('Name', { exact: true }).fill('tour-volume')
  await add.getByRole('textbox', { name: /^Folder/ }).fill(path.join(notAFolder, 'volume'))
  await expect(add).toContainText('The folder cannot change later.')
  await capture(page, testInfo, 'storage-add-1')
  await add.getByRole('button', { name: 'Next' }).click()
  await expect(add.getByRole('alert')).toContainText('Vero can’t use this folder', { timeout: 15000 })
  await expect(add.getByRole('alert')).toContainText('Part of this path is a file, not a folder.')
  await expect(add.getByRole('button', { name: 'Next' })).toBeDisabled()
  await expect(add.getByText(/I have verified/)).toHaveCount(0)
  await capture(page, testInfo, 'storage-add-2-refused')
  await add.getByRole('button', { name: 'Back' }).click()
  await add.getByRole('textbox', { name: /^Folder/ }).fill(writable)
  await add.getByRole('button', { name: 'Next' }).click()
  await expect(add.getByRole('status').filter({ hasText: 'Vero can write here' })).toContainText(/free/, { timeout: 15000 })
  expect(fs.existsSync(writable), 'the check leaves nothing behind').toBeFalsy()
  await capture(page, testInfo, 'storage-add-2-writable')
  await add.getByRole('button', { name: 'Next' }).click()
  await expect(add.getByRole('radio', { name: /^Standby/ })).toBeVisible()
  await expect(add.getByRole('radio', { name: /^In use/ })).toBeVisible()
  await capture(page, testInfo, 'storage-add-3')
  if (!createRegression) {
    await add.getByRole('button', { name: 'Close' }).click()
    return
  }
  await add.getByRole('radio', { name: /^Standby/ }).check()
  await add.getByRole('button', { name: 'Add volume' }).click()
  await expect(add).toHaveCount(0, { timeout: 15000 })
  const created = page.getByRole('article', { name: 'tour-volume' }).last()
  await expect(created).toContainText('Standby, used when Local demo volume fills up')
  // Leave the test volume offline so it never receives documents.
  await created.getByRole('button', { name: 'Edit' }).click()
  const edit = page.getByRole('dialog', { name: 'Edit tour-volume' })
  await edit.getByLabel('Takes new documents').selectOption('OFFLINE')
  await edit.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByRole('article', { name: 'tour-volume' }).last()).toContainText('Offline', { timeout: 15000 })
}

// ---- Problems and Audit (task 0011) ----

/** Seeded by Seed-SDV-Demo-Records.ps1 -ProblemDemo (could not store, stuck) and earlier (missing documents). */
const problemDemo = { store: '300000000094', stuck: '300000000095', missing: '300000000096' }
const OPS_HEADERS = { 'X-SDV-Tenant-Id': '11111111-1111-1111-1111-111111111111', 'X-SDV-Subject': 'local-platform-operations', 'X-SDV-Roles': 'PLATFORM_OPERATIONS_ADMIN' }

/** Problems: each filter, a detail with its three blocks and the note to copy; read-only for both roles. */
async function problemsChecks(page: Page, testInfo: TestInfo, area: 'Platform operations' | 'Tenant IT') {
  await page.getByRole('tablist', { name: `${area} sections` }).getByRole('tab', { name: /^Problems/ }).click()
  const list = page.getByRole('table', { name: 'Problems list' })
  const chip = (label: string) => page.getByRole('group', { name: 'Show' }).getByRole('button', { name: new RegExp(`^${label} \\d+$`) })
  if (!(await list.waitFor({ timeout: 15000 }).then(() => true, () => false))) return missingDemoData(testInfo, 'no problems listed. Run .\\Seed-SDV-Demo-Records.ps1 -SourceDisplayName LexaVault -ProblemDemo')
  await expect(visible(page, /producer API|retry finali[sz]ation|Retry/)).toHaveCount(0)
  const expectations: [string, string, RegExp][] = [
    ['Could not store', problemDemo.store, /Vero couldn’t finish storing the file/],
    ['Stuck', problemDemo.stuck, /Still being stored after \d+ (day|hour)s?/],
    ['Missing documents', problemDemo.missing, /1 of 2 received/],
  ]
  for (const [label, record, what] of expectations) {
    await chip(label).click()
    const row = list.locator('tbody tr').filter({ hasText: record })
    if (!(await row.first().waitFor({ timeout: 10000 }).then(() => true, () => false))) { missingDemoData(testInfo, `no "${label}" problem for ${record}`); continue }
    await expect(row.first()).toContainText(what)
    await expect(row.first()).toContainText('LexaVault')
    await capture(page, testInfo, `problems-${label.toLowerCase().replace(/\s+/g, '-')}`)
  }
  await chip('All').click()
  await expect(page.getByRole('button', { name: /Retry|Fix|Resolve/ })).toHaveCount(0)

  const storeRow = list.locator('tbody tr').filter({ hasText: problemDemo.store }).first()
  if (await storeRow.isVisible().catch(() => false)) {
    await storeRow.click()
    const panel = page.getByRole('dialog').filter({ hasText: 'What happened' })
    for (const heading of ['What happened', 'What it means', 'What you can do']) await expect(panel.getByRole('heading', { name: heading })).toBeVisible()
    await expect(panel).toContainText(/Vero received appraisal\.pdf from LexaVault on \d+ \w+ and tried 3 times\./)
    await expect(panel).toContainText('there is nothing to retry here')
    const note = panel.getByLabel('Note to copy')
    await expect(note).toContainText(`Record: Contract Number ${problemDemo.store}`)
    await expect(note).toContainText('File: appraisal.pdf')
    await expect(note).not.toContainText('Olivia')
    await expect(visible(page, 'DOCUMENT_FINALIZATION_FAILED')).toHaveCount(0)
    if (isDesktop(testInfo)) {
      await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
      await panel.getByRole('button', { name: 'Copy note' }).click()
      await expect(panel.getByRole('status').filter({ hasText: 'Copied' })).toBeVisible()
      expect(await page.evaluate(() => navigator.clipboard.readText())).toContain(`Contract Number ${problemDemo.store}`)
    }
    await capture(page, testInfo, 'problems-detail')
    await panel.getByRole('button', { name: 'Close' }).click()
  }
  if (area === 'Platform operations') {
    await page.evaluate(() => { window.location.hash = '#/operations/incidents' })
    await expect(page.getByRole('tablist', { name: 'Platform operations sections' }).getByRole('tab', { name: /^Problems/ })).toHaveAttribute('aria-selected', 'true')
  }
}

/** Audit: sentences for open, download, refused, settings change and delivery; the record-number filter; codes folded. */
async function auditChecks(page: Page, testInfo: TestInfo) {
  // A download to read about: the steward downloads one document of the title record through the API.
  const found = await (await page.request.post('/api/v1/business/search', { headers: { ...STEWARD_HEADERS, 'Content-Type': 'application/json' }, data: { sourceId: (await (await page.request.get('/api/v1/business/search-schema', { headers: STEWARD_HEADERS })).json() as { sources: { sourceId: string; displayName: string }[] }).sources.find((x) => x.displayName === 'LexaVault')!.sourceId, criteria: { contractNumber: titleRecord }, page: 1, pageSize: 5 } })).json() as { records: { recordRef: string }[] }
  const record = await (await page.request.get(`/api/v1/business/records/${encodeURIComponent(found.records[0].recordRef)}`, { headers: STEWARD_HEADERS })).json() as { documents: { documentId: string }[] }
  expect((await page.request.get(`/api/v1/business/documents/${record.documents[0].documentId}/download`, { headers: STEWARD_HEADERS })).ok()).toBeTruthy()
  // Fresh events to read about: an open, a refusal and a settings change (the setting is saved unchanged).
  expect((await page.request.get(`/api/v1/business/documents/${record.documents[0].documentId}/viewer`, { headers: STEWARD_HEADERS })).ok()).toBeTruthy()
  expect((await page.request.get('/api/v1/admin/role-capabilities', { headers: { ...STEWARD_HEADERS } })).status()).toBe(403)
  const sourceId = (await (await page.request.get('/api/v1/admin/metadata-sources', { headers: ADMIN_HEADERS })).json() as { sourceId: string; displayName: string }[]).find((x) => x.displayName === 'LexaVault')!.sourceId
  const title = await (await page.request.get(`/api/v1/admin/metadata-sources/${sourceId}/record-title`, { headers: ADMIN_HEADERS })).json() as string[]
  expect((await page.request.put(`/api/v1/admin/metadata-sources/${sourceId}/record-title`, { headers: { ...ADMIN_HEADERS, 'Content-Type': 'application/json' }, data: { fieldKeys: title } })).ok()).toBeTruthy()

  await page.getByRole('tablist', { name: 'Platform operations sections' }).getByRole('tab', { name: 'Audit' }).click()
  const list = page.getByRole('table', { name: 'Audit list' })
  await expect(list).toBeVisible({ timeout: 15000 })
  const activity = page.getByLabel('Activity')
  const sentences: [string, RegExp][] = [
    ['Opening and reading', /opened .+\.pdf/],
    ['Downloads and prints', /downloaded .+\.pdf/],
    ['Refused', /tried to .+ without permission/],
    ['Settings changes', /changed /],
    ['Deliveries', /delivered \d+ documents?|sent .+\.pdf/],
  ]
  for (const [group, sentence] of sentences) {
    await activity.selectOption({ label: group })
    // Deliveries of the seeded record with missing documents, found by its number.
    await page.getByLabel('Record number').fill(group === 'Deliveries' ? problemDemo.missing : '')
    await page.getByRole('button', { name: 'Show', exact: true }).click()
    let row = list.locator('tbody tr').filter({ hasText: sentence }).first()
    // Groups are applied to the loaded pages: look further back until one shows.
    for (let i = 0; i < 6 && !(await row.isVisible().catch(() => false)); i++) {
      const older = page.getByRole('button', { name: 'Load older events' })
      if (!(await older.isVisible().catch(() => false))) break
      await older.click(); await page.waitForTimeout(500)
      row = list.locator('tbody tr').filter({ hasText: sentence }).first()
    }
    await expect(row, `a "${group}" sentence`).toBeVisible({ timeout: 15000 })
    if (group === 'Refused') await expect(row).toContainText('Refused')
    if (group === 'Downloads and prints') await expect(row).toContainText(/\d{12}/)
    await capture(page, testInfo, `audit-${group.toLowerCase().replace(/[^a-z]+/g, '-')}`)
  }
  await expect(visible(page, /^DOCUMENT_[A-Z_]+$|^AUTHORIZATION_DENIED$/)).toHaveCount(0)

  // Record-number filter: every row is about that record.
  await activity.selectOption({ label: 'All activity' })
  await page.getByLabel('Record number').fill(titleRecord)
  await page.getByRole('button', { name: 'Show', exact: true }).click()
  await expect(list.locator('tbody tr').first()).toContainText(titleRecord, { timeout: 15000 })
  const records = await list.locator('td[data-label="Record"]').allInnerTexts()
  expect(records.every((r) => r.trim() === titleRecord), `only ${titleRecord}: ${records.join(', ')}`).toBeTruthy()

  // Detail: the sentence first, codes under Technical details.
  await list.locator('tbody tr').first().click()
  const detail = page.getByRole('dialog').filter({ hasText: 'Technical details' })
  await expect(detail.locator('details')).not.toHaveAttribute('open', '')
  await capture(page, testInfo, 'audit-detail')
  await detail.getByRole('button', { name: 'Close' }).click()
  expect((await page.request.get(`/api/v1/operations/audit-events?limit=5&recordNumber=${titleRecord}`, { headers: OPS_HEADERS })).ok()).toBeTruthy()
}

async function captureAccountMenu(page: Page, testInfo: TestInfo) {
  const menu = await openAccountMenu(page)
  await expect(menu.getByRole('button', { name: 'System', exact: true })).toBeVisible()
  await capture(page, testInfo, 'account-menu')
  await page.keyboard.press('Escape')
  await expect(menu).toHaveCount(0)
}

async function captureSectionTabs(page: Page, testInfo: TestInfo, sectionsLabel: string, tabs: string[]) {
  const tablist = page.getByRole('tablist', { name: sectionsLabel })
  await expect(tablist).toBeVisible({ timeout: 15000 })
  for (const tab of tabs) {
    // A tab may carry a status dot, which adds ' needs attention' to its accessible name.
    const escaped = tab.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const tabButton = tablist.getByRole('tab', { name: new RegExp(`^${escaped}( needs attention)?$`) })
    await tabButton.click()
    await expect(tabButton).toHaveAttribute('aria-selected', 'true')
    await page.waitForTimeout(400)
    await capture(page, testInfo, tab.toLowerCase().replace(/[^a-z0-9]+/g, '-'))
  }
}

/** A lower-case name part matching the Contains/Starts with field of the seeded demo data: also checks that case is ignored. */
const demoName = process.env.SDV_DEMO_NAME ?? 'pat'
/** The demo record's member number: typed in the box it is read as a contract number, which triggers the suggestion. */
const demoMember = process.env.SDV_DEMO_MEMBER ?? '20000000001'
const allowNoData = process.env.SDV_E2E_ALLOW_NO_DATA === '1'
const SEED_HELP = 'Run .\\Seed-SDV-Demo-Records.ps1 -SourceDisplayName LexaVault (and -FreshTestData -Count 3 for names, with Last Name set to Contains).'

/** Missing demo data fails the test, unless SDV_E2E_ALLOW_NO_DATA=1 skips the record steps on purpose. */
function missingDemoData(testInfo: TestInfo, what: string): void {
  if (allowNoData) { testInfo.annotations.push({ type: 'skipped-no-data', description: what }); return }
  throw new Error(`No demo records: ${what}. ${SEED_HELP} Set SDV_E2E_ALLOW_NO_DATA=1 to skip these steps on purpose.`)
}

const searchBox = (page: Page) => page.getByRole('searchbox', { name: 'Search records' })
const resultRows = (page: Page) => page.getByRole('grid', { name: 'Records' }).locator('tbody tr')
const chip = (page: Page, text: RegExp) => page.getByRole('search').locator('.ws-chip').filter({ hasText: text })
const clearSearch = (page: Page) => page.getByRole('button', { name: 'Clear', exact: true }).click()

async function waitForBusinessSearchReady(page: Page) {
  await expect(searchBox(page)).toBeVisible({ timeout: 15000 })
  await expect(page.getByRole('button', { name: /^Filters/ })).toBeVisible()
}

async function searchFor(page: Page, value: string) {
  await searchBox(page).fill(value)
  await searchBox(page).press('Enter')
  await expect(page.getByRole('region', { name: 'Search results' }).locator('[aria-busy="true"]')).toHaveCount(0, { timeout: 15000 })
}

/** Results appear, or the test fails with the seeding help. */
async function expectRows(page: Page, testInfo: TestInfo, what: string): Promise<boolean> {
  const found = await resultRows(page).first().waitFor({ timeout: 15000 }).then(() => true, () => false)
  if (!found) missingDemoData(testInfo, what)
  return found
}

/** The demo record with missing documents (created for task 0003: 1 of 2 documents received). */
const demoIncomplete = process.env.SDV_DEMO_INCOMPLETE ?? '300000000090'

/** Home: landing, greeting, Needs attention and Show all, and a search started on Home that moves to Records. */
async function homeSteps(page: Page, testInfo: TestInfo) {
  await expectLanding(page, 'Home')
  await expect(page.getByRole('heading', { level: 1, name: /^Good (morning|afternoon|evening)/ })).toBeVisible()
  const attention = page.getByRole('region', { name: 'Needs attention' })
  const attentionRow = attention.getByRole('button', { name: new RegExp(demoIncomplete) })
  if (!(await attentionRow.waitFor({ timeout: 15000 }).then(() => true, () => false))) return missingDemoData(testInfo, `record ${demoIncomplete} with missing documents is not under Needs attention`)
  await expect(attentionRow).toContainText('1 of 2 documents received')
  await capture(page, testInfo, 'home')

  await attention.getByRole('button', { name: /Show all/ }).click()
  await expect(areaTab(page, 'Records')).toHaveAttribute('aria-current', 'page')
  await expect(chip(page, /^Missing documents$/)).toBeVisible()
  await expect(chip(page, /^Received /)).toBeVisible()
  await expect(resultRows(page).filter({ hasText: demoIncomplete })).toBeVisible({ timeout: 15000 })
  await expect(resultRows(page).filter({ hasText: demoIncomplete })).toContainText('1 of 2 received')
  await capture(page, testInfo, 'records-needs-attention')

  await areaTab(page, 'Home').click()
  await searchBox(page).fill(demoRecord)
  await searchBox(page).press('Enter')
  await expect(areaTab(page, 'Records')).toHaveAttribute('aria-current', 'page')
  await expect(chip(page, new RegExp(`Contract Number is ${demoRecord}`))).toBeVisible()
  await expect(resultRows(page).filter({ hasText: demoRecord })).toBeVisible({ timeout: 15000 })
  await capture(page, testInfo, 'records-from-home-search')
  await clearSearch(page)
}

/** After the journey opened the demo record in the viewer: it is first under Recently viewed and opens in Records with its panel. */
async function recentlyViewedSteps(page: Page, testInfo: TestInfo) {
  // On narrower screens the record drawer is open again after leaving the viewer; close it first.
  const drawer = page.getByRole('dialog').filter({ hasText: demoRecord })
  if (await drawer.isVisible().catch(() => false)) await drawer.getByRole('button', { name: 'Close', exact: true }).click()
  await areaTab(page, 'Home').click()
  const recent = page.getByRole('region', { name: 'Recently viewed' })
  await expect(recent.getByRole('listitem').first()).toContainText(demoRecord, { timeout: 15000 })
  await capture(page, testInfo, 'home-recently-viewed')
  await recent.getByRole('button', { name: new RegExp(demoRecord) }).first().click()
  await expect(areaTab(page, 'Records')).toHaveAttribute('aria-current', 'page')
  const panel = page.getByRole('complementary', { name: 'Record inspector' }).or(page.getByRole('dialog').filter({ hasText: demoRecord }))
  await expect(panel).toBeVisible({ timeout: 15000 })
  await capture(page, testInfo, 'records-from-recently-viewed')
}


// ---- Viewer (task 0006) ----

const LOCAL_TENANT = '11111111-1111-1111-1111-111111111111'
const STEWARD_HEADERS = { 'X-SDV-Tenant-Id': LOCAL_TENANT, 'X-SDV-Subject': 'local-document-steward', 'X-SDV-Roles': 'DOCUMENT_STEWARD' }
/** Demo record used for the end-to-end redaction; every run permanently redacts a small area of its first page. */
const redactionRecord = process.env.SDV_REDACTION_DEMO_CONTRACT ?? '100000000003'
const isDesktop = (testInfo: TestInfo) => testInfo.project.name === 'desktop-chromium'
const viewer = (page: Page) => page.getByRole('dialog').filter({ has: page.getByRole('img', { name: /Document page 1/ }) })

async function openViewerFor(page: Page, contract: string) {
  await searchFor(page, contract)
  const row = resultRows(page).filter({ hasText: contract }).first()
  await expect(row).toBeVisible({ timeout: 15000 })
  await row.click()
  const inspector = page.getByRole('complementary', { name: 'Record inspector' }).or(page.getByRole('dialog').filter({ hasText: contract }))
  await inspector.getByRole('button', { name: /Open documents/ }).click()
  await expect(page.getByRole('img', { name: /Document page 1/ })).toBeVisible({ timeout: 20000 })
}

/** The open document's ID, from the Details tab (wide screens). */
async function openDocumentId(page: Page) {
  return (await page.locator('.vw-panel dt', { hasText: 'Document ID' }).locator('xpath=following-sibling::dd[1]').innerText()).trim()
}

/** Make sure the open document has a tag, so a read-only role can show it reads tags. Adds one as the steward if needed. */
async function ensureDemoTag(page: Page) {
  const id = await openDocumentId(page)
  const tags = await (await page.request.get(`/api/v1/business/documents/${id}/tags`, { headers: STEWARD_HEADERS })).json() as unknown[]
  if (tags.length === 0) {
    const created = await page.request.post(`/api/v1/business/documents/${id}/tags`, { headers: { ...STEWARD_HEADERS, 'Content-Type': 'application/json' }, data: { tag: 'e2e-demo' } })
    expect(created.ok()).toBeTruthy()
  }
}

async function viewerChecks(page: Page, testInfo: TestInfo, role: string) {
  const v = viewer(page)
  if (!isDesktop(testInfo)) {
    // Narrower screens: the information panel is a drawer; its actions follow the same rules.
    await v.getByRole('button', { name: 'Document information' }).click()
  }
  // Wide screens show the panel; narrower ones the drawer (the hidden panel copy is ignored).
  const drawerOrPanel = isDesktop(testInfo) ? page.locator('.vw-panel') : page.getByRole('dialog', { name: 'Document information' })
  const info = drawerOrPanel.getByRole('tablist', { name: 'Document information' })
  if (role === 'Business User') {
    // Out of the box a business user has no Download, Print or Redact: hidden, not greyed out.
    for (const name of ['Download', 'Print', 'Redact']) await expect(page.getByRole('button', { name, exact: true })).toHaveCount(0)
    await expect(page.getByText(/not permitted|isn.t assigned/i)).toHaveCount(0)
    if (isDesktop(testInfo)) await ensureDemoTag(page)
    await info.getByRole('tab', { name: /^Comments/ }).click()
    await expect(drawerOrPanel.locator('.v-comment').first()).toBeVisible({ timeout: 15000 })
    await expect(drawerOrPanel.getByLabel('New comment')).toHaveCount(0)
    await info.getByRole('tab', { name: 'Tags', exact: true }).click()
    if (isDesktop(testInfo)) await expect(drawerOrPanel.locator('.v-tagrow .v-badge').first()).toBeVisible({ timeout: 15000 })
    await expect(drawerOrPanel.getByLabel('New tag')).toHaveCount(0)
    await expect(page.getByText(/isn.t assigned/i)).toHaveCount(0)
    await capture(page, testInfo, 'viewer-read-only')
  } else {
    // Document steward: actions carry words.
    for (const name of ['Find', 'Download', 'Print', 'Redact']) {
      const button = v.locator('.vw-bar').first().getByRole('button', { name: new RegExp(`^${name}`) }).or(page.getByRole('dialog', { name: 'Document information' }).getByRole('button', { name, exact: true }))
      await expect(button.first()).toBeVisible()
      await expect(button.first()).toContainText(name)
    }
    await info.getByRole('tab', { name: /^Comments/ }).click()
    await expect(drawerOrPanel.getByLabel('New comment')).toBeVisible()
    await info.getByRole('tab', { name: 'Tags', exact: true }).click()
    await expect(drawerOrPanel.getByLabel('New tag')).toBeVisible()
    // The server refuses (e.g. permissions changed mid-session): plain words, no codes.
    await page.route('**/api/v1/business/documents/*/download', (route) => route.fulfill({ status: 403, json: { message: 'Required capability is not granted: DOWNLOAD_CURRENT_PDF' } }))
    // Wide screens: the header's Download; narrower ones: the drawer's (the drawer covers the header).
    await (isDesktop(testInfo) ? v.locator('.vw-bar').first() : drawerOrPanel).getByRole('button', { name: 'Download', exact: true }).click()
    const toast = page.getByRole('status').filter({ hasText: "You can't do this" })
    await expect(toast).toBeVisible()
    await expect(toast).toContainText("Your role doesn't allow it. Ask your Business admin if you need it.")
    await expect(page.getByText(/In production|DENIED|DOWNLOAD_CURRENT_PDF/)).toHaveCount(0)
    await capture(page, testInfo, 'viewer-refused')
    await page.unroute('**/api/v1/business/documents/*/download')
  }
  if (!isDesktop(testInfo)) await page.getByRole('dialog', { name: 'Document information' }).getByRole('button', { name: 'Close' }).click()

  if (isDesktop(testInfo)) {
    // Real page previews (decoded images), and the left column fold is remembered across a reload.
    const preview = page.locator('.vw-pagethumb img').first()
    await expect(preview).toBeVisible({ timeout: 15000 })
    expect(await preview.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0)
    await page.getByRole('button', { name: 'Hide document list' }).click()
    await expect(page.getByRole('navigation', { name: 'Record documents' })).toHaveCount(0)
    await page.reload()
    await openViewerFor(page, demoRecord)
    await expect(page.getByRole('button', { name: 'Show document list' })).toBeVisible()
    await expect(page.getByRole('navigation', { name: 'Record documents' })).toHaveCount(0)
    await capture(page, testInfo, 'viewer-folded')
    await page.getByRole('button', { name: 'Show document list' }).click()
    await expect(page.getByRole('navigation', { name: 'Record documents' })).toBeVisible()
  }
}

async function tryBusinessRecordJourney(page: Page, testInfo: TestInfo, role: string) {
  await waitForBusinessSearchReady(page)
  const placeholder = (await searchBox(page).getAttribute('placeholder')) ?? ''
  if (!/Contract Number/i.test(placeholder)) return missingDemoData(testInfo, `the search box offers "${placeholder}", no Contract Number field`)

  // 1. Search by contract number: the box routes digits to the contract number field.
  await searchFor(page, demoRecord)
  await expect(chip(page, new RegExp(`Contract Number is ${demoRecord}`))).toBeVisible()
  if (!(await expectRows(page, testInfo, `contract ${demoRecord} not found`))) return
  const recordRow = resultRows(page).filter({ hasText: demoRecord }).first()
  await expect(recordRow).toBeVisible({ timeout: 15000 })
  await capture(page, testInfo, 'results-contract')

  // 2. Suggestion: a member number typed in the box is read as a contract number, finds nothing,
  //    and one click searches it as Member Number instead.
  await clearSearch(page)
  await searchFor(page, demoMember)
  await expect(page.getByRole('heading', { name: `No Contract Number ${demoMember}` })).toBeVisible({ timeout: 15000 })
  await capture(page, testInfo, 'suggestion')
  await page.getByRole('button', { name: 'Search it as Member Number instead' }).click()
  await expect(chip(page, new RegExp(`Member Number is ${demoMember}`))).toBeVisible()
  await expect(resultRows(page).filter({ hasText: demoRecord })).toBeVisible({ timeout: 15000 })

  // 3. Search by name in lower case: the server ignores case for Contains/Starts with ("pat" finds "Patel").
  await clearSearch(page)
  await searchFor(page, demoName)
  await expect(chip(page, new RegExp(`(contains|starts with) ${demoName}$`))).toBeVisible()
  if (!(await expectRows(page, testInfo, `no record whose name contains "${demoName}"`))) return
  const nameRows = await resultRows(page).count()
  await capture(page, testInfo, 'results-name')

  // 4. Add a filter chip from the Filters panel, then remove it with ×.
  const firstRowFirstName = (await resultRows(page).first().locator('td[data-label="First Name"]').innerText().catch(() => '')).trim()
  if (!firstRowFirstName) return missingDemoData(testInfo, 'no First Name column in the name results')
  await page.getByRole('button', { name: /^Filters/ }).click()
  let filters = page.getByRole('dialog', { name: 'Filters' })
  await expect(filters).toBeVisible()
  await capture(page, testInfo, 'filters')
  await filters.getByLabel(/^First Name/).fill(firstRowFirstName.split(/\s/)[0])
  await filters.getByRole('button', { name: 'Show results' }).click()
  const added = chip(page, /^First Name is /)
  await expect(added).toBeVisible()
  await expect(page.getByRole('button', { name: /^Filters/ })).toContainText('2')
  await expect(resultRows(page)).toHaveCount(1)
  await capture(page, testInfo, 'results-filter-chip')
  await added.getByRole('button', { name: /^Remove filter/ }).click()
  await expect(added).toHaveCount(0)
  await expect(resultRows(page)).toHaveCount(nameRows)

  // 5. Date range only, with a quick button: every record received in the last 30 days.
  await clearSearch(page)
  await page.getByRole('button', { name: /^Filters/ }).click()
  filters = page.getByRole('dialog', { name: 'Filters' })
  await filters.getByRole('group', { name: 'Received quick ranges' }).getByRole('button', { name: 'Last 30 days' }).click()
  await filters.getByRole('button', { name: 'Show results' }).click()
  await expect(chip(page, /^Received /)).toBeVisible()
  if (!(await expectRows(page, testInfo, 'nothing received in the last 30 days'))) return
  await capture(page, testInfo, 'results-received-last-30-days')

  // 6. Date range only, typed: Last updated from 7 days ago to today (the person's own calendar days).
  await clearSearch(page)
  const [weekAgo, today] = await page.evaluate(() => {
    const day = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    const now = new Date()
    const before = new Date(now)
    before.setDate(now.getDate() - 7)
    return [day(before), day(now)]
  })
  await page.getByRole('button', { name: /^Filters/ }).click()
  filters = page.getByRole('dialog', { name: 'Filters' })
  const updated = filters.getByRole('group', { name: /^Last updated/ })
  await updated.getByLabel('From').fill(weekAgo)
  await updated.getByLabel('To').fill(today)
  await filters.getByRole('button', { name: 'Show results' }).click()
  await expect(chip(page, /^Updated /)).toBeVisible()
  if (!(await expectRows(page, testInfo, 'nothing updated in the last 7 days'))) return
  await capture(page, testInfo, 'results-updated-range')

  // 7. Open the record panel for the demo contract.
  await clearSearch(page)
  await searchFor(page, demoRecord)
  await recordRow.click()
  // Wide screens show the record in a side panel; narrower ones (e.g. iPad portrait) in a drawer.
  const inspector = page.getByRole('complementary', { name: 'Record inspector' }).or(page.getByRole('dialog').filter({ hasText: demoRecord }))
  await expect(inspector).toBeVisible({ timeout: 15000 })
  await expect(recordRow).toHaveAttribute('aria-selected', 'true')
  await capture(page, testInfo, 'record-inspector')

  await inspector.getByRole('button', { name: /Open documents/ }).click()
  await expect(page.getByRole('img', { name: /Document page 1/ })).toBeVisible({ timeout: 15000 })
  await capture(page, testInfo, 'document-viewer')
  await viewerChecks(page, testInfo, role)

  const activity = page.getByRole('tablist', { name: 'Document information' }).getByRole('tab', { name: 'Activity', exact: true })
  if (await activity.isVisible().catch(() => false)) {
    await activity.click()
    await page.waitForTimeout(250)
    await capture(page, testInfo, 'activity')
  }

  await page.getByRole('button', { name: 'Back to results' }).click()
  await expect(recordRow).toBeVisible()
}

test.describe('SDV role-based screen capture', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('domcontentloaded')
  })

  test('Business User screens', async ({ page }, testInfo) => {
    await selectRole(page, 'Business User')
    await homeSteps(page, testInfo)
    await areaTab(page, 'Records').click()
    await waitForBusinessSearchReady(page)
    await capture(page, testInfo, 'search')
    await captureAccountMenu(page, testInfo)
    await tryBusinessRecordJourney(page, testInfo, 'Business User')
    await recentlyViewedSteps(page, testInfo)
  })

  test('Document Steward redaction end to end', async ({ page }, testInfo) => {
    // Opt-in, like the comment test: every run permanently redacts a small area of a demo document.
    test.skip(process.env.SDV_REDACTION_REGRESSION !== '1', 'Opt-in: set SDV_REDACTION_REGRESSION=1 (permanently redacts demo record 100000000003)')
    test.skip(!isDesktop(testInfo), 'Runs once per tour (desktop only).')
    await selectRole(page, 'Document Steward')
    await areaTab(page, 'Records').click()
    await waitForBusinessSearchReady(page)
    await openViewerFor(page, redactionRecord)
    await page.getByRole('button', { name: 'Redact', exact: true }).click()
    const img = page.getByRole('img', { name: /Document page 1/ })
    const box = await img.boundingBox()
    if (!box) throw new Error('page 1 image has no box')
    await page.mouse.move(box.x + box.width * 0.15, box.y + box.height * 0.08)
    await page.mouse.down()
    await page.mouse.move(box.x + box.width * 0.45, box.y + box.height * 0.14, { steps: 6 })
    await page.mouse.up()
    await expect(page.locator('.vw-redact-bar')).toContainText('1 region marked')
    await page.getByRole('button', { name: 'Review', exact: true }).click()
    const review = page.getByRole('alertdialog', { name: 'Apply permanent redaction' })
    await expect(review).toContainText('Marked areas are removed for every user and the original is not kept.')
    await review.getByLabel(/Corrective reason/).fill('Automated e2e check of permanent redaction on a demo document.')
    await review.getByLabel(/I understand this cannot be undone/).check()
    await capture(page, testInfo, 'redaction-review')
    await review.getByRole('button', { name: 'Apply permanent redaction' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Permanent corrective redaction completed' })).toBeVisible({ timeout: 30000 })
    await expect(page.getByRole('img', { name: /Document page 1/ })).toBeVisible({ timeout: 20000 })
    await capture(page, testInfo, 'redaction-applied')
  })

  test('Home empty states', async ({ page }, testInfo) => {
    // Serve empty lists so the empty states can be seen whatever the local data holds.
    await page.route('**/api/v1/business/me/recent-records**', (route) => route.fulfill({ json: { records: [] } }))
    await page.route('**/api/v1/business/search', async (route) => {
      const body = route.request().postDataJSON() as { sourceId: string; incompleteOnly?: boolean }
      if (body?.incompleteOnly) return route.fulfill({ json: { sourceId: body.sourceId, records: [], page: 1, pageSize: 5, hasMore: false, truncated: false } })
      return route.continue()
    })
    await selectRole(page, 'Business User')
    await expectLanding(page, 'Home')
    await expect(page.getByText('Records you open will show here.')).toBeVisible({ timeout: 15000 })
    await expect(page.getByText('Nothing needs attention.')).toBeVisible({ timeout: 15000 })
    await capture(page, testInfo, 'home-empty')
  })

  test('Document Steward screens', async ({ page }, testInfo) => {
    await selectRole(page, 'Document Steward')
    await homeSteps(page, testInfo)
    await areaTab(page, 'Records').click()
    await waitForBusinessSearchReady(page)
    await capture(page, testInfo, 'search')
    await captureAccountMenu(page, testInfo)
    await tryBusinessRecordJourney(page, testInfo, 'Document Steward')
    await recentlyViewedSteps(page, testInfo)
  })

  test('Business Administrator screens', async ({ page }, testInfo) => {
    // The desktop run also changes and restores the keeping rule, search fields and record title.
    test.setTimeout(180_000)
    await selectRole(page, 'Business Administrator')
    await expectNoRecordsAccess(page, testInfo, 'Business administration')
    await captureAccountMenu(page, testInfo)
    await captureSectionTabs(page, testInfo, 'Business administration sections', [
      'People & roles',
      'Roles & permissions',
      'Keeping documents',
      'Search fields',
    ])
    await businessAdminChecks(page, testInfo)
    // Settings that change shared rules run once, on the desktop project; the tablet project only tours the screens.
    if (isDesktop(testInfo)) {
      await keepingDocumentsChecks(page, testInfo)
      await searchFieldsChecks(page, testInfo)
    }
  })

  test('A business user opening an admin page sees the no-access page', async ({ page }, testInfo) => {
    await selectRole(page, 'Business User')
    await page.evaluate(() => { window.location.hash = '#/governance/roles' })
    await expect(page.getByRole('heading', { name: /This page isn.t part of your role/ })).toBeVisible({ timeout: 15000 })
    await expect(page.getByText('Your role is Business user.')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Go to Home' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible()
    await capture(page, testInfo, 'no-access')
    await page.getByRole('button', { name: 'Go to Home' }).click()
    await expect(areaTab(page, 'Home')).toHaveAttribute('aria-current', 'page')
  })

  test('Tenant IT Administrator screens', async ({ page }, testInfo) => {
    test.setTimeout(150_000)
    await selectRole(page, 'Tenant IT Administrator')
    await expectNoRecordsAccess(page, testInfo, 'Tenant IT')
    await captureAccountMenu(page, testInfo)
    await captureSectionTabs(page, testInfo, 'Tenant IT sections', [
      'Storage',
      'Sign-in',
      'Integrations',
      'Problems',
      'Environment',
    ])
    await expect(page.getByRole('tablist', { name: 'Tenant IT sections' }).getByRole('tab', { name: 'Audit' })).toHaveCount(0)
    await problemsChecks(page, testInfo, 'Tenant IT')
    await integrationsChecks(page, testInfo)
    await storageChecks(page, testInfo)
  })

  test('Local Administrator with several roles sees every area', async ({ page }, testInfo) => {
    await selectRole(page, 'Local Administrator (Tenant IT + Business admin)')
    await expectNoRecordsAccess(page, testInfo, 'Business administration')
    const areas = page.getByRole('banner').getByRole('navigation', { name: 'Areas' })
    await expect(areas.getByRole('button')).toHaveText(['Business administration', 'Tenant IT'])
    await expect(page.getByRole('tablist', { name: 'Business administration sections' })).toBeVisible({ timeout: 15000 })
    await capture(page, testInfo, 'business-administration')
    await areas.getByRole('button', { name: 'Tenant IT', exact: true }).click()
    await expect(areas.getByRole('button', { name: 'Tenant IT', exact: true })).toHaveAttribute('aria-current', 'page')
    await expect(page.getByRole('tablist', { name: 'Tenant IT sections' })).toBeVisible({ timeout: 15000 })
    await capture(page, testInfo, 'tenant-it')
    await captureAccountMenu(page, testInfo)
  })

  test('Platform Operations Administrator screens', async ({ page }, testInfo) => {
    test.setTimeout(150_000)
    await selectRole(page, 'Platform Operations Administrator')
    await expectNoRecordsAccess(page, testInfo, 'Platform operations')
    await captureAccountMenu(page, testInfo)
    await captureSectionTabs(page, testInfo, 'Platform operations sections', [
      'Overview',
      'Problems',
      'Storage',
      'Platform health',
      'Audit',
      'Configuration',
    ])
    await problemsChecks(page, testInfo, 'Platform operations')
    await auditChecks(page, testInfo)
  })
})
