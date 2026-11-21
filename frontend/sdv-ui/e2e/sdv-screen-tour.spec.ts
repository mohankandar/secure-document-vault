import { expect, test, type Page, type TestInfo } from '@playwright/test'
import fs from 'node:fs'
import path from 'node:path'

// Screen tour of the R7 UI (served at /). Needs the app running locally in LOCAL security mode,
// where the account menu offers the development-only "Local test role" switch.

const demoRecord = process.env.SDV_DEMO_CONTRACT ?? '100000000001' // created by Seed-SDV-Demo-Records.ps1

/** The account button shows each local test sign-in's display name (local mode only), never the technical subject. */
const ACCOUNT_BY_ROLE: Record<string, string> = {
  'Business User': 'Account: Maya Okafor, Business user',
  'Document Steward': 'Account: Daniel Reyes, Document steward',
  'Restricted Steward': 'Account: Dana Ruiz, Restricted steward',
  'Business Administrator': 'Account: Priya Natarajan, Business administrator',
  'Tenant IT Administrator': 'Account: Jonas Weber, Tenant IT administrator',
  'Platform Operations Administrator': 'Account: Aiko Tanaka, Platform operations administrator',
  'Local Administrator (Tenant IT + Business admin)': 'Account: Ari Lindqvist, Business administrator, Tenant IT administrator',
}
const AREA_TAB_BY_ROLE: Record<string, string> = {
  'Business User': 'Home',
  'Document Steward': 'Home',
  'Restricted Steward': 'Home',
  'Business Administrator': 'Business administration',
  'Tenant IT Administrator': 'Tenant IT',
  'Platform Operations Administrator': 'Platform operations',
  'Local Administrator (Tenant IT + Business admin)': 'Business administration',
}

const accountButton = (page: Page) => page.getByRole('banner').getByRole('button', { name: /^Account: / })

/**
 * The page has loaded once the app's own code has run. main.tsx loads the app with import(), after the browser's
 * load event, so that event says nothing about the app (task 0023 follow-up: a blank page at the role switch when
 * this machine briefly froze while the code was still arriving). mount() in r7/entry.tsx marks <html> with r7-html.
 */
async function appLoaded(page: Page) {
  await page.waitForFunction(() => document.documentElement.classList.contains('r7-html'), undefined, { timeout: 15000 })
}
async function openApp(page: Page, url: string) {
  await page.goto(url)
  await appLoaded(page)
}
async function reloadApp(page: Page) {
  await page.reload()
  await appLoaded(page)
}
/** Signed in: the app has loaded the session and the top bar shows the account button. */
async function signedIn(page: Page) {
  await appLoaded(page)
  await expect(accountButton(page)).toBeVisible({ timeout: 15000 })
}

async function openAccountMenu(page: Page) {
  const button = accountButton(page)
  await signedIn(page)
  if ((await button.getAttribute('aria-expanded')) !== 'true') await button.click()
  const menu = page.getByRole('dialog', { name: 'Account menu' })
  await expect(menu).toBeVisible()
  return menu
}

async function selectRole(page: Page, role: string) {
  const expected = ACCOUNT_BY_ROLE[role]
  await signedIn(page)
  if ((await accountButton(page).getAttribute('aria-label')) !== expected) {
    const menu = await openAccountMenu(page)
    const roleSelect = menu.getByLabel('Local test role')
    await expect(roleSelect).toBeVisible()
    // Selecting a role reloads the page with the new local test identity.
    await Promise.all([page.waitForEvent('load'), roleSelect.selectOption({ label: role })])
    await signedIn(page)
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
  await openApp(page, '/')
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
  await expect(page.getByRole('combobox', { name: 'Search records' })).toBeHidden()
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
  // Task 0025: Restricted steward is listed too, read-only (Fixed).
  await expect(people.locator('tbody tr')).toHaveCount(5)
  await expect(people.locator('tbody tr').filter({ hasText: 'RESTRICTED_STEWARD' })).toContainText('Fixed')
  await expect(people.locator('tbody')).not.toContainText(/Platform operations/i)
  await expect(page.getByText(/Claim value/)).toHaveCount(0)

  await sections.getByRole('tab', { name: 'Roles & permissions' }).click()
  const print = page.getByRole('switch', { name: 'Print for Business user' })
  await expect(print).toBeVisible({ timeout: 15000 })
  await expect(page.getByText(/Baseline|PRINT_DOCUMENT|PERMANENT_REDACTION/)).toHaveCount(0)
  await expect(page.locator('table.v-table tbody tr').first()).toContainText('Always on')
  const before = await print.getAttribute('aria-checked')
  // Task 0035: the role rules as they were, put back through the API if a step below stops halfway.
  const rolesNow = async () => (await (await page.request.get('/api/v1/admin/role-capabilities', { headers: ADMIN_HEADERS })).json() as { roles: Record<string, Record<string, boolean>> }).roles
  const savedRoles = await rolesNow()
  try {
    for (const _ of [0, 1]) {
      // Change, save, then change back and save again, so the role rules end as they started.
      await print.click()
      await page.getByRole('button', { name: 'Save', exact: true }).click()
      await expect(page.getByRole('status').filter({ hasText: 'Permissions saved' }).last()).toBeVisible({ timeout: 15000 })
    }
    await expect(print).toHaveAttribute('aria-checked', before ?? 'false')
  } finally {
    if (JSON.stringify(await rolesNow()) !== JSON.stringify(savedRoles)) {
      const r = await page.request.put('/api/v1/admin/role-capabilities', { headers: { ...ADMIN_HEADERS, 'Content-Type': 'application/json' }, data: { roles: savedRoles } })
      expect(r.ok(), 'putting the role rules back').toBeTruthy()
    }
  }

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

/** Saving a keeping rule adds permanent rows to Earlier rules, so that step only runs with SDV_KEEPING_RULE_REGRESSION=1. */
const keepingRegression = process.env.SDV_KEEPING_RULE_REGRESSION === '1'

/** The rule in use reads in words and the New rule panel explains itself. Opt-in: a new rule in years saves and is listed under Earlier rules; the earlier rule is then put back. */
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
  // Saving adds two rows to Earlier rules that can never be removed, so it only runs on request (owner, 2026-11-18).
  if (!keepingRegression) {
    await panel.getByRole('button', { name: 'Cancel' }).click()
    await expect(panel).toHaveCount(0)
    await expect(current).toHaveText(before)
    return
  }
  await panel.getByRole('button', { name: 'Start using this rule' }).click()
  await expect(page.getByRole('status').filter({ hasText: 'New rule in use' }).last()).toBeVisible({ timeout: 15000 })
  await expect(current).toHaveText('Keep for 7 years')
  const earlier = page.locator('table.v-table').filter({ hasText: 'Set by' })
  await expect(earlier.locator('tbody tr').first()).toContainText('Keep for 7 years')
  await expect(earlier.locator('tbody tr').first()).toContainText('In use')
  await expect(earlier.locator('tbody tr').first()).toContainText('e2e tour: seven years')
  // The person's name is kept with each new rule (task 0019 follow-up); never the sign-in ID.
  await expect(earlier.locator('tbody tr').first()).toContainText('Priya Natarajan')
  await expect(page.getByRole('region', { name: 'Rule in use' })).toContainText('Set by Priya Natarajan')
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
  type FieldDefinition = { fieldKey: string; displayLabel: string; fieldType: string; required: boolean; businessIdentifier: boolean; searchable: boolean; normalization: string; searchMode: string; validationRulesJson: string; active: boolean }
  const readFields = async () => (await (await page.request.get(`/api/v1/admin/ingestion-sources/${sourceId}/metadata-fields`, { headers: ADMIN_HEADERS })).json() as FieldDefinition[]).filter((f) => f.active)
  const fields = await readFields()
  const savedLastName = fields.find((f) => f.fieldKey === 'lastName')
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
    // Task 0035: Last Name's match too, if a step stopped between changing it and changing it back.
    const now = (await readFields()).find((f) => f.fieldKey === 'lastName')
    if (savedLastName && now && (now.searchMode !== savedLastName.searchMode || now.normalization !== savedLastName.normalization)) {
      const s = savedLastName
      const r = await page.request.put(`/api/v1/admin/ingestion-sources/${sourceId}/metadata-fields/lastName`, { headers: { ...ADMIN_HEADERS, 'Content-Type': 'application/json' }, data: {
        displayLabel: s.displayLabel, fieldType: s.fieldType, required: s.required, businessIdentifier: s.businessIdentifier, searchable: s.searchable,
        normalization: s.normalization, searchMode: s.searchMode, validationRules: JSON.parse(s.validationRulesJson || '{}') } })
      expect(r.ok(), 'restoring how Last Name matches').toBeTruthy()
    }
  }

  await sectionTab(page, 'Search fields').click()
  const table = page.getByRole('table', { name: 'Search fields' })
  await expect(table).toBeVisible({ timeout: 15000 })
  await expect(page.getByRole('button', { name: /Reorder/ })).toHaveCount(0)
  await expect(page.getByText(/current UI|Field key|DIGITS_ONLY|PARTIAL|PREFIX/)).toHaveCount(0)
  await expect(page.getByText(/^Records per page: \d+ \(most 50\)$/)).toBeVisible()
  // Task 0020 (item 5): the sending-system picker is wide enough for every name (no name cut off).
  const picker = page.getByLabel('Sending system')
  if (await picker.count()) {
    const fits = await picker.evaluate((el: HTMLSelectElement) => {
      const probe = document.createElement('span')
      probe.style.cssText = `position:absolute;visibility:hidden;white-space:nowrap;font:${getComputedStyle(el).font}`
      document.body.append(probe)
      const widest = Math.max(...[...el.options].map((o) => { probe.textContent = o.text; return probe.offsetWidth }))
      probe.remove()
      const cs = getComputedStyle(el)
      const room = el.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight)
      return room >= widest || el.clientWidth >= (el.closest('.v-page, main')?.clientWidth ?? Infinity) - 64
    })
    expect(fits, 'the sending-system picker shows the full name').toBeTruthy()
  }
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
    // Task 0035: the panel opens only once the search fields have loaded, and the labels were read at once (report
    // 0033: an empty list, so searchable[0] was undefined). Wait for the panel to list the moved field first.
    await expect(page.getByRole('dialog', { name: 'Filters' })).toBeVisible({ timeout: 15000 })
    const fieldLabel = page.locator('.ws-filter-fields .v-field > span')
    await expect(fieldLabel.first(), 'the Filters panel lists the moved field first').toHaveText(new RegExp(`^${moved.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`), { timeout: 15000 })
    const filterLabels = await fieldLabel.allInnerTexts()
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
  await reloadApp(page)
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
  // Limits are numbers (task 0019 follow-up): the system's own, never above the standard the server sends.
  const standard = await (await page.request.get('/api/v1/admin/ingestion-sources/standard-limits', { headers: IT_HEADERS })).json() as Record<string, number>
  const lexaLimits = ((await (await page.request.get('/api/v1/admin/ingestion-sources', { headers: IT_HEADERS })).json()) as { displayName: string; limits?: Record<string, number | null> | null }[])
    .find((s) => s.displayName === 'LexaVault')?.limits ?? {}
  const inUse = (key: string) => Math.min(lexaLimits[key] ?? standard[key], standard[key])
  expect(standard.maxPagesPerPdf, 'standard limits from the server').toBeGreaterThan(0)
  await expect(visible(page, "Vero's standard limit")).toHaveCount(0)
  const limitsCard = detail.locator('.int-kv')
  await expect(limitsCard).toContainText(`Most pages in one PDF${inUse('maxPagesPerPdf').toLocaleString('en-US')} pages`)
  await expect(limitsCard).toContainText(`Most documents${inUse('maxDocumentsPerSubmission').toLocaleString('en-US')} documents`)
  await expect(limitsCard).toContainText(`Wait for missing documents${inUse('incompleteAttentionHours').toLocaleString('en-US')} hours`)
  await expect(limitsCard).toContainText(/Largest PDF[\d,.]+ MB/)
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
  // Task 0020 (item 3): on a wide screen the panel lies over the page; the detail card behind it keeps its width.
  const sendsTable = page.getByRole('table', { name: 'What it sends' })
  const wideScreen = (page.viewportSize()?.width ?? 0) >= 1024
  const before = wideScreen ? await sendsTable.boundingBox() : null
  await list.getByRole('button', { name: 'Add', exact: true }).click()
  const editor = page.getByRole('dialog').filter({ hasText: 'Add a sending system' })
  if (before) {
    await expect(editor).toBeVisible()
    const after = await sendsTable.boundingBox()
    expect(after?.width, 'the detail table keeps its width behind the panel').toBeCloseTo(before.width, 0)
    expect(after?.x).toBeCloseTo(before.x, 0)
  }
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
  const demo = page.getByRole('row', { name: 'primary-a' })
  await expect(demo).toContainText('In use, new documents go here', { timeout: 15000 })
  await expect(demo).toContainText(/\d+%/)
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
    const banner = page.getByRole('alert').filter({ hasText: 'primary-a is 82% full.' })
    await expect(banner).toBeVisible({ timeout: 15000 })
    await expect(banner).toHaveText(withStandby
      ? 'primary-a is 82% full. At 95% it stops accepting documents and Vero switches to primary-b. Add space or a new volume before then.'
      : 'primary-a is 82% full. At 95% Vero stops accepting new documents. Add space or a new volume before then.')
    if (withStandby) await expect(page.getByRole('row', { name: 'primary-b' })).toContainText('Standby, used when primary-a fills up')
    await capture(page, testInfo, withStandby ? 'storage-banner-standby' : 'storage-banner-no-standby')
    await page.unroute('**/api/v1/admin/storage-volumes')
  }

  // Task 0020 (item 4): a volume Tenant IT set offline, which Vero doesn't measure, reads as Platform operations sees it, with no warning tone.
  await page.route('**/api/v1/admin/storage-volumes', async (route) => {
    if (route.request().method() !== 'GET') return route.continue()
    const real = await (await route.fetch()).json() as { volume: Record<string, unknown> }[]
    const offline = { ...real[0], volume: { ...real[0].volume, volumeId: '00000000-0000-0000-0000-0000000000c3', displayName: 'offline-x', state: 'OFFLINE' },
      capacity: null, observationError: null, warningThresholdReached: false, stopWriteThresholdReached: false }
    await route.fulfill({ json: [real[0], offline] })
  })
  await tenantTab(page, 'Integrations').click()
  await tenantTab(page, 'Storage').click()
  const offlineRow = page.getByRole('row', { name: 'offline-x' })
  await expect(offlineRow.locator('td[data-label="How full"]')).toHaveText('Offline (set by Tenant IT)', { timeout: 15000 })
  await expect(offlineRow).not.toContainText('can’t be read')
  await expect(offlineRow.locator('.v-dot')).toHaveClass(/^v-dot\s*$/)
  await expect(page.getByRole('alert').filter({ hasText: 'offline-x' })).toHaveCount(0)
  await capture(page, testInfo, 'storage-offline-on-purpose')
  await page.unroute('**/api/v1/admin/storage-volumes')
  await tenantTab(page, 'Integrations').click()
  await tenantTab(page, 'Storage').click()

  // Add a volume: an unwritable folder (a path through a file) is refused plainly; a writable one shows free space.
  fs.mkdirSync(tourRuntime, { recursive: true })
  const notAFolder = path.join(tourRuntime, 'not-a-folder.txt')
  fs.writeFileSync(notAFolder, 'Used by the screen tour as a folder Vero cannot write in.\n')
  const writable = path.join(tourRuntime, `volume-${stamp()}`)
  await page.getByRole('button', { name: 'Add volume' }).click()
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
  const created = page.getByRole('row', { name: 'tour-volume' }).last()
  await expect(created).toContainText('Standby, used when primary-a fills up')
  // Leave the test volume offline so it never receives documents.
  await created.getByRole('button', { name: /^Edit/ }).click()
  const edit = page.getByRole('dialog', { name: 'Edit tour-volume' })
  await edit.getByLabel('Takes new documents').selectOption('OFFLINE')
  await edit.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByRole('row', { name: 'tour-volume' }).last()).toContainText('Offline', { timeout: 15000 })
}

// ---- Problems and Audit (task 0011) ----

/** Seeded by Seed-SDV-Demo-Records.ps1 -ProblemDemo (could not store, stuck) and earlier (missing documents). */
const problemDemo = { store: '300000000094', stuck: '300000000095', missing: '300000000096' }
const OPS_HEADERS = { 'X-SDV-Tenant-Id': '11111111-1111-1111-1111-111111111111', 'X-SDV-Subject': 'local-platform-operations', 'X-SDV-Roles': 'PLATFORM_OPERATIONS_ADMIN' }

/** Problems: each filter, a detail with its three blocks and the note to copy; read-only for both roles. */
async function problemsChecks(page: Page, testInfo: TestInfo, area: 'Platform operations' | 'Tenant IT') {
  await page.getByRole('tablist', { name: `${area} sections` }).getByRole('tab', { name: /^Problems/ }).click()
  const list = page.getByRole('table', { name: 'Problems list' })
  const chip = (label: string) => page.getByRole('group', { name: 'Show' }).getByRole('button', { name: new RegExp(`^${label} · \\d+$`) })
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
    // Task 0027 (0.4): admin views never name a file; the document is described instead.
    await expect(panel).toContainText(/Vero received a (\d+-page )?document from LexaVault on \d+ \w+ and tried 3 times\./)
    await expect(panel).not.toContainText('.pdf')
    await expect(panel).toContainText('there is nothing to retry here')
    const note = panel.getByLabel('Note to copy')
    await expect(note).toContainText(`Record: Contract Number ${problemDemo.store}`)
    await expect(note).toContainText(/Document: received \d+ \w+.*Vero document ID [0-9a-f-]{36}/)
    await expect(note).not.toContainText('.pdf')
    await expect(note).not.toContainText('Olivia')
    await expect(visible(page, 'DOCUMENT_FINALIZATION_FAILED')).toHaveCount(0)
    if (isDesktop(testInfo)) {
      await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
      await panel.getByRole('button', { name: /^Copy a note for the/ }).click()
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
    ['Opening and reading', /opened a document/],
    ['Downloads, prints and exports', /downloaded a document/],
    ['Refused', /tried to .+ without permission/],
    ['Settings changes', /changed /],
    ['Deliveries', /delivered \d+ documents?|sent a document/],
  ]
  for (const [group, sentence] of sentences) {
    await activity.selectOption({ label: group })
    // Deliveries of the seeded record with missing documents, found by its number.
    await page.getByLabel('Record number').fill(group === 'Deliveries' ? problemDemo.missing : '')
    await page.getByLabel('Record number').press('Enter')
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
    // The fresh download was made by the local steward, who has a display name in local mode.
    if (group === 'Downloads, prints and exports') { await expect(row).toContainText(/\d{12}/); await expect(list.locator('tbody tr').filter({ hasText: /Daniel Reyes downloaded / }).first()).toBeVisible() }
    await capture(page, testInfo, `audit-${group.toLowerCase().replace(/[^a-z]+/g, '-')}`)
  }
  await expect(visible(page, /^DOCUMENT_[A-Z_]+$|^AUTHORIZATION_DENIED$/)).toHaveCount(0)

  // Task 0020 item 1: the record open made above names its record (looked up from its submission, within the tenant).
  await activity.selectOption({ label: 'Opening and reading' })
  await page.getByLabel('Record number').fill('')
  await page.getByLabel('Record number').press('Enter')
  const opened = list.locator('tbody tr').filter({ hasText: /Daniel Reyes opened a record/ }).first()
  await expect(opened, 'the fresh record open').toBeVisible({ timeout: 15000 })
  await expect(opened.locator('td[data-label="Record"]')).toHaveText(titleRecord)

  // Record-number filter: every row is about that record.
  await activity.selectOption({ label: 'All activity' })
  await page.getByLabel('Record number').fill(titleRecord)
  await page.getByLabel('Record number').press('Enter')
  await expect(list.locator('tbody tr').first()).toContainText(titleRecord, { timeout: 15000 })
  const records = await list.locator('td[data-label="Record"]').allInnerTexts()
  expect(records.every((r) => r.trim() === titleRecord), `only ${titleRecord}: ${records.join(', ')}`).toBeTruthy()
  // Task 0020 item 1 follow-up: the filter also finds record opens (by their submission, within the tenant).
  await activity.selectOption({ label: 'Opening and reading' })
  const openedByFilter = list.locator('tbody tr').filter({ hasText: /Daniel Reyes opened a record/ }).first()
  await expect(openedByFilter, 'the record open, found by the Record number filter').toBeVisible({ timeout: 15000 })
  await expect(openedByFilter.locator('td[data-label="Record"]')).toHaveText(titleRecord)
  const openedRecords = await list.locator('td[data-label="Record"]').allInnerTexts()
  expect(openedRecords.every((r) => r.trim() === titleRecord), `only ${titleRecord}: ${openedRecords.join(', ')}`).toBeTruthy()
  await activity.selectOption({ label: 'All activity' })
  await expect(list.locator('tbody tr').first()).toContainText(titleRecord, { timeout: 15000 })

  // Detail: the sentence first, codes under Technical details.
  await list.locator('tbody tr').first().click()
  const detail = page.getByRole('dialog').filter({ hasText: 'Technical details' })
  await expect(detail.locator('details')).not.toHaveAttribute('open', '')
  await capture(page, testInfo, 'audit-detail')
  await detail.getByRole('button', { name: 'Close' }).click()
  expect((await page.request.get(`/api/v1/operations/audit-events?limit=5&recordNumber=${titleRecord}`, { headers: OPS_HEADERS })).ok()).toBeTruthy()

  // Export: the server's file has the screen's sentences, in the same order, and the export is itself in the audit.
  const onScreen = (await list.locator('td[data-label="What happened"]').allInnerTexts()).map((s) => s.trim())
  const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Export', exact: true }).click()])
  await expect(page.getByRole('status').filter({ hasText: 'The export is recorded in the audit.' })).toBeVisible({ timeout: 15000 })
  const csv = parseCsv(fs.readFileSync(await download.path()).toString('utf8').replace(/^﻿/, ''))
  // Task 0027 (0.4): the document's ID, level, received date and pages instead of a file name.
  expect(csv[0]).toEqual(['When (UTC)', 'What happened', 'Person', 'Record', 'Result', 'Action code', 'Document ID', 'Level', 'Received (UTC)', 'Pages'])
  expect(csv.flat().some((cell) => cell.toLowerCase().endsWith('.pdf')), 'no file name in the export').toBeFalsy()
  const rows = csv.slice(1)
  expect(rows.length, 'exported rows').toBeGreaterThanOrEqual(onScreen.length)
  expect(rows.every((r) => r[3] === titleRecord), 'every exported row is about the filtered record').toBeTruthy()
  expect(rows.slice(0, onScreen.length).map((r) => r[1]), 'exported sentences match the screen').toEqual(onScreen)
  await activity.selectOption({ label: 'Downloads, prints and exports' })
  await page.getByLabel('Record number').fill('')
  await page.getByLabel('Record number').press('Enter')
  await expect(list.locator('tbody tr').first()).toContainText(new RegExp(`exported the audit \\(${rows.length} events?\\)`), { timeout: 15000 })
  await capture(page, testInfo, 'audit-exported')
}

// ---- Overview, Storage, Health, About and Sign-in (task 0012) ----

const opsTab = (page: Page, name: string) => page.getByRole('tablist', { name: 'Platform operations sections' }).getByRole('tab', { name: new RegExp(`^${name}( needs attention)?$`) })
const ISO_STAMP = /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/
/** Raw status words that must not show as text on these pages (Technical details stay folded). */
const RAW_WORDS = /\b(DEGRADED|ATTENTION|NOT_CONFIGURED|OFFLINE|ACTIVE|NEVER_RUN|lastSweepAt|attention submissions)\b|^UP$|^DOWN$/

async function expectNoRawWords(page: Page) {
  await expect(visible(page, RAW_WORDS)).toHaveCount(0)
  const text = await page.locator('main, .v-page').first().innerText()
  expect(text, 'no ISO time stamps on screen').not.toMatch(ISO_STAMP)
}

/** Overview: the opening sentence and four tiles with the demo problems, then (simulated) with nothing wrong. */
async function overviewChecks(page: Page, testInfo: TestInfo) {
  await opsTab(page, 'Overview').click()
  const lead = page.getByRole('region', { name: 'How Vero is doing' })
  await expect(lead).toContainText(/^(\d+ things? needs? attention\. .+\.|Everything is working\.)/, { timeout: 15000 })
  const tiles = page.getByRole('list', { name: 'Overview tiles' }).getByRole('listitem')
  await expect(tiles).toHaveCount(4)
  for (const [i, label] of ['Problems', 'Storage', 'Services', 'Cleanup'].entries()) await expect(tiles.nth(i)).toContainText(label)
  await expect(tiles.nth(1)).toContainText(/warns at \d+%, stops at \d+%|Can’t reach|No volume/)
  await expect(tiles.nth(3)).toContainText(/deleted today|Last run failed/, { timeout: 15000 })
  // The demo data has problems; a volume Tenant IT set offline on purpose is never one of the things needing attention.
  await expect(lead).toContainText(/did not arrive properly/)
  await expect(lead).not.toContainText('tour-volume')
  await expect(tiles.nth(0)).toContainText(/could not be stored|stuck|missing documents/)
  const activity = page.getByRole('list', { name: 'Latest activity' }).getByRole('listitem')
  await expect(activity.first()).toBeVisible({ timeout: 15000 })
  expect(await activity.count()).toBeLessThanOrEqual(5)
  await expect(activity.first()).toContainText(/^(Today|Yesterday|\d+ \w+) \d{2}:\d{2}/)
  // Task 0020 (item 2): no searches, page loads or other routine reads in Latest activity.
  await expect(activity.filter({ hasText: /searched records|loaded (page \d+|a page) of|looked at (Recently viewed|the audit)|opened a record|checked a folder/ })).toHaveCount(0)
  await expectNoRawWords(page)
  await capture(page, testInfo, 'overview-with-problems')
  await tiles.nth(0).click()
  await expect(opsTab(page, 'Problems')).toHaveAttribute('aria-selected', 'true')

  // Nothing wrong (simulated in the browser only): "Everything is working."
  await page.route('**/api/v1/operations/summary', async (route) => {
    const s = await (await route.fetch()).json()
    await route.fulfill({ json: { ...s, processingFailureCount: 0, stuckDocumentCount: 0, attentionSubmissionCount: 0, processingFailures: [], stuckDocuments: [], attentionSubmissions: [],
      storageVolumes: s.storageVolumes.map((v: Record<string, unknown>) => (v.state === 'ACTIVE' ? { ...v, warningThresholdReached: false, stopWriteThresholdReached: false, observationError: null, writable: true } : v)) } })
  })
  await page.route('**/api/v1/operations/retention-status', (route) => route.fulfill({ json: { outcome: 'SUCCESS', startedAt: new Date().toISOString(), completedAt: new Date().toISOString(), candidates: 0, deleted: 0, failed: 0, reasonCode: null, nextRunAt: tomorrowAt7().toISOString() } }))
  await opsTab(page, 'Overview').click()
  await expect(lead).toHaveText('Everything is working.', { timeout: 15000 })
  await expect(tiles.nth(0)).toContainText('None')
  await expect(tiles.nth(2)).toContainText('All working')
  // Task 0023, as on the canvas: the Cleanup tile says when the next run is.
  await expect(tiles.nth(3)).toContainText('Documents past their keep date · next run tomorrow 07:00')
  await capture(page, testInfo, 'overview-all-working')
  await page.unroute('**/api/v1/operations/summary')
  await page.unroute('**/api/v1/operations/retention-status')
}

/** Storage for Platform operations: read-only, in words, with what to do and folded technical details. */
async function opsStorageChecks(page: Page, testInfo: TestInfo) {
  await opsTab(page, 'Storage').click()
  const table = page.getByRole('table', { name: 'Storage volumes' })
  await expect(table).toBeVisible({ timeout: 15000 })
  await expect(table.locator('thead th')).toHaveText(['Volume', 'Role', 'How full', 'Can store new documents'])
  await expect(table.locator('tbody tr').filter({ hasText: 'primary-a' })).toContainText(/In use/)
  const offline = table.locator('tbody tr').filter({ hasText: 'tour-volume' })
  if (await offline.count()) {
    await expect(offline).toContainText('Offline (set by Tenant IT)')
    await expect(offline).toContainText('No: offline on purpose')
  }
  await expect(page.getByRole('button', { name: /Edit|Add volume|Save/ })).toHaveCount(0)
  await expect(page.getByText('What to do:', { exact: true })).toBeVisible()
  await expect(page.locator('details.v-tech')).not.toHaveAttribute('open', '')
  await expectNoRawWords(page)
  await capture(page, testInfo, 'ops-storage')
}

/** Tomorrow at 07:00 local time: the canvas's daily cleanup sample (task 0023). */
function tomorrowAt7() { const d = new Date(); d.setDate(d.getDate() + 1); d.setHours(7, 0, 0, 0); return d }

/** Health: checked time and Check now, services in words, a stopped key service (simulated), and cleanup in a sentence. */
async function healthChecks(page: Page, testInfo: TestInfo) {
  await opsTab(page, 'Health').click()
  await expect(page.getByRole('status').filter({ hasText: /^Checked (just now|\d+ minutes? ago)$/ })).toBeVisible({ timeout: 15000 })
  await expect(page.getByRole('button', { name: 'Refresh' })).toHaveCount(0)
  const services = page.getByRole('table', { name: 'Services' })
  await expect(services.locator('tbody tr')).toHaveCount(3)
  for (const [name, does] of [['Vero', 'The application itself'], ['Database', 'Keeps records, settings and the audit'], ['Key service', 'Locks and unlocks stored documents (Vault)']]) {
    await expect(services.locator('tbody tr').filter({ hasText: name }).first()).toContainText(does)
  }
  const cleanup = page.getByRole('region', { name: 'Automatic cleanup' })
  await expect(cleanup).toContainText(/Last ran (today|yesterday|on .+) at \d{2}:\d{2}|hasn’t run since Vero last started/)
  await expectNoRawWords(page)
  await capture(page, testInfo, 'health')

  // Task 0023: the next run, worked out by the server from the scheduler's own setting. Locally that is a fixed
  // delay after each run (one minute by default), so it is soon, or null while a run is going on.
  const status = await (await page.request.get('/api/v1/operations/retention-status', { headers: OPS_HEADERS })).json() as { nextRunAt?: string | null }
  expect('nextRunAt' in status, 'the retention status has nextRunAt').toBeTruthy()
  if (status.nextRunAt) expect(Date.parse(status.nextRunAt)).toBeGreaterThan(Date.now() - 5_000)
  // A daily sample (simulated in the browser): the sentence ends with the next run, in the "Last ran" style.
  const today7 = new Date(); today7.setHours(7, 0, 0, 0)
  await page.route('**/api/v1/operations/retention-status', (route) => route.fulfill({ json: { outcome: 'SUCCESS', startedAt: today7.toISOString(), completedAt: today7.toISOString(), candidates: 3, deleted: 3, failed: 0, reasonCode: null, nextRunAt: tomorrowAt7().toISOString() } }))
  await page.getByRole('button', { name: 'Check now' }).click()
  await expect(cleanup).toContainText('Last ran today at 07:00 and deleted 3 documents that had passed their keep date. Next run: tomorrow at 07:00.', { timeout: 15000 })
  await capture(page, testInfo, 'health-next-run')
  // When the server can't say (no nextRunAt), nothing is shown rather than a guess.
  await page.unroute('**/api/v1/operations/retention-status')
  await page.route('**/api/v1/operations/retention-status', (route) => route.fulfill({ json: { outcome: 'SUCCESS', startedAt: today7.toISOString(), completedAt: today7.toISOString(), candidates: 3, deleted: 3, failed: 0, reasonCode: null, nextRunAt: null } }))
  await page.getByRole('button', { name: 'Check now' }).click()
  await expect(cleanup).toContainText('Last ran today at 07:00 and deleted 3 documents that had passed their keep date.', { timeout: 15000 })
  await expect(cleanup).not.toContainText('Next run')
  await page.unroute('**/api/v1/operations/retention-status')

  // The key service stops (simulated in the browser only), and a cleanup run fails.
  await page.route('**/api/v1/operations/summary', async (route) => {
    const s = await (await route.fetch()).json()
    await route.fulfill({ json: { ...s, keyService: { ...s.keyService, status: 'DOWN', message: 'Key service health check failed.' } } })
  })
  await page.route('**/api/v1/operations/retention-status', (route) => route.fulfill({ json: { outcome: 'FAILED', startedAt: new Date().toISOString(), completedAt: new Date().toISOString(), candidates: 0, deleted: 0, failed: 1, reasonCode: 'RETENTION_SWEEP_FAILED' } }))
  await page.getByRole('button', { name: 'Check now' }).click()
  await expect(page.getByText('Key service isn’t answering. Nobody can open documents and new deliveries wait. Check that Vault is running and unsealed.')).toBeVisible({ timeout: 15000 })
  await expect(services.locator('tbody tr').filter({ hasText: 'Key service' })).toContainText('Not answering')
  await expect(cleanup).toContainText(/The last cleanup run failed today at \d{2}:\d{2}/)
  await expect(cleanup).toContainText('What to do:')
  await capture(page, testInfo, 'health-key-service-stopped')
  await opsTab(page, 'Overview').click()
  await expect(page.getByRole('region', { name: 'How Vero is doing' })).toContainText(/the key service isn’t answering/, { timeout: 15000 })
  await page.unroute('**/api/v1/operations/summary')
  await page.unroute('**/api/v1/operations/retention-status')

  // Task 0013: Vero started against an older database (simulated in the browser only). The server then refuses
  // every request except system information, and Health shows the same sentence as the startup log.
  const older = 'The database is older than this Vero version. Ask your database team to run the schema step (install guide, "Update the database").'
  await page.route('**/api/v1/system/info', async (route) => {
    const i = await (await route.fetch()).json()
    await route.fulfill({ json: { ...i, status: 'DOWN', databaseSchema: { state: 'OLDER', message: older } } })
  })
  await page.route('**/api/v1/operations/summary', (route) => route.fulfill({ status: 503, json: { code: 'DATABASE_OLDER', message: older } }))
  await page.route('**/api/v1/operations/retention-status', (route) => route.fulfill({ status: 503, json: { code: 'DATABASE_OLDER', message: older } }))
  await opsTab(page, 'Health').click()
  const mismatch = page.getByRole('alert').filter({ hasText: 'The database doesn’t match this Vero version' })
  await expect(mismatch).toContainText(older, { timeout: 15000 })
  await expect(page.getByText('Vero couldn\'t check its services')).toHaveCount(0)
  // Cleanup is off while the database doesn't match, so its "check now again" advice isn't shown.
  await expect(page.getByRole('region', { name: 'Automatic cleanup' })).toHaveCount(0)
  await capture(page, testInfo, 'health-database-older')
  await page.unroute('**/api/v1/system/info')
  await page.unroute('**/api/v1/operations/summary')
  await page.unroute('**/api/v1/operations/retention-status')
}

/** About: the facts, Copy details for support without personal data, and the old addresses open it. */
async function aboutChecks(page: Page, testInfo: TestInfo, area: 'operations' | 'tenant', oldTab: 'configuration' | 'environment', person: string) {
  await page.evaluate((hash) => { window.location.hash = hash }, `#/${area}/${oldTab}`)
  const sections = area === 'operations' ? 'Platform operations sections' : 'Tenant IT sections'
  await expect(page.getByRole('tablist', { name: sections }).getByRole('tab', { name: /^About/ })).toHaveAttribute('aria-selected', 'true', { timeout: 15000 })
  await expect(page.getByRole('tablist', { name: sections }).getByRole('tab', { name: /^(Configuration|Environment|Platform health)$/ })).toHaveCount(0)
  const facts = page.getByLabel('Installation facts')
  await expect(facts).toContainText(/Product\s*Vero /, { timeout: 15000 })
  for (const fact of ['Status', 'Organization', 'Sign-in', 'Checked']) await expect(facts).toContainText(fact)
  await expect(facts).toContainText('Working')
  await expect(facts).toContainText('Acme Lending')
  await expect(facts).toContainText('Local test sign-in')
  await expect(facts).toContainText(/Today \d{2}:\d{2}/)
  await expect(page.locator('details.v-tech')).not.toHaveAttribute('open', '')
  const note = await page.getByLabel('Details for support').textContent()
  expect(note).toContain('Organization: Acme Lending')
  expect(note).toContain('Active profiles: local')
  expect(note, 'no personal data in the support note').not.toContain(person)
  expect(note).not.toMatch(/local-[a-z-]+/)
  if (isDesktop(testInfo)) {
    await page.context().grantPermissions(['clipboard-read', 'clipboard-write'])
    await page.getByRole('button', { name: 'Copy details for support' }).click()
    await expect(page.getByRole('status').filter({ hasText: 'Copied' })).toBeVisible()
    // Windows turns line breaks on the clipboard into CRLF.
    expect((await page.evaluate(() => navigator.clipboard.readText())).replace(/\r\n/g, '\n')).toBe(note)
  }
  await expectNoRawWords(page)
  await capture(page, testInfo, `about-${area}`)
}

/** Sign-in for Tenant IT: read-only, with who to contact to change it. */
async function signInChecks(page: Page, testInfo: TestInfo) {
  await tenantTab(page, 'Sign-in').click()
  await expect(page.getByText('Sign-in is set during installation by your installer. To change it, contact your Vero support team.')).toBeVisible({ timeout: 15000 })
  await expect(page.getByRole('button', { name: /Edit|draft|Test|Activate|Roll back|Save/i })).toHaveCount(0)
  await expect(page.getByRole('textbox')).toHaveCount(0)
  await capture(page, testInfo, 'sign-in-read-only')
}

/** A small CSV reader for the export check: quoted cells, doubled quotes, CRLF rows. */
function parseCsv(text: string): string[][] {
  const out: string[][] = []
  let row: string[] = [], cell = '', quoted = false
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') { cell += '"'; i++ } else if (c === '"') quoted = false; else cell += c
    } else if (c === '"') quoted = true
    else if (c === ',') { row.push(cell); cell = '' }
    else if (c === '\n') { row.push(cell); out.push(row); row = []; cell = '' }
    else if (c !== '\r') cell += c
  }
  if (cell || row.length) { row.push(cell); out.push(row) }
  return out
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
    // Task 0030: a tab may also carry a count ("Approvals 50 waiting").
    const tabButton = tablist.getByRole('tab', { name: new RegExp(`^${escaped}( needs attention)?( \\d+ [a-z ]+)?$`) })
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

const searchBox = (page: Page) => page.getByRole('combobox', { name: 'Search records' })
const resultRows = (page: Page) => page.getByRole('grid', { name: 'Records' }).locator('tbody tr')
const chip = (page: Page, text: RegExp) => page.getByRole('search').locator('.ws-chip').filter({ hasText: text })
const clearSearch = (page: Page) => page.getByRole('button', { name: 'Clear', exact: true }).click()

const resultsBusy = (page: Page) => page.getByRole('region', { name: 'Search results' }).locator('[aria-busy="true"]')

/** Records is ready once the sending systems and their fields have loaded: until then the results show a loading skeleton. */
async function waitForBusinessSearchReady(page: Page) {
  await expect(searchBox(page)).toBeVisible({ timeout: 15000 })
  await expect(page.getByRole('button', { name: /^Filters/ })).toBeVisible()
  await expect(resultsBusy(page), 'the sending systems have loaded').toHaveCount(0, { timeout: 15000 })
}

/**
 * Task 0035: waits for the search's own answer, then for the list to be drawn. Before, only the loading skeleton was
 * awaited, which can be checked before the search has even started (report 0025: rows never seen for "gomez").
 */
async function searchFor(page: Page, value: string) {
  const wanted = JSON.stringify(value.trim())
  const answered = page.waitForResponse((r) => r.url().endsWith('/api/v1/business/search') && r.request().method() === 'POST'
    && JSON.stringify(r.request().postDataJSON() ?? {}).includes(wanted), { timeout: 15000 })
  answered.catch(() => { /* awaited below; this only stops an early failure from leaving it unhandled */ })
  await searchBox(page).fill(value)
  await searchBox(page).press('Enter')
  const response = await answered
  expect(response.ok(), `the search for ${value} was answered`).toBeTruthy()
  await expect(resultsBusy(page)).toHaveCount(0, { timeout: 15000 })
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
  await expect(attentionRow).toContainText('Only 1 of 2 documents have arrived. The rest are still coming from LexaVault.')
  await capture(page, testInfo, 'home')

  const firstListed = ((await attention.locator('.home-attention').first().innerText()).match(/\d{12}/) ?? [''])[0]
  await attention.getByRole('button', { name: /Show all/ }).click()
  await expect(areaTab(page, 'Records')).toHaveAttribute('aria-current', 'page')
  await expect(chip(page, /^Missing documents$/)).toBeVisible()
  await expect(chip(page, /^Received /)).toBeVisible()
  await expect(resultRows(page).filter({ hasText: firstListed })).toBeVisible({ timeout: 15000 })
  await expect(resultRows(page).filter({ hasText: firstListed })).toContainText(/\d+ of \d+ received/)
  await capture(page, testInfo, 'records-needs-attention')

  await areaTab(page, 'Home').click()
  await searchBox(page).fill(demoRecord)
  await searchBox(page).press('Enter')
  await expect(areaTab(page, 'Records')).toHaveAttribute('aria-current', 'page')
  // Task 0022: Enter searches every field; the exact contract number comes first.
  await expect(chip(page, new RegExp(`^Anywhere ${demoRecord}`))).toBeVisible()
  await expect(resultRows(page).first()).toContainText(demoRecord, { timeout: 15000 })
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
  // Under the folded Technical details: read the text, which is there even while the section is closed.
  return ((await page.locator('.vw-panel dt', { hasText: 'Document ID' }).locator('xpath=following-sibling::dd[1]').textContent()) ?? '').trim()
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
    await reloadApp(page)
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

  // 1. Search by contract number (task 0022): Enter searches every field; the exact contract number comes first.
  await searchFor(page, demoRecord)
  await expect(chip(page, new RegExp(`^Anywhere ${demoRecord}`))).toBeVisible()
  if (!(await expectRows(page, testInfo, `contract ${demoRecord} not found`))) return
  const recordRow = resultRows(page).filter({ hasText: demoRecord }).first()
  await expect(recordRow).toBeVisible({ timeout: 15000 })
  await expect(resultRows(page).first()).toContainText(demoRecord)
  await capture(page, testInfo, 'results-contract')

  // 2. One field from the list (task 0022, replaces the "Search it as … instead" suggestion): a member number,
  //    searched only as Member Number.
  await clearSearch(page)
  await searchBox(page).fill(demoMember)
  await page.getByRole('listbox', { name: 'Search suggestions' }).getByRole('option', { name: new RegExp(`^Member Number is .${demoMember}.`) }).click()
  await expect(chip(page, new RegExp(`Member Number is ${demoMember}`))).toBeVisible()
  await expect(resultRows(page).filter({ hasText: demoRecord })).toBeVisible({ timeout: 15000 })

  // 3. Search by name in lower case, everywhere: Contains/Starts with ignore case ("pat" finds "Patel").
  await clearSearch(page)
  await searchFor(page, demoName)
  await expect(chip(page, new RegExp(`^Anywhere ${demoName}`))).toBeVisible()
  if (!(await expectRows(page, testInfo, `no record whose name contains "${demoName}"`))) return
  const nameRows = await resultRows(page).count()
  await capture(page, testInfo, 'results-name')

  // 4. Add a filter chip from the Filters panel, then remove it with ×.
  const firstRowFirstName = (await resultRows(page).first().locator('td.ws-name').innerText().catch(() => '')).trim().split(/\s/)[0] ?? ''
  if (!firstRowFirstName || firstRowFirstName === '—') return missingDemoData(testInfo, 'no record title in the name results (run Seed-SDV-Canvas-Demo.ps1)')
  await page.getByRole('button', { name: /^Filters/ }).click()
  let filters = page.getByRole('dialog', { name: 'Filters' })
  await expect(filters).toBeVisible()
  // Task 0020 (item 6): hints from each field's settings inside the empty boxes (demo: Contract Number is digits only, First Name exact).
  await expect(filters.getByLabel(/^Contract Number/)).toHaveAttribute('placeholder', 'Digits only')
  await expect(filters.getByLabel(/^First Name/)).toHaveAttribute('placeholder', 'Any')
  for (const box of await filters.locator('.ws-filter-fields input[type="text"]').all()) {
    await expect(box).toHaveAttribute('placeholder', /^(Digits only|Any|The first letters|Any part)$/)
  }
  await capture(page, testInfo, 'filters')
  await filters.getByLabel(/^First Name/).fill(firstRowFirstName.split(/\s/)[0])
  await filters.getByRole('button', { name: 'Show results' }).click()
  const added = chip(page, /^First Name is /)
  await expect(added).toBeVisible()
  await expect(page.getByRole('button', { name: /^Filters/ })).toContainText(`· ${await page.getByRole('search').locator('.ws-chip').count()}`)
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

// ---- Which sending system Records searches (task 0017) ----

const BUSINESS_HEADERS = { 'X-SDV-Tenant-Id': LOCAL_TENANT, 'X-SDV-Subject': 'local-business-user', 'X-SDV-Roles': 'BUSINESS_USER' }
const LAST_SOURCE_KEY = 'vero.records.sourceId'
/** A record of the canvas's loan system (Seed-SDV-Canvas-Demo.ps1); LexaVault has no record with this number. */
const CANVAS_RECORD = '300000000001'
type SchemaSource = { sourceId: string; displayName: string; fields: unknown[]; status: 'ACTIVE' | 'PAUSED'; hasRecords: boolean; lastDeliveredAt: string | null }
async function searchSources(page: Page) {
  const r = await page.request.get('/api/v1/business/search-schema', { headers: BUSINESS_HEADERS })
  expect(r.ok(), 'reading the search schema').toBeTruthy()
  return ((await r.json()) as { sources: SchemaSource[] }).sources
}

// ---- The tour's own test record (task 0035) ----
// The steps that make a request and decline it (tasks 0030 and 0031) used the seed's police report on 300000000008,
// so every run added to a demo document's history. They now use this record of the loan system, which only the tour
// uses: one Highly Confidential document, tour-check.pdf. The tour delivers it through the producer API the first time
// and uses it again after that (a new record each run would add a record to the demo lists every time).
const TOUR_RECORD = '399000000001'
const TOUR_FILE = 'tour-check.pdf'
const TOUR_PDF = path.resolve(process.cwd(), '..', '..', 'test-tools', 'fixtures', 'business-viewer-two-page.pdf')
const DANA_HEADERS = { 'X-SDV-Tenant-Id': LOCAL_TENANT, 'X-SDV-Subject': 'local-restricted-steward', 'X-SDV-Roles': 'RESTRICTED_STEWARD', 'Content-Type': 'application/json' }

/** The document ID of tour-check.pdf on the tour's own record, delivered first if it isn't there yet. */
async function tourCheckDocument(page: Page): Promise<string> {
  const los = (await searchSources(page)).find((s) => s.displayName === 'Loan Origination System')
  expect(los, 'the Loan Origination System from Seed-SDV-Canvas-Demo.ps1').toBeTruthy()
  const find = async () => {
    const found = await (await page.request.post('/api/v1/business/search', { headers: DANA_HEADERS, data: { sourceId: los!.sourceId, criteria: { contractNumber: TOUR_RECORD }, page: 1, pageSize: 5 } })).json() as { records: { recordRef: string }[] }
    if (!found.records.length) return null
    const rec = await (await page.request.get('/api/v1/business/records/' + encodeURIComponent(found.records[0].recordRef), { headers: DANA_HEADERS })).json() as { documents: { documentId: string; originalFilename: string }[] }
    return rec.documents.find((d) => d.originalFilename === TOUR_FILE)?.documentId ?? null
  }
  const have = await find()
  if (have) return have
  const producer = { 'X-SDV-Tenant-Id': LOCAL_TENANT, 'X-SDV-Source-Id': los!.sourceId, 'X-SDV-Subject': 'local-demo-producer' }
  const key = `tour-check-${TOUR_RECORD}`
  const sub = await page.request.post('/api/v1/ingestion/submissions', { headers: { ...producer, 'Content-Type': 'application/json' }, data: {
    sourceSubmissionId: key, expectedDocumentCount: 1, expectedDocuments: [{ sourceDocumentId: `${key}-1` }],
    metadata: { contractNumber: TOUR_RECORD, borrowerName: 'Tour Check (test record)', loanType: 'Auto', closingDate: '2026-12-31' } } })
  expect(sub.ok(), `delivering the tour's own record ${TOUR_RECORD}: ${sub.status()} ${await sub.text()}`).toBeTruthy()
  const submissionId = (await sub.json() as { submissionId: string }).submissionId
  const pdf = Buffer.concat([fs.readFileSync(TOUR_PDF), Buffer.from(`\n%vero-tour-copy ${key}\n`)])
  const doc = await page.request.post(`/api/v1/ingestion/submissions/${submissionId}/documents`, { headers: { ...producer,
    'X-SDV-Source-Document-Id': `${key}-1`, 'X-SDV-Filename': TOUR_FILE, 'X-SDV-Sensitivity-Level': 'HIGHLY_CONFIDENTIAL', 'Content-Type': 'application/pdf' }, data: pdf })
  expect(doc.ok(), `delivering ${TOUR_FILE}: ${doc.status()} ${await doc.text()}`).toBeTruthy()
  let id: string | null = null
  await expect.poll(async () => (id = await find()), { message: `${TOUR_FILE} is listed on ${TOUR_RECORD}`, timeout: 15000 }).not.toBeNull()
  return id!
}

/** The tour searches LexaVault's demo records, so it chooses LexaVault the way a person does: as their last choice in this browser. */
async function chooseLexaVault(page: Page) {
  const lexa = (await searchSources(page)).find((s) => s.displayName === 'LexaVault')
  expect(lexa, 'the LexaVault sending system').toBeTruthy()
  await page.evaluate(([key, id]) => { try { window.localStorage.setItem(key, id) } catch { /* the default rule still picks it */ } }, [LAST_SOURCE_KEY, lexa!.sourceId])
}

test.describe('SDV role-based screen capture', () => {
  test.beforeEach(async ({ page }) => {
    await openApp(page, '/')
    await chooseLexaVault(page)
  })

  test('Records starts with the loan system while a paused, half-set-up system exists', async ({ page }, testInfo) => {
    test.skip(!isDesktop(testInfo), 'Runs once per tour (desktop only).')
    test.setTimeout(240_000) // thirteen full page loads
    // The test case is the leftover paused "Tour test system …" source: no searchable field and no record (reports
    // 0013 and 0017; Seed-SDV-Canvas-Demo.ps1 makes it on a fresh machine).
    const sources = await searchSources(page)
    const leftover = sources.find((s) => /^Tour test system /.test(s.displayName) && s.fields.length === 0)
    expect(leftover, 'the test case: a "Tour test system" source without search fields must exist (keep it, task 0017)').toBeTruthy()
    // Task 0023: the schema says each system's status, whether it holds records and when it last delivered.
    expect(leftover!.status, 'the leftover system is paused').toBe('PAUSED')
    expect(leftover!.hasRecords, 'the leftover system holds no record').toBe(false)
    const los = sources.find((s) => s.displayName === 'Loan Origination System')
    const lexa = sources.find((s) => s.displayName === 'LexaVault')
    expect(los && lexa, 'the loan system and LexaVault (Seed-SDV-Canvas-Demo.ps1)').toBeTruthy()
    for (const s of [los!, lexa!]) {
      expect(s.status, `${s.displayName} is active`).toBe('ACTIVE')
      expect(s.hasRecords, `${s.displayName} holds records`).toBe(true)
    }
    // The seed delivers the loan system last, so it is the active system that delivered most recently.
    expect(Date.parse(los!.lastDeliveredAt ?? ''), 'the loan system delivered most recently').toBeGreaterThan(Date.parse(lexa!.lastDeliveredAt ?? ''))
    await selectRole(page, 'Business User')
    await areaTab(page, 'Records').click()
    await waitForBusinessSearchReady(page)

    const results = page.getByRole('region', { name: 'Search results' })
    const remember = (id: string | null) => page.evaluate(([key, v]) => { if (v) window.localStorage.setItem(key, v); else window.localStorage.removeItem(key) }, [LAST_SOURCE_KEY, id] as const)
    /** Reload, type straight away (before the sending systems have loaded, the cause in report 0017) and search. */
    const searchAfterReload = async (contract: string, system: string, what: string) => {
      await reloadApp(page)
      await searchBox(page).fill(contract)
      await searchBox(page).press('Enter')
      await expect(resultRows(page).filter({ hasText: contract }).first(), `${what}: the default is ${system}`).toBeVisible({ timeout: 15000 })
      await expect(results, `${what}: searched in ${system}`).toContainText(system)
      await expect(page.getByText('no field the search box can use')).toHaveCount(0)
    }

    // Ten loads in a row with no remembered choice, and with the half-set-up system remembered (it never qualifies).
    for (let i = 1; i <= 10; i++) {
      await remember(i % 2 === 0 ? leftover!.sourceId : null)
      await searchAfterReload(CANVAS_RECORD, 'Loan Origination System', `load ${i}`)
      await page.getByRole('button', { name: /^Filters/ }).click()
      const filters = page.getByRole('dialog', { name: 'Filters' })
      await expect(filters).toBeVisible()
      await expect(filters).not.toContainText('Tour test system')
      await page.keyboard.press('Escape')
      await expect(filters).toHaveCount(0)
    }

    // A last choice that still qualifies is kept.
    await remember(lexa!.sourceId)
    await searchAfterReload(demoRecord, 'LexaVault', 'LexaVault remembered')

    // Simulated in the browser: LexaVault, the remembered choice, no longer holds records, so it is ignored.
    const changeSchema = (change: (s: SchemaSource) => SchemaSource) => page.route('**/api/v1/business/search-schema', async (route) => {
      const s = await (await route.fetch()).json() as { sources: SchemaSource[] }
      await route.fulfill({ json: { ...s, sources: s.sources.map(change) } })
    })
    await changeSchema((s) => s.sourceId === lexa!.sourceId ? { ...s, hasRecords: false, lastDeliveredAt: null } : s)
    await searchAfterReload(CANVAS_RECORD, 'Loan Origination System', 'LexaVault remembered but without records')
    await page.unrouteAll({ behavior: 'ignoreErrors' })

    // Simulated: the loan system is paused. An active system that qualifies comes first, although it delivered earlier.
    await remember(null)
    await changeSchema((s) => s.sourceId === los!.sourceId ? { ...s, status: 'PAUSED' } : s)
    await searchAfterReload(demoRecord, 'LexaVault', 'loan system paused')
    await page.unrouteAll({ behavior: 'ignoreErrors' })

    // With only systems that can't be searched (simulated in the browser), Records and Home say so.
    await page.route('**/api/v1/business/search-schema', async (route) => {
      const s = await (await route.fetch()).json() as { sources: SchemaSource[] }
      await route.fulfill({ json: { ...s, sources: s.sources.filter((x) => x.fields.length === 0) } })
    })
    await reloadApp(page)
    await expect(results.getByRole('heading', { name: 'There’s nothing to search yet.' })).toBeVisible({ timeout: 15000 })
    await expect(results).toContainText('Records appear here once a sending system delivers documents.')
    await expect(page.getByRole('button', { name: /^Filters/ })).toBeDisabled()
    await expect(searchBox(page)).toHaveAttribute('placeholder', 'Nothing to search yet')
    await capture(page, testInfo, 'records-nothing-to-search')
    await areaTab(page, 'Home').click()
    await searchBox(page).fill(demoRecord)
    await searchBox(page).press('Enter')
    await expect(page.getByRole('status').filter({ hasText: 'There’s nothing to search yet. Records appear here once a sending system delivers documents.' })).toBeVisible({ timeout: 15000 })
    await page.unrouteAll({ behavior: 'ignoreErrors' }) // a schema request may still be in flight

    // Task 0023 follow-up (owner's answer 2): searchable systems exist, but none holds a record yet (a new
    // installation, simulated in the browser). No system is picked by name; Records and Home say so instead.
    await page.route('**/api/v1/business/search-schema', async (route) => {
      const s = await (await route.fetch()).json() as { sources: SchemaSource[] }
      await route.fulfill({ json: { ...s, sources: s.sources.map((x) => ({ ...x, hasRecords: false, lastDeliveredAt: null })) } })
    })
    await remember(los!.sourceId) // even a stored last choice is not used
    await areaTab(page, 'Records').click()
    await reloadApp(page)
    await expect(results.getByRole('heading', { name: 'There’s nothing to search yet.' })).toBeVisible({ timeout: 15000 })
    await expect(results).toContainText('Records appear here once a sending system delivers documents.')
    await expect(page.getByRole('button', { name: /^Filters/ })).toBeDisabled()
    await expect(searchBox(page)).toHaveAttribute('placeholder', 'Nothing to search yet')
    await searchBox(page).fill(CANVAS_RECORD)
    await searchBox(page).press('Enter')
    await expect(page.getByRole('status').filter({ hasText: 'There’s nothing to search yet. Records appear here once a sending system delivers documents.' })).toBeVisible({ timeout: 15000 })
    await capture(page, testInfo, 'records-nothing-delivered')
    await areaTab(page, 'Home').click()
    await expect(searchBox(page)).toHaveAttribute('placeholder', 'Nothing to search yet', { timeout: 15000 })
    await expect(page.getByRole('button', { name: /^Filters/ })).toBeDisabled()
    await searchBox(page).fill(CANVAS_RECORD)
    await searchBox(page).press('Enter')
    await expect(page.getByRole('status').filter({ hasText: 'There’s nothing to search yet. Records appear here once a sending system delivers documents.' })).toBeVisible({ timeout: 15000 })
    await capture(page, testInfo, 'home-nothing-delivered')
    await page.unrouteAll({ behavior: 'ignoreErrors' })
    await remember(null)
  })

  // Task 0029: one record seen by the three business roles. Seed-SDV-Canvas-Demo.ps1 gives 300000000003 (Marcus Lee)
  // a Highly Confidential settlement agreement, and Dana Ruiz an approved print of it and a waiting lowering. The
  // tour opens dialogs and cancels them: it changes no level and sends no request.
  test('Sensitivity: business user, document steward and restricted steward on one record (task 0029)', async ({ page }, testInfo) => {
    test.skip(!isDesktop(testInfo), 'Runs once per tour (desktop only).')
    test.setTimeout(240_000)
    const RECORD = '300000000003'
    const los = (await searchSources(page)).find((s) => s.displayName === 'Loan Origination System')
    expect(los, 'the Loan Origination System from Seed-SDV-Canvas-Demo.ps1').toBeTruthy()
    await page.evaluate(([key, id]) => window.localStorage.setItem(key, id), [LAST_SOURCE_KEY, los!.sourceId])
    const inspector = () => page.getByRole('complementary', { name: 'Record inspector' })
    const lockedItem = () => inspector().getByRole('button', { name: /^Restricted document, Highly Confidential/ })
    const viewerBar = () => viewer(page).locator('.vw-bar').first()
    const openRecord = async () => {
      await areaTab(page, 'Records').click()
      await waitForBusinessSearchReady(page)
      await searchFor(page, RECORD)
      const row = resultRows(page).filter({ hasText: RECORD }).first()
      await expect(row).toBeVisible({ timeout: 15000 })
      await row.click()
      await expect(inspector()).toBeVisible({ timeout: 15000 })
      await expect(inspector().getByText('Contract level')).toBeVisible()
      return row
    }
    const openDocuments = async () => {
      await inspector().getByRole('button', { name: /Open documents/ }).click()
      await expect(page.getByRole('img', { name: /Document page 1/ })).toBeVisible({ timeout: 20000 })
    }
    const closeViewer = () => viewer(page).getByRole('button', { name: /^Back to/ }).click()

    // 1. Business user: a lock on the row, a locked item that reveals nothing, no Level, no requests.
    await selectRole(page, 'Business User')
    const row = await openRecord()
    await expect(row.getByRole('img', { name: /Highly Confidential/ })).toBeVisible()
    await expect(lockedItem()).toBeVisible()
    await expect(inspector()).not.toContainText(/settlement/i)
    await expect(inspector().getByRole('button', { name: 'Level', exact: true })).toHaveCount(0)
    await lockedItem().click()
    await expect(page.getByText("You can't open this document", { exact: true }).last()).toBeVisible()
    await capture(page, testInfo, 'sensitivity-business-user-record')
    await openDocuments()
    await expect(viewerBar().getByRole('button', { name: /^Level/ })).toHaveCount(0)
    await expect(viewerBar().getByRole('button', { name: /^Request (download|print)/ })).toHaveCount(0)
    await expect(viewer(page).getByRole('tab', { name: /^Requests/ })).toHaveCount(0)
    await closeViewer()

    // 2. Document steward: the Highly Confidential document stays locked; Level in the panel and the viewer.
    await selectRole(page, 'Document Steward')
    await openRecord()
    await expect(lockedItem()).toBeVisible()
    await inspector().getByRole('button', { name: 'Level', exact: true }).click()
    let dialog = page.getByRole('dialog', { name: 'Change sensitivity level' })
    await expect(dialog.getByRole('radio', { name: /^Whole contract/ })).toBeChecked()
    await expect(dialog.getByRole('radio', { name: 'This document' })).toBeDisabled()
    await dialog.getByRole('radio', { name: /Highly Confidential/ }).check()
    await expect(dialog).toContainText("You won't be able to open the documents on this contract after this change.")
    await expect(dialog.getByRole('button', { name: 'Raise contract to Highly Confidential' })).toBeDisabled() // no reason yet
    await capture(page, testInfo, 'sensitivity-steward-level-dialog')
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(dialog).toHaveCount(0)
    await openDocuments()
    await expect(viewerBar().getByRole('button', { name: /^Request (download|print)/ })).toHaveCount(0)
    await viewerBar().getByRole('button', { name: /^Level/ }).click()
    dialog = page.getByRole('dialog', { name: 'Change sensitivity level' })
    await expect(dialog.getByRole('radio', { name: 'This document' })).toBeChecked()
    await expect(dialog.getByText('Now')).toBeVisible()
    await dialog.getByRole('button', { name: 'Cancel' }).click()
    await expect(viewer(page).getByRole('tab', { name: /^Requests/ })).toBeVisible()
    await closeViewer()

    // 3. Restricted steward: every document opens; for the Highly Confidential one, requests instead of Download and Print.
    await selectRole(page, 'Restricted Steward')
    await openRecord()
    await expect(inspector().getByRole('button', { name: /^Restricted document/ })).toHaveCount(0)
    await expect(inspector().getByRole('button', { name: 'Level', exact: true })).toBeVisible()
    await openDocuments()
    await page.getByRole('navigation', { name: 'Record documents' }).getByRole('button', { name: /Settlement agreement/ }).click()
    await expect(viewerBar()).toContainText('Highly Confidential', { timeout: 15000 })
    await expect(viewerBar().getByRole('button', { name: 'Download', exact: true })).toHaveCount(0)
    await expect(viewerBar().getByRole('button', { name: 'Print', exact: true })).toHaveCount(0)
    await expect(viewerBar().getByRole('button', { name: 'Request download' })).toBeVisible()
    await expect(viewerBar().getByRole('button', { name: /^Print \(approved, use by / })).toBeVisible({ timeout: 15000 })
    await viewer(page).getByRole('tab', { name: /^Requests/ }).click()
    const requests = viewer(page).getByRole('complementary', { name: 'Document information' })
    // Task 0035: the history may also hold earlier requests (used, declined, expired), so look for the seed's two.
    const seedHelp = 'from Seed-SDV-Canvas-Demo.ps1; if the demo data has moved on, run .\\Seed-SDV-Canvas-Demo.ps1 -Reset'
    await expect(requests.getByRole('listitem').filter({ hasText: /^Print/ }).filter({ hasText: 'Approved · use by' }).first(), `Dana's approved print ${seedHelp}`).toBeVisible()
    await expect(requests.getByRole('listitem').filter({ hasText: 'Lower to Confidential' }).filter({ hasText: 'Waiting for approval' }).first(), `Dana's waiting lowering ${seedHelp}`).toBeVisible()
    await viewerBar().getByRole('button', { name: 'Request download' }).click()
    const pop = page.getByRole('dialog', { name: 'Request a download' })
    await expect(pop).toContainText('A Business admin approves it. Once approved, you can download this document once, within 24 hours.')
    await expect(pop.getByRole('button', { name: 'Send request' })).toBeDisabled() // no reason yet
    await capture(page, testInfo, 'sensitivity-restricted-steward-viewer')
    await pop.getByRole('button', { name: 'Cancel' }).click()
    await expect(pop).toHaveCount(0)
    await closeViewer()
  })

  // Task 0030. Needs Seed-SDV-Canvas-Demo.ps1 (50 waiting print and download requests, a whole-contract request on
  // 300000000002, legal holds). Changes no demo data: Dana Ruiz asks to print the tour's own document (task 0035) and
  // a Business admin declines it, so the notice can be seen (a declined request stays in her history).
  test('Approvals, legal holds, requester notices and the audit level filter (task 0030)', async ({ page }, testInfo) => {
    test.skip(!isDesktop(testInfo), 'Runs once per tour (desktop only).')
    test.setTimeout(240_000)
    const TENANT = '11111111-1111-1111-1111-111111111111'
    const as = (subject: string, roles: string) => ({ 'X-SDV-Tenant-Id': TENANT, 'X-SDV-Subject': subject, 'X-SDV-Roles': roles, 'Content-Type': 'application/json' })
    const dana = as('local-restricted-steward', 'RESTRICTED_STEWARD'), priya = as('local-business-admin', 'BUSINESS_ADMIN')

    // 1. Approvals: more than 10 wait, so the groups start collapsed; the whole-contract group stays open.
    await selectRole(page, 'Business Administrator')
    await page.evaluate(() => { window.location.hash = '#/governance/approvals' })
    const tabs = page.getByRole('tablist', { name: 'Business administration sections' })
    await expect(tabs.getByRole('tab', { name: /^Approvals \d+ waiting$/ })).toBeVisible({ timeout: 15000 })
    await expect(page.getByRole('heading', { level: 1, name: 'Approvals' })).toBeVisible()
    const list = page.getByRole('region', { name: 'Waiting requests' })
    await expect(list).toBeVisible({ timeout: 15000 })
    const wholeGroup = list.getByRole('group').filter({ hasText: 'Whole contract: decide on its own' }).first()
    await expect(wholeGroup.getByRole('button', { name: /^Collapse / })).toHaveAttribute('aria-expanded', 'true')
    await expect(wholeGroup.getByRole('checkbox', { name: 'Cannot be selected: whole contract' })).toBeDisabled()
    await expect(wholeGroup.getByRole('button', { name: 'Review' })).toBeVisible()
    const collapsed = list.getByRole('group').filter({ has: page.getByRole('button', { name: /^Expand / }) })
    expect(await collapsed.count(), 'more than 10 waiting: the other groups start collapsed').toBeGreaterThan(0)
    // Chips count by kind; a collapsed heading names count, kinds and requesters; its tick selects the whole group.
    const chips = page.getByRole('toolbar', { name: 'Approvals filters' })
    await expect(chips.getByRole('button', { name: /^Downloads \d+$/ })).toBeVisible()
    const first = list.getByRole('group', { name: (await collapsed.first().getAttribute('aria-label'))!, exact: true })
    await expect(first).toContainText(/\d+ requests? · .*(download|print|lower).* · /)
    await first.getByRole('checkbox', { name: /^Select all on / }).check()
    const bar = page.getByRole('region', { name: 'Selected requests' })
    await expect(bar).toContainText(/\d+ selected · \d+ on \d+ \(.+\) · all one document/)
    await capture(page, testInfo, 'approvals-selected')
    await bar.getByRole('button', { name: 'Clear' }).click()
    await expect(bar).toHaveCount(0)
    // Nothing the board leaves out: no document ID, file name or title.
    const text = await list.innerText()
    expect(text).not.toMatch(/\.pdf|[0-9a-f]{8}-[0-9a-f]{4}-/i)
    // Review of a whole-contract request, cancelled.
    await wholeGroup.getByRole('button', { name: 'Review' }).click()
    const review = page.getByRole('alertdialog', { name: 'Review a whole-contract request' })
    await expect(review).toContainText('Lower to Confidential for the whole contract')
    await review.getByRole('button', { name: 'Cancel' }).click()
    // Self-approval: the server's refusal (SDV-APR-006) is shown in its own words. The refusal is simulated here,
    // because no local sign-in both asks and approves; the rule itself is tested on the server (AccessRequestApprovalsTest).
    await page.route('**/api/v1/admin/access-requests/*/approve', (route) => route.fulfill({ status: 403, contentType: 'application/json',
      body: JSON.stringify({ code: 'SDV-APR-006', message: "You can't approve or decline your own request." }) }))
    await first.getByRole('button', { name: /^Expand / }).click()
    await first.getByRole('button', { name: 'Approve', exact: true }).first().click()
    await expect(page.getByRole('alert').filter({ hasText: 'Not approved' })).toContainText("You can't approve or decline your own request. (SDV-APR-006)")
    await page.unroute('**/api/v1/admin/access-requests/*/approve')

    // 2. Legal holds: how many documents a contract number covers, never which; the holds in place with Release.
    await tabs.getByRole('tab', { name: 'Legal holds' }).click()
    await page.getByLabel('Contract number').fill('300000000001')
    await expect(page.getByText(/3 documents on contract 300000000001 will be held/)).toBeVisible({ timeout: 15000 })
    await expect(page.getByRole('button', { name: 'Place hold' })).toBeDisabled() // no reason yet
    const held = page.getByRole('table', { name: 'Legal holds in place' })
    await expect(held.locator('tbody tr').first()).toBeVisible()
    expect(await held.innerText()).not.toMatch(/\.pdf/i)
    await held.getByRole('button', { name: 'Release' }).first().click()
    await page.getByRole('alertdialog', { name: 'Release this hold?' }).getByRole('button', { name: 'Cancel' }).click()
    await capture(page, testInfo, 'legal-holds')
    await tabs.getByRole('tab', { name: 'Keeping documents' }).click()
    await expect(page.getByRole('button', { name: /^\d+ documents? on legal hold$/ })).toBeVisible({ timeout: 15000 })

    // 3. A notice after a decision. Dana opens Vero (this browser starts from what is there), asks to print the
    // tour's own document, a Business admin declines it, and the next time she opens Vero she is told; the Requests
    // tab counts it.
    await selectRole(page, 'Restricted Steward')
    // The notice has read her requests once, so what is there now counts as seen.
    await page.waitForFunction(() => Object.keys(window.localStorage).some((k) => k.startsWith('vero.requests.seen.')), undefined, { timeout: 15000 })
    const tourDoc = await tourCheckDocument(page)
    const asked = await page.request.post(`/api/v1/business/documents/${tourDoc}/access-requests`, { headers: dana, data: { kind: 'PRINT', reason: 'Tour check (task 0030)' } })
    let requestId = asked.ok() ? (await asked.json() as { requestId: string }).requestId : ''
    if (!requestId) { // one is already waiting from an earlier run that stopped
      const mine = await (await page.request.get('/api/v1/business/access-requests', { headers: dana })).json() as { requestId: string; documentId: string; kind: string; status: string }[]
      requestId = mine.find((r) => r.documentId === tourDoc && r.kind === 'PRINT' && r.status === 'PENDING')!.requestId
    }
    expect((await page.request.post(`/api/v1/admin/access-requests/${requestId}/decline`, { headers: priya })).ok()).toBeTruthy()
    await reloadApp(page)
    const notice = page.getByRole('status', { name: 'Your requests' })
    await expect(notice).toContainText(`Print on ${TOUR_RECORD} declined`, { timeout: 15000 })
    await capture(page, testInfo, 'requester-notice')
    await notice.getByRole('button', { name: 'Dismiss' }).click()
    await reloadApp(page)
    await expect(page.getByRole('status', { name: 'Your requests' })).toHaveCount(0) // told once
    const los = (await searchSources(page)).find((s) => s.displayName === 'Loan Origination System')!
    await page.evaluate(([key, id]) => window.localStorage.setItem(key, id), [LAST_SOURCE_KEY, los.sourceId])
    await reloadApp(page)
    await areaTab(page, 'Records').click()
    await waitForBusinessSearchReady(page)
    await searchFor(page, TOUR_RECORD)
    await resultRows(page).filter({ hasText: TOUR_RECORD }).first().click()
    await page.getByRole('complementary', { name: 'Record inspector' }).getByRole('button', { name: /Open documents/ }).click()
    await page.getByRole('navigation', { name: 'Record documents' }).getByRole('button', { name: /Tour check/ }).click()
    const requestsTab = viewer(page).getByRole('tab', { name: /^Requests/ })
    await expect(requestsTab).toHaveAccessibleName(/1 new decision$/, { timeout: 15000 })
    await requestsTab.click()
    await expect(requestsTab).not.toHaveAccessibleName(/new decision/)
    await viewer(page).getByRole('button', { name: /^Back to/ }).click()
    // Whole-contract requests show with the documents of their record, marked "Whole contract".
    await searchFor(page, '300000000002')
    await resultRows(page).filter({ hasText: '300000000002' }).first().click()
    await page.getByRole('complementary', { name: 'Record inspector' }).getByRole('button', { name: /Open documents/ }).click()
    await expect(page.getByRole('img', { name: /Document page 1/ })).toBeVisible({ timeout: 20000 })
    await viewer(page).getByRole('tab', { name: /^Requests/ }).click()
    await expect(viewer(page).getByRole('complementary', { name: 'Document information' }).getByRole('listitem').filter({ hasText: 'Whole contract' }).first()).toContainText('Lower contract to Confidential')
    await viewer(page).getByRole('button', { name: /^Back to/ }).click()

    // 4. Audit filter by level: only events that recorded the level; a value that names no level is refused, coded.
    await selectRole(page, 'Platform Operations Administrator')
    await page.evaluate(() => { window.location.hash = '#/operations/audit' })
    const asked2 = page.waitForRequest((r) => r.url().includes('/api/v1/operations/audit-events?') && r.url().includes('sensitivityLevel=HIGHLY_CONFIDENTIAL'))
    await page.getByLabel('Sensitivity').selectOption({ label: 'Highly Confidential' })
    await asked2
    const ops = as('local-platform-operations-admin', 'PLATFORM_OPERATIONS_ADMIN')
    const hc = await (await page.request.get('/api/v1/operations/audit-events?limit=200&sensitivityLevel=HIGHLY_CONFIDENTIAL', { headers: ops })).json() as { events: { safeDetailsJson: string }[] }
    expect(hc.events.length, 'the seed made Highly Confidential requests').toBeGreaterThan(0)
    expect(hc.events.every((e) => /"(level|sensitivityLevel)":\s*"HIGHLY_CONFIDENTIAL"/.test(e.safeDetailsJson))).toBeTruthy()
    const bad = await page.request.get('/api/v1/operations/audit-events?sensitivityLevel=highly', { headers: ops })
    expect(bad.status()).toBe(400)
    expect((await bad.json() as { code: string }).code).toBe('SDV-SEN-019')
    await capture(page, testInfo, 'audit-highly-confidential')
  })

  // Task 0031 (section 0): "Decided recently" comes from the server (every Business admin's decisions, here one made
  // outside this browser), the expiry note shows the tenant's own window, and an opening records the document's level.
  test('Decided recently, the expiry note and levels on openings (task 0031)', async ({ page }, testInfo) => {
    test.skip(!isDesktop(testInfo), 'Runs once per tour (desktop only).')
    test.setTimeout(120_000)
    const TENANT = '11111111-1111-1111-1111-111111111111'
    const as = (subject: string, roles: string) => ({ 'X-SDV-Tenant-Id': TENANT, 'X-SDV-Subject': subject, 'X-SDV-Roles': roles, 'Content-Type': 'application/json' })
    const dana = as('local-restricted-steward', 'RESTRICTED_STEWARD'), priya = as('local-business-admin', 'BUSINESS_ADMIN')

    // A decision made through the API, not on this screen: the drawer must still list it. On the tour's own document
    // (task 0035), so no demo document's history grows.
    const tourDoc = await tourCheckDocument(page)
    const asked = await page.request.post(`/api/v1/business/documents/${tourDoc}/access-requests`, { headers: dana, data: { kind: 'DOWNLOAD', reason: 'Tour check (task 0031)' } })
    let requestId = asked.ok() ? (await asked.json() as { requestId: string }).requestId : ''
    if (!requestId) { // one is already waiting from an earlier run that stopped
      const mine = await (await page.request.get('/api/v1/business/access-requests', { headers: dana })).json() as { requestId: string; documentId: string; kind: string; status: string }[]
      requestId = mine.find((r) => r.documentId === tourDoc && r.kind === 'DOWNLOAD' && r.status === 'PENDING')!.requestId
    }
    expect((await page.request.post(`/api/v1/admin/access-requests/${requestId}/decline`, { headers: priya })).ok()).toBeTruthy()
    const decided = await (await page.request.get('/api/v1/admin/access-requests/decided', { headers: priya })).json() as { requestId: string; decision: string; decidedBy: string; reason?: string }[]
    expect(decided[0].requestId, 'newest first').toBe(requestId)
    expect(decided[0].decision).toBe('DECLINE')
    expect(JSON.stringify(decided)).not.toContain('Tour check') // never a reason
    const list = await (await page.request.get('/api/v1/admin/access-requests', { headers: priya })).json() as { pendingExpiryDays: number }

    await selectRole(page, 'Business Administrator')
    await page.evaluate(() => { window.location.hash = '#/governance/approvals' })
    await expect(page.getByRole('region', { name: 'Waiting requests' })).toBeVisible({ timeout: 15000 })
    await expect(page.getByText(`requests nobody decides expire after ${list.pendingExpiryDays} day${list.pendingExpiryDays === 1 ? '' : 's'}`)).toBeVisible()
    await page.getByRole('button', { name: 'Decided recently ›' }).click()
    const drawer = page.getByRole('dialog', { name: 'Decided recently' })
    await expect(drawer).toContainText('Decisions by every Business admin in the last 30 days')
    await expect(drawer.getByRole('listitem').first()).toContainText(`Declined · Download on ${TOUR_RECORD}`, { timeout: 15000 })
    await expect(drawer.getByRole('listitem').first()).toContainText(/ · by .+ · /)
    expect(await drawer.innerText()).not.toMatch(/Tour check|\.pdf/)
    await capture(page, testInfo, 'approvals-decided-recently')

    // An opening from now on records the document's effective level, so the audit's level filter finds it.
    const opened = await page.request.get(`/api/v1/business/documents/${tourDoc}/viewer`, { headers: dana })
    expect(opened.ok()).toBeTruthy()
    const viewingId = (await opened.json() as { viewingId: string }).viewingId
    const ops = as('local-platform-operations-admin', 'PLATFORM_OPERATIONS_ADMIN')
    let levelOfOpening: string | null = null
    for (const level of ['STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL']) {
      const events = await (await page.request.get(`/api/v1/operations/audit-events?limit=200&sensitivityLevel=${level}`, { headers: ops })).json() as { events: { auditEventId: string; action: string }[] }
      if (events.events.some((e) => e.auditEventId === viewingId)) levelOfOpening = level
    }
    expect(levelOfOpening, 'the opening is found by the level filter under its level').not.toBeNull()
  })

  test('Reports: the Daily intake report, refusals counted, CSV and who may see it (task 0032)', async ({ page }, testInfo) => {
    test.skip(!isDesktop(testInfo), 'Runs once per tour (desktop only).')
    test.setTimeout(120_000)
    const TENANT = '11111111-1111-1111-1111-111111111111'
    const as = (subject: string, roles: string) => ({ 'X-SDV-Tenant-Id': TENANT, 'X-SDV-Subject': subject, 'X-SDV-Roles': roles })
    const priya = as('local-business-admin', 'BUSINESS_ADMIN')
    type Report = { from: string; to: string; today: string; timeZone: string; sendingSystems: { sourceId: string; displayName: string }[]; totals: { deliveries: number; refused: number; refusedCodes: { code: string; count: number }[] }; systems: { sourceId: string; displayName: string; totals: { refused: number } }[] }
    const report = async (query = '') => {
      const r = await page.request.get('/api/v1/admin/reports/intake' + query, { headers: priya })
      expect(r.ok(), 'the Business admin reads the report').toBeTruthy()
      return await r.json() as Report
    }

    // Business admin only: every other role gets SDV-RPT-001, for the report and its CSV.
    for (const [subject, roles] of [['local-business-user', 'BUSINESS_USER'], ['local-document-steward', 'DOCUMENT_STEWARD'], ['local-restricted-steward', 'RESTRICTED_STEWARD'], ['local-tenant-it-admin', 'TENANT_IT_ADMIN'], ['local-platform-operations-admin', 'PLATFORM_OPERATIONS_ADMIN']]) {
      for (const path of ['/api/v1/admin/reports/intake', '/api/v1/admin/reports/intake/export']) {
        const refused = await page.request.get(path, { headers: as(subject, roles) })
        expect(refused.status(), `${roles} on ${path}`).toBe(403)
        expect((await refused.json() as { code: string }).code).toBe('SDV-RPT-001')
      }
    }
    // Unclear filters are refused with their codes; intake notices are for stewards.
    const badRange = await page.request.get('/api/v1/admin/reports/intake?from=2026-01-01&to=2026-03-01', { headers: priya })
    expect([badRange.status(), (await badRange.json() as { code: string }).code]).toEqual([400, 'SDV-RPT-002'])
    const badSource = await page.request.get('/api/v1/admin/reports/intake?sourceId=00000000-0000-0000-0000-000000000000', { headers: priya })
    expect((await badSource.json() as { code: string }).code).toBe('SDV-RPT-003')
    expect((await (await page.request.get('/api/v1/business/intake-notices', { headers: priya })).json() as { code: string }).code).toBe('SDV-INT-001')
    expect((await page.request.get('/api/v1/business/intake-notices', { headers: as('local-document-steward', 'DOCUMENT_STEWARD') })).ok()).toBeTruthy()

    // A delivery refused before any submission exists is counted, with its code and request ID. (One INTAKE_REFUSED
    // audit event in the dev database per run; nothing is stored.)
    const before = await report()
    const los = before.sendingSystems.find((s) => s.displayName === 'Loan Origination System')
    expect(los, 'the seed\'s Loan Origination System').toBeTruthy()
    const requestId = `tour-0032-${Date.now()}`
    const sent = await page.request.post('/api/v1/ingestion/submissions', {
      headers: { 'X-SDV-Tenant-Id': TENANT, 'X-SDV-Source-Id': los!.sourceId, 'X-SDV-Subject': 'local-demo-producer', 'Content-Type': 'application/json' },
      data: { sourceSubmissionId: requestId, expectedDocumentCount: 1, metadata: {}, recordSensitivityLevel: 'secret' },
    })
    expect(sent.status()).toBe(400)
    expect((await sent.json() as { code: string }).code).toBe('SDV-SEN-002')
    const after = await report()
    expect(after.totals.refused).toBe(before.totals.refused + 1)
    expect(after.totals.deliveries).toBe(before.totals.deliveries + 1)
    expect(after.totals.refusedCodes.find((c) => c.code === 'SDV-SEN-002')?.count).toBe((before.totals.refusedCodes.find((c) => c.code === 'SDV-SEN-002')?.count ?? 0) + 1)
    expect(after.systems.find((s) => s.sourceId === los!.sourceId)!.totals.refused).toBeGreaterThanOrEqual(1)

    // The screen: tab last, the board's parts, counts only.
    await selectRole(page, 'Business Administrator')
    await page.evaluate(() => { window.location.hash = '#/governance/reports' })
    const table = page.getByRole('region', { name: 'Deliveries by sending system' })
    await expect(table).toBeVisible({ timeout: 15000 })
    await expect(page.getByRole('heading', { level: 1, name: 'Reports' })).toBeVisible()
    await expect(page.getByText('Counts only. No document contents, titles or names.')).toBeVisible()
    // Task 0033: the Sensitivity sub-tab is there now (it was disabled in task 0032).
    await expect(page.getByRole('tablist', { name: 'Reports' }).getByRole('tab', { name: 'Sensitivity' })).toBeEnabled()
    await expect(page.getByText('Deliveries refused before Vero could identify the sending system (for example a failed sign-in) are not counted here.')).toBeVisible()
    await expect(page.getByRole('button', { name: /^Day Today, / })).toBeVisible()
    await expect(page.getByText(/ \(entity\)$/)).toHaveAttribute('title', new RegExp(after.timeZone.replace('/', '\\/')))
    await expect(page.locator('.rpt-kpi').filter({ hasText: 'Refused at intake' }).locator('b')).toHaveText(after.totals.refused.toLocaleString('en-US'))
    await expect(table.getByRole('row').filter({ hasText: 'All sending systems' })).toBeVisible()
    // The Loan Origination System group: open it (if collapsed); the new refusal is its newest line, or in the CSV.
    const toggle = table.getByRole('button', { name: /^(Expand|Collapse) Loan Origination System$/ })
    if ((await toggle.getAttribute('aria-expanded')) === 'false') await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await capture(page, testInfo, 'reports-daily-intake')
    expect(await page.locator('.v-main').innerText()).not.toMatch(/\.pdf/i)
    await toggle.click()
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')

    // One sending system, then yesterday, then back to today.
    await page.getByLabel('Sending system', { exact: true }).selectOption({ label: 'Loan Origination System' })
    await expect(table.locator('tr.sys')).toHaveCount(1, { timeout: 15000 })
    await page.getByRole('button', { name: /^Day / }).click()
    await page.getByRole('dialog', { name: 'Choose the days' }).getByRole('button', { name: 'Yesterday' }).click()
    await expect(page.getByRole('button', { name: /^Day Yesterday, / })).toBeVisible({ timeout: 15000 })
    await page.getByRole('button', { name: /^Day / }).click()
    await page.getByRole('dialog', { name: 'Choose the days' }).getByRole('button', { name: 'Today' }).click()
    await expect(page.getByRole('button', { name: /^Day Today, / })).toBeVisible({ timeout: 15000 })

    // The CSV: one line per document, with the refusal; downloaded by the screen and audited by the server.
    const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Export CSV' }).click()])
    expect(download.suggestedFilename()).toBe(`vero-daily-intake-${after.today}.csv`)
    const csv = fs.readFileSync(await download.path() as string, 'utf8')
    expect(csv.split('\r\n')[0]).toBe(`﻿SHA-256 checksum,Request ID,Sending system,Primary identifier,Pages,"Received (${after.timeZone})",Received (UTC),Outcome,Refusal code`)
    expect(csv).toContain(`"${requestId}","Loan Origination System","","",`)
    expect(csv).toMatch(new RegExp(`"${requestId}".*"REFUSED","SDV-SEN-002"`))
    expect(csv).not.toMatch(/\.pdf/i)
    await expect(page.getByText('The file is saved. The export is recorded in the audit.')).toBeVisible()
  })

  test('Reports: the Sensitivity report, attempts by role, CSV and who may see it; stored steward notices (task 0033)', async ({ page }, testInfo) => {
    test.skip(!isDesktop(testInfo), 'Runs once per tour (desktop only).')
    test.setTimeout(120_000)
    const TENANT = '11111111-1111-1111-1111-111111111111'
    const as = (subject: string, roles: string) => ({ 'X-SDV-Tenant-Id': TENANT, 'X-SDV-Subject': subject, 'X-SDV-Roles': roles })
    const priya = as('local-business-admin', 'BUSINESS_ADMIN')
    const dee = as('local-document-steward', 'DOCUMENT_STEWARD')
    type Sens = { from: string; to: string; today: string; timeZone: string; documents: { total: number; highlyConfidential: number }; requests: { kind: string }[]; attempts: { roles: string[]; attempts: number; people: number }[] }
    const sens = async (query = '') => {
      const r = await page.request.get('/api/v1/admin/reports/sensitivity' + query, { headers: priya })
      expect(r.ok(), 'the Business admin reads the Sensitivity report').toBeTruthy()
      return await r.json() as Sens
    }
    const code = async (r: { json: () => Promise<unknown>; status: () => number }) => [r.status(), (await r.json() as { code: string }).code]

    // Business admin only: every other role gets SDV-RPT-001, for the report and its CSV.
    for (const [subject, roles] of [['local-business-user', 'BUSINESS_USER'], ['local-document-steward', 'DOCUMENT_STEWARD'], ['local-restricted-steward', 'RESTRICTED_STEWARD'], ['local-tenant-it-admin', 'TENANT_IT_ADMIN'], ['local-platform-operations-admin', 'PLATFORM_OPERATIONS_ADMIN']]) {
      for (const path of ['/api/v1/admin/reports/sensitivity', '/api/v1/admin/reports/sensitivity/export']) {
        expect(await code(await page.request.get(path, { headers: as(subject, roles) })), `${roles} on ${path}`).toEqual([403, 'SDV-RPT-001'])
      }
    }
    expect(await code(await page.request.get('/api/v1/admin/reports/sensitivity?from=2025-01-01&to=2026-11-01', { headers: priya }))).toEqual([400, 'SDV-RPT-002'])
    expect(await code(await page.request.get('/api/v1/admin/reports/sensitivity?sourceId=00000000-0000-0000-0000-000000000000', { headers: priya }))).toEqual([400, 'SDV-RPT-003'])

    // Section 0.1: notices are stored; marking needs real references of this tenant (coded otherwise).
    const notices = await (await page.request.get('/api/v1/business/intake-notices', { headers: dee })).json() as { notices: unknown[]; unread: number }
    expect(Array.isArray(notices.notices) && typeof notices.unread === 'number').toBeTruthy()
    expect(await code(await page.request.post('/api/v1/business/intake-notices/read', { headers: dee, data: { noticeIds: ['00000000-0000-0000-0000-000000000000'] } }))).toEqual([404, 'SDV-INT-002'])
    expect(await code(await page.request.post('/api/v1/business/intake-notices/read', { headers: dee, data: { noticeIds: [] } }))).toEqual([400, 'SDV-INT-003'])
    expect(await code(await page.request.post('/api/v1/business/intake-notices/read', { headers: priya, data: { noticeIds: [] } }))).toEqual([403, 'SDV-INT-001'])

    // A real attempt: a Business user tries to open the tour's own Highly Confidential document (task 0035). It is
    // refused (SDV-SEN-008) and counted under Business user, with no name in the report. (One DOCUMENT_ACCESS_REFUSED
    // audit event in the dev database per run.)
    const tourDoc = await tourCheckDocument(page)
    const before = await sens()
    expect(await code(await page.request.get(`/api/v1/business/documents/${tourDoc}/viewer`, { headers: as('local-business-user', 'BUSINESS_USER') }))).toEqual([403, 'SDV-SEN-008'])
    const after = await sens()
    const businessUser = (r: Sens) => r.attempts.find((a) => a.roles.join(',') === 'BUSINESS_USER')
    expect(businessUser(after)!.attempts).toBe((businessUser(before)?.attempts ?? 0) + 1)
    expect(businessUser(after)!.people).toBeGreaterThanOrEqual(1)
    expect(after.documents.highlyConfidential).toBeGreaterThanOrEqual(1)
    expect(after.requests.map((r) => r.kind)).toEqual(['DOWNLOAD', 'PRINT', 'LOWER_LEVEL', 'LOWER_RECORD_LEVEL'])
    expect(after.from).toBe(after.today.slice(0, 8) + '01')

    // The screen: the Sensitivity sub-tab with the board's four cards, this month, counts only.
    await selectRole(page, 'Business Administrator')
    await page.evaluate(() => { window.location.hash = '#/governance/reports' })
    await page.getByRole('tablist', { name: 'Reports' }).getByRole('tab', { name: 'Sensitivity' }).click()
    const levels = page.getByRole('region', { name: 'Documents by level' })
    await expect(levels).toBeVisible({ timeout: 15000 })
    for (const name of ['Requests', 'Level changes', 'Attempts to open restricted documents']) await expect(page.getByRole('region', { name })).toBeVisible()
    await expect(page.getByRole('button', { name: /^Period This month \(/ })).toBeVisible()
    await expect(levels.getByText(/carr(y|ies) a \w+ level \(all their documents count at that level\)\. On legal hold: /)).toBeVisible()
    const attempts = page.getByRole('region', { name: 'Attempts to open restricted documents' })
    await expect(attempts.getByRole('row').filter({ hasText: /^Business user/ }).getByRole('cell').nth(1)).toHaveText(businessUser(after)!.attempts.toLocaleString('en-US'))
    await expect(attempts.getByText('Who and when is in the audit trail (filter by level).')).toBeVisible()
    await capture(page, testInfo, 'reports-sensitivity')
    const text = await page.locator('.v-main').innerText()
    expect(text).not.toMatch(/\.pdf/i)
    expect(text).not.toContain('local-business-user')

    // One sending system, then last month, then back to this month.
    await page.getByLabel('Sending system', { exact: true }).selectOption({ label: 'Loan Origination System' })
    await expect(page.getByLabel('Sending system', { exact: true })).toHaveValue(/.+/)
    await page.getByRole('button', { name: /^Period / }).click()
    await page.getByRole('dialog', { name: 'Choose the period' }).getByRole('button', { name: 'Last month' }).click()
    await expect(page.getByRole('button', { name: /^Period Last month \(/ })).toBeVisible({ timeout: 15000 })
    await expect(page.getByRole('region', { name: 'Requests' }).locator('.rpt-ch span')).toHaveText('last month')
    await page.getByRole('button', { name: /^Period / }).click()
    await page.getByRole('dialog', { name: 'Choose the period' }).getByRole('button', { name: 'This month' }).click()
    await expect(page.getByRole('button', { name: /^Period This month \(/ })).toBeVisible({ timeout: 15000 })
    await page.getByLabel('Sending system', { exact: true }).selectOption({ label: 'All' })

    // The CSV: every count with its period; downloaded by the screen and audited by the server.
    const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('button', { name: 'Export CSV' }).click()])
    expect(download.suggestedFilename()).toBe(`vero-sensitivity-${after.from}-to-${after.to}.csv`)
    const csv = fs.readFileSync(await download.path() as string, 'utf8')
    expect(csv.split('\r\n')[0]).toBe('﻿Section,Line,Measure,Count,From,To,Time zone,Sending system')
    expect(csv).toContain(`"Attempts to open restricted documents","Business user","Attempts",`)
    expect(csv).toContain(`"${after.timeZone}","All"`)
    expect(csv).not.toMatch(/\.pdf/i)
    await expect(page.getByText('The file is saved. The export is recorded in the audit.')).toBeVisible()
  })

  // Task 0030: approve 50 waiting requests in one go. Opt-in, because it uses up the seed's 50 waiting print and
  // download requests (run Seed-SDV-Canvas-Demo.ps1 again afterwards to make them again).
  test('Bulk approval of the 50 waiting print and download requests (task 0030)', async ({ page }, testInfo) => {
    test.skip(process.env.SDV_BULK_APPROVAL_REGRESSION !== '1', 'Opt-in: set SDV_BULK_APPROVAL_REGRESSION=1 (approves the seed\'s 50 waiting requests)')
    test.skip(!isDesktop(testInfo), 'Desktop only.')
    test.setTimeout(180_000)
    await selectRole(page, 'Business Administrator')
    await page.evaluate(() => { window.location.hash = '#/governance/approvals' })
    const list = page.getByRole('region', { name: 'Waiting requests' })
    await expect(list).toBeVisible({ timeout: 15000 })
    const chips = page.getByRole('toolbar', { name: 'Approvals filters' })
    // Downloads, then Prints: each group's tick selects its requests of that kind; the selection is kept between chips.
    for (const chip of [/^Downloads \d+$/, /^Prints \d+$/]) {
      await chips.getByRole('button', { name: chip }).click()
      const boxes = list.getByRole('checkbox', { name: /^Select all on / })
      for (let i = 0; i < await boxes.count(); i++) if (await boxes.nth(i).isEnabled()) await boxes.nth(i).check()
    }
    const bar = page.getByRole('region', { name: 'Selected requests' })
    await expect(bar).toContainText('50 selected')
    await capture(page, testInfo, 'approvals-bulk-50')
    const sent = page.waitForResponse((r) => r.url().endsWith('/api/v1/admin/access-requests/decisions') && r.request().method() === 'POST')
    await bar.getByRole('button', { name: 'Approve 50' }).click()
    const body = await (await sent).json() as { decided: number; notDecided: number }
    expect(body).toEqual(expect.objectContaining({ decided: 50, notDecided: 0 }))
    await expect(page.getByText('50 requests approved')).toBeVisible()
    await chips.getByRole('button', { name: /^All \d+$/ }).click()
    await expect(chips.getByRole('button', { name: /^Downloads 0$/ })).toBeVisible({ timeout: 15000 })
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
    // Task 0018 follow-up: the record's document list now says the document was redacted.
    await reloadApp(page)
    await openViewerFor(page, redactionRecord)
    await expect(page.getByRole('navigation', { name: 'Record documents' })).toContainText('redacted', { timeout: 15000 })
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
      'Legal holds',
      'Search fields',
      'Approvals',
      'Reports',
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

  test('Without a session the app shows the error page, never sample data (task 0021)', async ({ page }) => {
    // The server refuses the session (simulated in the browser only).
    await page.route('**/api/v1/session', (route) => route.fulfill({ status: 401, json: { message: 'Not signed in' } }))
    await openApp(page, '/')
    await expect(page.getByRole('alert')).toContainText(/Your session didn.t start/, { timeout: 15000 })
    await expect(page.getByRole('main')).not.toContainText(/Records|Recently viewed|Needs attention|Simulated|simulated/)
    await expect(page.getByRole('banner')).not.toContainText(/Simulated|R7 · business integration/)
    // The old R5 address now opens Vero too: the old app is gone.
    await openApp(page, '/?app=r5')
    await expect(page.getByRole('alert')).toContainText(/Your session didn.t start/, { timeout: 15000 })
    await expect(page.locator('.auth-shell, .auth-card')).toHaveCount(0)
  })

  test('Quick search across all fields (task 0022)', async ({ page }, testInfo) => {
    test.skip(!isDesktop(testInfo), 'Runs once per tour (desktop only); it takes the 390px screenshots itself.')
    test.setTimeout(180_000)
    // The canvas's loan system (Seed-SDV-Canvas-Demo.ps1): 300000000004 Ana Gomez has co-borrower Priya Patel,
    // 300000000006 Sam Turner has closing agent Patel & Sons Title. Neither field is a Records column.
    const los = (await searchSources(page)).find((s) => s.displayName === 'Loan Origination System')
    expect(los, 'the Loan Origination System from Seed-SDV-Canvas-Demo.ps1').toBeTruthy()
    await page.evaluate(([key, id]) => window.localStorage.setItem(key, id), [LAST_SOURCE_KEY, los!.sourceId])
    await reloadApp(page)
    await selectRole(page, 'Business User')
    const shots = path.resolve(process.cwd(), '..', '..', '.runtime', 'ui-checks', '0022')
    fs.mkdirSync(shots, { recursive: true })
    /** 1440 and 390 px, light and dark, of the state on screen now. */
    const fourShots = async (name: string) => {
      for (const width of [1440, 390]) {
        await page.setViewportSize({ width, height: width === 1440 ? 900 : 844 })
        for (const theme of ['light', 'dark']) {
          await page.evaluate((t) => document.documentElement.setAttribute('data-theme', t), theme)
          await page.waitForTimeout(150)
          await page.screenshot({ path: path.join(shots, `${name}-${width}-${theme}.png`), animations: 'disabled' })
        }
      }
      await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'light'))
      await page.setViewportSize({ width: 1440, height: 1000 })
    }
    const list = page.getByRole('listbox', { name: 'Search suggestions' })
    const option = (name: RegExp) => list.getByRole('option', { name })

    // ---- Typing: the list opens after 2 characters, Search everywhere highlighted ----
    await areaTab(page, 'Records').click()
    await waitForBusinessSearchReady(page)
    await searchBox(page).fill('p')
    await expect(list).toHaveCount(0)
    await searchBox(page).fill('patel')
    await expect(list).toBeVisible()
    await expect(option(/^Search everywhere for .patel./)).toHaveAttribute('aria-selected', 'true')
    for (const name of [/^Borrower contains .patel./, /^Loan type is .patel./, /^Co-borrower contains .patel./, /^Closing agent starts with .patel./]) {
      await expect(option(name)).toBeVisible()
    }
    // Digits-only fields need digits; dates stay in Filters.
    await expect(option(/^Contract number/)).toHaveCount(0)
    await expect(option(/^Closing date/)).toHaveCount(0)
    await capture(page, testInfo, 'quick-search-typing')
    await fourShots('typing')
    await searchBox(page).focus()
    await expect(list).toBeVisible()

    // ---- Keyboard only: Escape closes the list, arrows move, Enter picks one field ----
    await searchBox(page).press('Escape')
    await expect(list).toHaveCount(0)
    await expect(searchBox(page)).toHaveValue('patel')
    await searchBox(page).press('ArrowDown')
    await expect(list).toBeVisible()
    for (let i = 0; i < 10 && (await option(/^Co-borrower contains/).getAttribute('aria-selected')) !== 'true'; i++) await searchBox(page).press('ArrowDown')
    await expect(option(/^Co-borrower contains/)).toHaveAttribute('aria-selected', 'true')
    await searchBox(page).press('Enter')
    await expect(chip(page, /^Co-borrower contains patel/)).toBeVisible()
    await expect(chip(page, /^Anywhere/)).toHaveCount(0)
    await expect(resultRows(page)).toHaveCount(1, { timeout: 15000 })
    await expect(resultRows(page).first()).toContainText('300000000004')

    // ---- Results: Enter searches everywhere; best match first; "Matched …" for fields that aren't columns ----
    await clearSearch(page)
    const sent = page.waitForRequest((r) => r.url().endsWith('/api/v1/business/search') && r.method() === 'POST' && (r.postDataJSON() as { anywhere?: string }).anywhere === 'patel')
    await searchFor(page, 'patel')
    expect(Object.keys((await sent).postDataJSON().criteria ?? {})).toHaveLength(0)
    await expect(chip(page, /^Anywhere patel/)).toBeVisible()
    const head = page.locator('.ws-results-head')
    await expect(head.getByRole('heading')).toContainText(/2 records · “patel” in any searchable field · Loan Origination System/)
    await expect(head).toContainText('Best match first')
    // Closing agent starts with "Patel" (ranks before contains): Sam Turner first, then Ana Gomez.
    await expect(resultRows(page).nth(0)).toContainText('300000000006')
    await expect(resultRows(page).nth(0)).toContainText('Matched Closing agent: Patel & Sons Title')
    await expect(resultRows(page).nth(1)).toContainText('Matched Co-borrower: Priya Patel')
    await expect(resultRows(page).nth(1).locator('mark')).toHaveText('Patel')
    await expect(page.locator('.ws-pager')).toContainText('Narrow it with Filters, or pick one field from the search list.')
    await resultRows(page).nth(1).click()
    const inspector = page.getByRole('complementary', { name: 'Record inspector' })
    await expect(inspector).toBeVisible({ timeout: 15000 })
    await expect(inspector.locator('.ws-kv div', { hasText: 'Co-borrower' }).locator('mark')).toHaveText('Patel', { timeout: 15000 })
    await capture(page, testInfo, 'quick-search-results')
    await fourShots('results')

    // ---- With a date range: Filters keep the "anywhere" text ----
    await page.getByRole('button', { name: /^Filters/ }).click()
    const filters = page.getByRole('dialog', { name: 'Filters' })
    await filters.getByRole('group', { name: 'Received quick ranges' }).getByRole('button', { name: 'Last 30 days' }).click()
    const withRange = page.waitForRequest((r) => r.url().endsWith('/api/v1/business/search') && r.method() === 'POST')
    await filters.getByRole('button', { name: 'Show results' }).click()
    const body = (await withRange).postDataJSON() as { anywhere?: string; dateFrom?: string }
    expect(body.anywhere).toBe('patel')
    expect(body.dateFrom).toBeTruthy()
    await expect(chip(page, /^Anywhere patel/)).toBeVisible()
    await expect(chip(page, /^Received /)).toBeVisible()
    await expect(page.getByRole('region', { name: 'Search results' }).locator('[aria-busy="true"]')).toHaveCount(0, { timeout: 15000 })
    await capture(page, testInfo, 'quick-search-with-date-range')

    // ---- By name, everywhere: the borrower's name is marked in the title ----
    await clearSearch(page)
    await searchFor(page, 'gomez')
    await expect(resultRows(page).first()).toContainText('300000000004', { timeout: 15000 })
    await expect(resultRows(page).first().locator('td.ws-name mark')).toHaveText('Gomez')

    // ---- A contract number lands first ----
    await clearSearch(page)
    await searchFor(page, '300000000004')
    await expect(chip(page, /^Anywhere 300000000004/)).toBeVisible()
    await expect(resultRows(page).first()).toContainText('300000000004', { timeout: 15000 })

    // ---- Follow-up: an exact text field ignores capitals ("mortgage" finds the 4 loans stored as "Mortgage") ----
    await clearSearch(page)
    await searchFor(page, 'mortgage')
    await expect(chip(page, /^Anywhere mortgage/)).toBeVisible()
    await expect(resultRows(page)).toHaveCount(4, { timeout: 15000 })
    await clearSearch(page)
    await searchBox(page).fill('mortgage')
    await option(/^Loan type is .mortgage./).click()
    await expect(chip(page, /^Loan type/)).toBeVisible()
    await expect(chip(page, /^Anywhere/)).toHaveCount(0)
    await expect(resultRows(page)).toHaveCount(4, { timeout: 15000 })
    for (const n of ['300000000001', '300000000003', '300000000005', '300000000007']) {
      await expect(resultRows(page).filter({ hasText: n })).toHaveCount(1)
    }
    // An identifier is still matched exactly: the contract number search above found exactly its own record.
    await clearSearch(page)
    await searchFor(page, '300000000004')
    await expect(resultRows(page).first()).toContainText('300000000004', { timeout: 15000 })

    // ---- A record opened recently, from the list (Home's Recently viewed; no new server call while typing) ----
    await resultRows(page).first().click()
    await page.getByRole('complementary', { name: 'Record inspector' }).getByRole('button', { name: /Open documents/ }).click()
    await expect(page.getByRole('img', { name: /Document page 1/ })).toBeVisible({ timeout: 20000 })
    await page.getByRole('button', { name: 'Back to results' }).click()
    await clearSearch(page)
    await areaTab(page, 'Home').click()
    await expect(page.getByRole('region', { name: 'Recently viewed' }).getByRole('listitem').first()).toContainText('300000000004', { timeout: 15000 })
    let recentCalls = 0
    page.on('request', (r) => { if (r.url().includes('/me/recent-records')) recentCalls++ })
    await searchBox(page).fill('gomez')
    const recentOption = option(/^300000000004/)
    await expect(list.getByText('Records you opened recently')).toBeVisible()
    await expect(recentOption).toBeVisible()
    await capture(page, testInfo, 'quick-search-recent')
    expect(recentCalls, 'typing on Home uses the Recently viewed data Home already has').toBe(0)
    await recentOption.click()
    await expect(areaTab(page, 'Records')).toHaveAttribute('aria-current', 'page')
    await expect(page.getByRole('complementary', { name: 'Record inspector' })).toContainText('300000000004', { timeout: 15000 })
    // Follow-up: arriving on Records loads the list once, as Home does.
    await expect.poll(() => recentCalls, { message: 'Records loads Recently viewed once per visit' }).toBe(1)
    await page.waitForTimeout(500)
    expect(recentCalls, 'only once per visit').toBe(1)

    // ---- Follow-up: opening Records directly (Home never shown) still offers recently opened matches ----
    let directCalls = 0
    page.on('request', (r) => { if (r.url().includes('/me/recent-records')) directCalls++ })
    // A fresh page load straight onto Records (the page is already on #/workspace, so a hash change alone wouldn't load it).
    await page.goto('about:blank')
    await openApp(page, '/#/workspace/search')
    await expect(areaTab(page, 'Records')).toHaveAttribute('aria-current', 'page', { timeout: 15000 })
    await waitForBusinessSearchReady(page)
    await expect.poll(() => directCalls, { message: 'Records loads Recently viewed itself' }).toBe(1)
    await searchBox(page).fill('gomez')
    await expect(list.getByText('Records you opened recently')).toBeVisible({ timeout: 15000 })
    await expect(option(/^300000000004/)).toBeVisible()
    await capture(page, testInfo, 'quick-search-recent-records-direct')
    await page.waitForTimeout(500)
    expect(directCalls, 'once per visit; typing makes no further call').toBe(1)
    await searchBox(page).press('Escape')

    // ---- Phones: a full-width list ----
    await clearSearch(page)
    await page.setViewportSize({ width: 390, height: 844 })
    await searchBox(page).fill('patel')
    await expect(list).toBeVisible()
    const bar = await page.locator('.ws-bar').boundingBox()
    const box = await page.locator('.qs-list').boundingBox()
    expect(Math.abs((box?.width ?? 0) - (bar?.width ?? 0))).toBeLessThan(4)
    await searchBox(page).press('Escape')
    await page.setViewportSize({ width: 1440, height: 1000 })
    // The tour's other tests search LexaVault.
    await chooseLexaVault(page)
  })

  test('Tenant IT Administrator screens', async ({ page }, testInfo) => {
    test.setTimeout(240_000)
    await selectRole(page, 'Tenant IT Administrator')
    await expectNoRecordsAccess(page, testInfo, 'Tenant IT')
    await captureAccountMenu(page, testInfo)
    await captureSectionTabs(page, testInfo, 'Tenant IT sections', [
      'Storage',
      'Sign-in',
      'Integrations',
      'Problems',
      'About',
    ])
    await expect(page.getByRole('tablist', { name: 'Tenant IT sections' }).getByRole('tab', { name: 'Audit' })).toHaveCount(0)
    await problemsChecks(page, testInfo, 'Tenant IT')
    await integrationsChecks(page, testInfo)
    await storageChecks(page, testInfo)
    await signInChecks(page, testInfo)
    await aboutChecks(page, testInfo, 'tenant', 'environment', 'Jonas Weber')
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
    test.setTimeout(300_000)
    await selectRole(page, 'Platform Operations Administrator')
    await expectNoRecordsAccess(page, testInfo, 'Platform operations')
    await captureAccountMenu(page, testInfo)
    await captureSectionTabs(page, testInfo, 'Platform operations sections', [
      'Overview',
      'Problems',
      'Storage',
      'Health',
      'Audit',
      'About',
    ])
    await problemsChecks(page, testInfo, 'Platform operations')
    await auditChecks(page, testInfo)
    await overviewChecks(page, testInfo)
    await opsStorageChecks(page, testInfo)
    await healthChecks(page, testInfo)
    await aboutChecks(page, testInfo, 'operations', 'configuration', 'Aiko Tanaka')
  })
})
