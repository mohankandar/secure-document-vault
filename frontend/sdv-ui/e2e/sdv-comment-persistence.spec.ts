import { expect, test } from '@playwright/test'

/**
 * Opt-in, non-destructive comment persistence diagnostic.
 * Run only with SDV_COMMENT_REGRESSION=1; this creates one clearly tagged
 * test comment on an existing disposable local demo document.
 */
test('a document comment persists after leaving and reopening Viewer', async ({ page }) => {
  test.skip(process.env.SDV_COMMENT_REGRESSION !== '1', 'Opt-in test: creates a persistent diagnostic comment')
  const contract = process.env.SDV_DEMO_CONTRACT ?? '300000000076'
  const marker = `R5-comment-persistence-${Date.now()}`
  const responses: Array<{ status: number; url: string; body?: string }> = []

  page.on('response', async (response) => {
    if (!response.url().includes('/api/v1/business/documents/') || !response.url().includes('/comments')) return
    const body = await response.text().catch(() => '')
    responses.push({ status: response.status(), url: response.url(), body: body.slice(0, 4000) })
  })

  try {
  await page.goto('/')
  const role = page.getByLabel('Demo role')
  await expect(role).toBeVisible()
  await role.selectOption({ label: 'Document Steward' })
  await expect(page.locator('.sidebar-identity strong')).toHaveText('local-document-steward', { timeout: 15000 })
  await expect(page.getByLabel('Search source')).toBeVisible({ timeout: 15000 })
  await expect(page.getByLabel(/Contract Number/i).first()).toBeVisible({ timeout: 15000 })

  await page.getByLabel(/Contract Number/i).first().fill(contract)
  await page.getByRole('button', { name: 'Search records', exact: true }).click()
  const row = page.locator('.business-result-table tbody tr').filter({ hasText: contract }).first()
  await expect(row).toBeVisible({ timeout: 15000 })
  await row.getByRole('button', { name: /View PDF for record/i }).click()
  await expect(page.locator('.viewer-page-stage img').first()).toBeVisible({ timeout: 15000 })

  await page.getByRole('button', { name: /^Comments/ }).click()
  await expect(page.getByLabel('Document information')).toBeVisible()
  await page.getByLabel('New comment').fill(marker)
  await page.getByRole('button', { name: 'Add comment', exact: true }).click()
  await expect(page.getByText(marker)).toBeVisible({ timeout: 15000 })

  await page.getByLabel('Business document workflow').getByRole('button', { name: 'Results', exact: false }).click()
  await expect(page.getByLabel('Selected record inspector')).toHaveCount(0)
  await row.getByRole('button', { name: /View PDF for record/i }).click()
  await expect(page.locator('.viewer-page-stage img').first()).toBeVisible({ timeout: 15000 })
  await page.getByRole('button', { name: /^Comments/ }).click()
  await expect(page.getByText(marker)).toBeVisible({ timeout: 15000 })
  } finally {
    await test.info().attach('comments-network.json', {
      body: JSON.stringify({ contract, marker, responses }, null, 2),
      contentType: 'application/json',
    })
  }
})
