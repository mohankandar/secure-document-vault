import { expect, test, type Page, type TestInfo } from '@playwright/test'

const demoRecord = process.env.SDV_DEMO_CONTRACT ?? '300000000001'

async function selectRole(page: Page, role: string) {
  let roleSelect = page.getByLabel('Demo role')
  if (!(await roleSelect.isVisible().catch(() => false))) {
    const openNavigation = page.getByRole('button', { name: 'Open navigation' })
    if (await openNavigation.isVisible().catch(() => false)) {
      await openNavigation.click()
      roleSelect = page.getByLabel('Demo role')
    }
  }
  await expect(roleSelect).toBeVisible()
  await roleSelect.selectOption({ label: role })
  await expect(page.locator('.sidebar-identity strong')).not.toHaveText('Local user')
  await page.waitForTimeout(150)
}

async function capture(page: Page, testInfo: TestInfo, name: string) {
  await page.screenshot({
    path: testInfo.outputPath(`${name}.png`),
    fullPage: true,
    animations: 'disabled',
  })
}

async function clickTab(page: Page, name: string, navigationLabel?: string) {
  const scope = navigationLabel ? page.getByLabel(navigationLabel) : page
  const tab = scope.getByRole('button', { name, exact: true })
  await expect(tab).toBeVisible()
  await tab.click()
  await page.waitForTimeout(250)
}

async function captureAdminTabs(page: Page, testInfo: TestInfo, tabs: string[], navigationLabel?: string) {
  for (const tab of tabs) {
    await clickTab(page, tab, navigationLabel)
    await capture(page, testInfo, tab.toLowerCase().replace(/[^a-z0-9]+/g, '-'))
  }
}

async function tryBusinessRecordJourney(page: Page, testInfo: TestInfo) {
  const sourceSelect = page.getByLabel('Search source')
  if (await sourceSelect.isVisible().catch(() => false)) {
    const options = await sourceSelect.locator('option').allTextContents()
    const preferred = options.find((option) => option.toLowerCase().includes('laxavault'))
      ?? options.find((option) => option.toLowerCase().includes('lexavault'))
    if (preferred) {
      await sourceSelect.selectOption({ label: preferred })
      await page.waitForTimeout(200)
    }
  }

  const contract = page.getByLabel(/Contract Number/i).first()
  if (!(await contract.isVisible().catch(() => false))) return

  await contract.fill(demoRecord)
  await page.getByRole('button', { name: 'Search records', exact: true }).click()
  await page.waitForTimeout(500)
  await capture(page, testInfo, 'results')

  const recordRow = page.locator('.business-result-table tbody tr').filter({ hasText: demoRecord }).first()
  if (!(await recordRow.isVisible().catch(() => false))) return

  await recordRow.click()
  await page.waitForTimeout(250)
  await capture(page, testInfo, 'record-inspector')

  const openDocument = page.getByRole('button', { name: /open document/i })
  if (!(await openDocument.isVisible().catch(() => false))) return

  await openDocument.click()
  await expect(page.locator('.viewer-page-canvas, .pdf-page-canvas, canvas').first()).toBeVisible({ timeout: 15000 })
  await page.waitForTimeout(300)
  await capture(page, testInfo, 'document-viewer')

  const activity = page.getByRole('button', { name: 'Activity', exact: true })
  if (await activity.isVisible().catch(() => false)) {
    await activity.click()
    await page.waitForTimeout(250)
    await capture(page, testInfo, 'activity')
  }
}

test.describe('SDV role-based screen capture', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/')
    await page.waitForLoadState('domcontentloaded')
  })

  test('Business User screens', async ({ page }, testInfo) => {
    await selectRole(page, 'Business User')
    await capture(page, testInfo, 'search')
    await tryBusinessRecordJourney(page, testInfo)
  })

  test('Document Steward screens', async ({ page }, testInfo) => {
    await selectRole(page, 'Document Steward')
    await capture(page, testInfo, 'search')
    await tryBusinessRecordJourney(page, testInfo)
  })

  test('Business Administrator screens', async ({ page }, testInfo) => {
    await selectRole(page, 'Business Administrator')
    await page.getByRole('button', { name: 'Administration', exact: true }).click()
    await captureAdminTabs(page, testInfo, [
      'Users & Access',
      'Roles & Permissions',
      'Retention Policies',
      'Metadata Configuration',
    ])
  })

  test('Tenant IT Administrator screens', async ({ page }, testInfo) => {
    await selectRole(page, 'Tenant IT Administrator')
    await page.getByRole('button', { name: 'Administration', exact: true }).click()
    await captureAdminTabs(page, testInfo, [
      'Storage',
      'Authentication',
      'Integrations',
      'Environment',
    ])
  })

  test('Platform Operations Administrator screens', async ({ page }, testInfo) => {
    await selectRole(page, 'Platform Operations Administrator')
    await page.getByRole('button', { name: 'Operations', exact: true }).click()
    await captureAdminTabs(page, testInfo, [
      'Overview',
      'Storage',
      'Platform Health',
      'Incidents',
      'Audit',
      'Configuration',
    ], 'Platform operations sections')
  })
})
