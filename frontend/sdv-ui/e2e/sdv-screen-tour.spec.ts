import { expect, test, type Page, type TestInfo } from '@playwright/test'

// Screen tour of the R7 UI (served at /). Needs the app running locally in LOCAL security mode,
// where the account menu offers the development-only "Local test role" switch.

const demoRecord = process.env.SDV_DEMO_CONTRACT ?? '300000000001'

/** Readable names the account button shows for each local test role (never the technical subject). */
const ACCOUNT_BY_ROLE: Record<string, string> = {
  'Business User': 'Account: Local business user, Business user',
  'Document Steward': 'Account: Local document steward, Document steward',
  'Business Administrator': 'Account: Local business administrator, Business administrator',
  'Tenant IT Administrator': 'Account: Local Tenant IT administrator, Tenant IT administrator',
  'Platform Operations Administrator': 'Account: Local platform operations administrator, Platform operations administrator',
}
const AREA_TAB_BY_ROLE: Record<string, string> = {
  'Business User': 'Records',
  'Document Steward': 'Records',
  'Business Administrator': 'Business administration',
  'Tenant IT Administrator': 'Tenant IT',
  'Platform Operations Administrator': 'Platform operations',
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

async function waitForBusinessSearchReady(page: Page) {
  const search = page.getByRole('region', { name: 'Search records' })
  await expect(search.getByRole('button', { name: 'Search', exact: true })).toBeVisible({ timeout: 15000 })
  await expect(search.getByLabel('Source')).toBeVisible()
  return search
}

async function tryBusinessRecordJourney(page: Page, testInfo: TestInfo) {
  const search = await waitForBusinessSearchReady(page)
  const contract = search.getByLabel(/Contract Number/i).first()
  if (!(await contract.isVisible().catch(() => false))) {
    // No searchable source on this machine (e.g. an empty local database): record screens cannot be shown.
    testInfo.annotations.push({ type: 'skipped-journey', description: 'No searchable "Contract Number" field; record, inspector and viewer screens not captured.' })
    return
  }
  await contract.fill(demoRecord)
  await search.getByRole('button', { name: 'Search', exact: true }).click()
  const recordRow = page.locator('.ws-rec').filter({ hasText: demoRecord }).first()
  await expect(page.getByRole('region', { name: 'Search results' })).toBeVisible()
  await page.waitForTimeout(500)
  await capture(page, testInfo, 'results')
  if (!(await recordRow.isVisible().catch(() => false))) {
    testInfo.annotations.push({ type: 'skipped-journey', description: `Record ${demoRecord} not found; inspector and viewer screens not captured.` })
    return
  }

  await recordRow.click()
  const inspector = page.getByRole('complementary', { name: 'Record inspector' })
  await expect(inspector).toBeVisible({ timeout: 15000 })
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
    await captureAccountMenu(page, testInfo)
    await captureSectionTabs(page, testInfo, 'Tenant IT sections', [
      'Storage',
      'Authentication',
      'Integrations',
      'Environment',
    ])
  })

  test('Platform Operations Administrator screens', async ({ page }, testInfo) => {
    await selectRole(page, 'Platform Operations Administrator')
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
