import { expect, test } from '@playwright/test'

/**
 * Opt-in, non-destructive comment persistence diagnostic for the R7 UI.
 * Run only with SDV_COMMENT_REGRESSION=1; this creates one clearly tagged
 * test comment on an existing disposable local demo document.
 */
test('a document comment persists after leaving and reopening Viewer', async ({ page }) => {
  test.skip(process.env.SDV_COMMENT_REGRESSION !== '1', 'Opt-in test: creates a persistent diagnostic comment')
  const contract = process.env.SDV_DEMO_CONTRACT ?? '100000000001' // created by Seed-SDV-Demo-Records.ps1
  const marker = `R7-comment-persistence-${Date.now()}`
  const responses: Array<{ status: number; url: string; body?: string }> = []

  page.on('response', async (response) => {
    if (!response.url().includes('/api/v1/business/documents/') || !response.url().includes('/comments')) return
    const body = await response.text().catch(() => '')
    responses.push({ status: response.status(), url: response.url(), body: body.slice(0, 4000) })
  })

  try {
    await page.goto('/')
    // Switch to the Document Steward test role from the account menu (development builds, LOCAL mode only).
    const account = page.getByRole('banner').getByRole('button', { name: /^Account: / })
    const steward = 'Account: Local document steward, Document steward'
    await expect(account).toBeVisible({ timeout: 15000 })
    if ((await account.getAttribute('aria-label')) !== steward) {
      await account.click()
      const roleSelect = page.getByRole('dialog', { name: 'Account menu' }).getByLabel('Local test role')
      await Promise.all([page.waitForEvent('load'), roleSelect.selectOption({ label: 'Document Steward' })])
    }
    await expect(account).toHaveAttribute('aria-label', steward, { timeout: 15000 })

    const search = page.getByRole('region', { name: 'Search records' })
    await expect(search.getByLabel(/Contract Number/i).first()).toBeVisible({ timeout: 15000 })
    await search.getByLabel(/Contract Number/i).first().fill(contract)
    await search.getByRole('button', { name: 'Search', exact: true }).click()
    const row = page.locator('.ws-rec').filter({ hasText: contract }).first()
    await expect(row).toBeVisible({ timeout: 15000 })

    // Wide screens show the record in a side inspector; narrower ones in a drawer.
    const inspector = page.getByRole('complementary', { name: 'Record inspector' }).or(page.getByRole('dialog', { name: contract }))
    const openViewer = async () => {
      // The record stays selected after returning from the viewer; clicking it again would close the inspector.
      if (!(await inspector.isVisible().catch(() => false))) await row.click()
      await inspector.getByRole('button', { name: /Open documents/ }).click()
      await expect(page.getByRole('img', { name: /Document page 1/ })).toBeVisible({ timeout: 15000 })
      await page.getByRole('tablist', { name: 'Document information' }).getByRole('tab', { name: /^Comments/ }).click()
    }

    await openViewer()
    await page.getByLabel('New comment').fill(marker)
    await page.getByRole('button', { name: 'Add comment', exact: true }).click()
    await expect(page.getByText(marker)).toBeVisible({ timeout: 15000 })

    await page.getByRole('button', { name: 'Back to results' }).click()
    await expect(row).toBeVisible()
    await openViewer()
    await expect(page.getByText(marker)).toBeVisible({ timeout: 15000 })
  } finally {
    await test.info().attach('comments-network.json', {
      body: JSON.stringify({ contract, marker, responses }, null, 2),
      contentType: 'application/json',
    })
  }
})
