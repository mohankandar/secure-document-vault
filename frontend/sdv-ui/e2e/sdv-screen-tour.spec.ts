import { expect, test, type Page, type TestInfo } from '@playwright/test'

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
  'Business User': 'Records',
  'Document Steward': 'Records',
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
  await expect(page.getByRole('banner').getByRole('navigation', { name: 'Areas' }).getByRole('button', { name: AREA_TAB_BY_ROLE[role], exact: true }))
    .toHaveAttribute('aria-current', 'page')
}

async function capture(page: Page, testInfo: TestInfo, name: string) {
  const screenshot = await page.screenshot({ path: testInfo.outputPath(`${name}.png`), fullPage: true, animations: 'disabled' })
  await testInfo.attach(name, { body: screenshot, contentType: 'image/png' })
}

/** Roles without SEARCH_RECORDS: no Records tab, and the Records address shows the access page. */
async function expectNoRecordsAccess(page: Page, testInfo: TestInfo) {
  await expect(page.getByRole('banner').getByRole('navigation', { name: 'Areas' }).getByRole('button', { name: 'Records', exact: true })).toHaveCount(0)
  const back = await page.evaluate(() => window.location.hash)
  await page.evaluate(() => { window.location.hash = '#/workspace/search' })
  await expect(page.getByRole('heading', { name: /This area isn.t assigned to your role/ })).toBeVisible({ timeout: 15000 })
  await expect(page.getByRole('searchbox', { name: 'Search records' })).toBeHidden()
  await capture(page, testInfo, 'records-not-assigned')
  await page.evaluate((h) => { window.location.hash = h }, back)
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

async function tryBusinessRecordJourney(page: Page, testInfo: TestInfo) {
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
    await waitForBusinessSearchReady(page)
    await capture(page, testInfo, 'search')
    await captureAccountMenu(page, testInfo)
    await tryBusinessRecordJourney(page, testInfo)
  })

  test('Document Steward screens', async ({ page }, testInfo) => {
    await selectRole(page, 'Document Steward')
    await waitForBusinessSearchReady(page)
    await capture(page, testInfo, 'search')
    await captureAccountMenu(page, testInfo)
    await tryBusinessRecordJourney(page, testInfo)
  })

  test('Business Administrator screens', async ({ page }, testInfo) => {
    await selectRole(page, 'Business Administrator')
    await expectNoRecordsAccess(page, testInfo)
    await captureAccountMenu(page, testInfo)
    await captureSectionTabs(page, testInfo, 'Business administration sections', [
      'Users & access',
      'Roles & permissions',
      'Retention policies',
      'Metadata configuration',
    ])
  })

  test('Tenant IT Administrator screens', async ({ page }, testInfo) => {
    await selectRole(page, 'Tenant IT Administrator')
    await expectNoRecordsAccess(page, testInfo)
    await captureAccountMenu(page, testInfo)
    await captureSectionTabs(page, testInfo, 'Tenant IT sections', [
      'Storage',
      'Authentication',
      'Integrations',
      'Environment',
    ])
  })

  test('Local Administrator with several roles sees every area', async ({ page }, testInfo) => {
    await selectRole(page, 'Local Administrator (Tenant IT + Business admin)')
    await expectNoRecordsAccess(page, testInfo)
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
    await selectRole(page, 'Platform Operations Administrator')
    await expectNoRecordsAccess(page, testInfo)
    await captureAccountMenu(page, testInfo)
    await captureSectionTabs(page, testInfo, 'Platform operations sections', [
      'Overview',
      'Intake',
      'Storage',
      'Platform health',
      'Incidents',
      'Audit',
      'Configuration',
    ])
  })
})
