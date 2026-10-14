import { expect, test, type Page, type TestInfo } from '@playwright/test'

const demoRecord = process.env.SDV_DEMO_CONTRACT ?? '300000000001'

async function ensureNavigationOpen(page: Page) {
  const roleSelect = page.getByLabel('Demo role')
  if (await roleSelect.isVisible().catch(() => false)) return

  const openNavigation = page.getByRole('button', { name: 'Open navigation' })
  if (await openNavigation.isVisible().catch(() => false)) {
    await openNavigation.click()
    await expect(page.getByLabel('Demo role')).toBeVisible()
  }
}

async function selectRole(page: Page, role: string) {
  await ensureNavigationOpen(page)
  const roleSelect = page.getByLabel('Demo role')
  await expect(roleSelect).toBeVisible()

  const selectedLabel = await roleSelect.locator('option:checked').textContent()
  if (selectedLabel?.trim() === role) {
    const alternate = await roleSelect.locator('option').evaluateAll((options, desired) => {
      const match = options.find((option) => option.textContent?.trim() !== desired) as HTMLOptionElement | undefined
      return match?.value ?? ''
    }, role)
    if (alternate) {
      await roleSelect.selectOption(alternate)
      await page.waitForTimeout(150)
      await ensureNavigationOpen(page)
    }
  }

  const currentRoleSelect = page.getByLabel('Demo role')
  await currentRoleSelect.selectOption({ label: role })
  await expect(currentRoleSelect.locator('option:checked')).toHaveText(role)
  await expect(page.locator('.sidebar-identity strong')).not.toHaveText('Local user')

  const workspaceByRole: Record<string, string> = {
    'Business Administrator': 'Business administration sections',
    'Tenant IT Administrator': 'Tenant IT administration sections',
    'Platform Operations Administrator': 'Platform operations sections',
  }
  const workspace = workspaceByRole[role]
  if (workspace) {
    await expect(page.getByLabel(workspace)).toBeVisible({ timeout: 15000 })
  } else {
    await expect(
      page.getByLabel('Business document workflow').getByRole('button', { name: 'Search', exact: true }),
    ).toBeVisible({ timeout: 15000 })
  }
}

async function navigatePrimary(page: Page, name: string, workspaceLabel?: string) {
  if (workspaceLabel && await page.getByLabel(workspaceLabel).isVisible().catch(() => false)) {
    return
  }

  let button = page.getByRole('button', { name, exact: true })
  if (!(await button.isVisible().catch(() => false))) {
    await ensureNavigationOpen(page)
    button = page.getByRole('button', { name, exact: true })
  }

  if (!(await button.isVisible().catch(() => false)) && workspaceLabel &&
      await page.getByLabel(workspaceLabel).isVisible().catch(() => false)) {
    return
  }

  await expect(button).toBeVisible()
  await button.click()
  await page.waitForTimeout(200)
}

async function capture(page: Page, testInfo: TestInfo, name: string) {
  const path = testInfo.outputPath(`${name}.png`)
  const screenshot = await page.screenshot({
    path,
    fullPage: true,
    animations: 'disabled',
  })
  await testInfo.attach(name, {
    body: screenshot,
    contentType: 'image/png',
  })
}

async function clickTab(page: Page, name: string, navigationLabel?: string) {
  const scope = navigationLabel ? page.getByLabel(navigationLabel) : page
  const tab = scope.getByRole('button', { name, exact: true })
  await expect(tab).toBeVisible()
  await tab.click()
  await page.waitForTimeout(250)
}

async function waitForAdminTabReady(page: Page, tab: string) {
  if (tab === 'Roles & Permissions') {
    await expect(page.getByText('Loading role permissions…')).toHaveCount(0, { timeout: 15000 })
    await expect(page.getByRole('button', { name: /Save permissions|Saving…/ })).toBeVisible({ timeout: 15000 })
    return
  }

  if (tab === 'Overview') {
    await expect(page.getByText('Loading operational health…')).toHaveCount(0, { timeout: 15000 })
    await expect(page.getByText('Overall', { exact: true })).toBeVisible({ timeout: 15000 })
    await expect(page.getByText('Loading', { exact: true })).toHaveCount(0, { timeout: 15000 })
    return
  }

  if (tab === 'Audit') {
    await expect(page.getByRole('button', { name: 'Apply filters', exact: true })).toBeVisible({ timeout: 15000 })
    await expect(page.getByRole('button', { name: 'Apply filters', exact: true })).toBeEnabled({ timeout: 15000 })
    return
  }

  await page.waitForTimeout(150)
}

async function captureAdminTabs(page: Page, testInfo: TestInfo, tabs: string[], navigationLabel?: string) {
  for (const tab of tabs) {
    await clickTab(page, tab, navigationLabel)
    await waitForAdminTabReady(page, tab)
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
  await expect(page.locator('.viewer-page-stage img').first()).toBeVisible({ timeout: 15000 })
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
    await expect(page.getByLabel('Business administration sections')).toBeVisible()
    await captureAdminTabs(page, testInfo, [
      'Users & Access',
      'Roles & Permissions',
      'Retention Policies',
      'Metadata Configuration',
    ])
  })

  test('Tenant IT Administrator screens', async ({ page }, testInfo) => {
    await selectRole(page, 'Tenant IT Administrator')
    await expect(page.getByLabel('Tenant IT administration sections')).toBeVisible()
    await captureAdminTabs(page, testInfo, [
      'Storage',
      'Authentication',
      'Integrations',
      'Environment',
    ])
  })

  test('Platform Operations Administrator screens', async ({ page }, testInfo) => {
    await selectRole(page, 'Platform Operations Administrator')
    await expect(page.getByLabel('Platform operations sections')).toBeVisible()
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
