import { test, expect, type Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * npm run ui:compare (task 0018): puts each approved canvas board next to the app in the same state.
 *
 * For each board it screenshots the canvas's app frame (`.screen`, the blue "Seen by" design notes hidden), puts the
 * app in the same role and state at the same size, screenshots it, and writes one side-by-side image
 * (canvas left, app right) to .runtime/ui-compare/<screen>.png. It changes no data except what opening a record and
 * a document already records (the audit). The app must be running (start-local.cmd).
 *
 * The canvas names no font source, so the IBM Plex fonts the app uses are added to it; otherwise the browser would
 * draw the canvas in a fallback font and every text line would differ for that reason alone.
 */

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..')
const OUT = resolve(REPO, '.runtime', 'ui-compare')
const MAIN_CANVAS = resolve(REPO, 'docs', 'design', 'vero-ui-after.html')
const FONTS = 'https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;500&family=IBM+Plex+Sans:wght@400;500;600;700&display=swap'

/** alone: the board file holds only the app frame (no label column beside it), so the window is the frame's own size. */
export type Board = { name: string; canvas: string; index: number; width: number; height: number; alone?: boolean }

async function canvasShot(page: Page, board: Board): Promise<Buffer> {
  // Room for the frame at full width beside the canvas's 360px label column: a narrower window squeezes the frame.
  await page.setViewportSize({ width: board.alone ? board.width : board.width + 600, height: board.height + 200 })
  await page.goto('file:///' + board.canvas.replace(/\\/g, '/'))
  await page.addStyleTag({ url: FONTS }).catch(() => undefined)
  await page.addStyleTag({ content: '.role, .tagpos { display: none !important }' })
  await page.evaluate(() => document.fonts.ready)
  const screen = page.locator('.screen').nth(board.index)
  await screen.scrollIntoViewIfNeeded()
  return screen.screenshot({ animations: 'disabled' })
}

async function appShot(page: Page, board: Board): Promise<Buffer> {
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(300)
  return page.screenshot({ animations: 'disabled', clip: { x: 0, y: 0, width: board.width, height: board.height } })
}

/** One image: canvas left, app right, each with a caption. */
async function sideBySide(page: Page, name: string, canvas: Buffer, app: Buffer, width: number, height: number) {
  mkdirSync(OUT, { recursive: true })
  const img = (b: Buffer) => 'data:image/png;base64,' + b.toString('base64')
  await page.setViewportSize({ width: width * 2 + 72, height: height + 72 })
  await page.setContent(`<html><body style="margin:0;background:#9aa3a6;font:600 16px system-ui;display:flex;gap:24px;padding:16px">
    <figure style="margin:0"><figcaption style="height:28px">Canvas: ${name}</figcaption><img src="${img(canvas)}" width="${width}" height="${height}"></figure>
    <figure style="margin:0"><figcaption style="height:28px">App: ${name}</figcaption><img src="${img(app)}" width="${width}" height="${height}"></figure></body></html>`)
  writeFileSync(resolve(OUT, `${name}.png`), await page.screenshot({ fullPage: true }))
  writeFileSync(resolve(OUT, `${name}.canvas.png`), canvas)
  writeFileSync(resolve(OUT, `${name}.app.png`), app)
}

// ---- putting the app in a canvas state ----

export const accountButton = (page: Page) => page.getByRole('banner').getByRole('button', { name: /^Account: / })

export async function asRole(page: Page, role: string, width: number, height: number) {
  await page.setViewportSize({ width, height })
  await page.goto('/')
  await expect(accountButton(page)).toBeVisible({ timeout: 15000 })
  // The account button names the role in words ("IT administrator"); switch only when it is another role.
  const ROLE_WORDS: Record<string, string> = { 'Business User': 'Business user', 'Document Steward': 'Document steward', 'Restricted Steward': 'Restricted steward', 'Business Administrator': 'Business administrator', 'IT Administrator': 'IT administrator', 'Platform Operations Administrator': 'Platform operations administrator' }
  if (!((await accountButton(page).getAttribute('aria-label')) ?? '').endsWith(', ' + (ROLE_WORDS[role] ?? role))) {
    await accountButton(page).click()
    await Promise.all([page.waitForEvent('load'), page.getByLabel('Local test role').selectOption({ label: role })])
    await expect(accountButton(page)).toBeVisible({ timeout: 15000 })
  }
  // Every compare run starts the same way: the canvas's loan system (Seed-SDV-Canvas-Demo.ps1) as the remembered choice.
  const schema = await (await page.request.get('/api/v1/business/search-schema', { headers: { 'X-SDV-Tenant-Id': 'bd960ee9-9d54-5053-9958-92aaa54893a6', 'X-SDV-Subject': 'local-business-user', 'X-SDV-Roles': 'BUSINESS_USER' } })).json() as { sources: { sourceId: string; displayName: string }[] }
  const remembered = schema.sources.find((s) => s.displayName === CANVAS_SOURCE) ?? schema.sources.find((s) => s.displayName === 'Redaction Service')
  if (remembered) await page.evaluate((id) => window.localStorage.setItem('vero.records.sourceId', id), remembered.sourceId)
  await page.evaluate(() => window.localStorage.setItem('sdv.r7.theme', 'light'))
  await page.reload()
  await expect(accountButton(page)).toBeVisible({ timeout: 15000 })
}

const area = (page: Page, name: string) => page.getByRole('banner').getByRole('navigation', { name: 'Areas' }).getByRole('button', { name: new RegExp(`^${name}( .+)?$`) })
const searchBox = (page: Page) => page.getByRole('combobox', { name: 'Search records' })
const rows = (page: Page) => page.getByRole('grid', { name: 'Records' }).locator('tbody tr')

/** The canvas's records, from Seed-SDV-Canvas-Demo.ps1: Marcus Lee (2 of 4 documents) and Jordan Ellis. */
const CANVAS_SOURCE = 'Loan Origination System'
const INCOMPLETE = '300000000003'
const RECORD = '300000000001'

export const MAIN_BOARDS: (Board & { setUp: (page: Page) => Promise<void> })[] = [
  { name: 'home', canvas: MAIN_CANVAS, index: 0, width: 1440, height: 900, setUp: async (page) => {
    await asRole(page, 'Business User', 1440, 900)
    await area(page, 'Home').click()
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await page.waitForLoadState('networkidle')
  } },
  { name: 'results', canvas: MAIN_CANVAS, index: 1, width: 1440, height: 900, setUp: async (page) => {
    await asRole(page, 'Business User', 1440, 900)
    await area(page, 'Records').click()
    await page.getByRole('button', { name: /^Filters/ }).click()
    const filters = page.getByRole('dialog', { name: 'Filters' })
    const updated = filters.getByRole('group', { name: /Last updated quick ranges/ })
    if (await updated.count()) await updated.getByRole('button', { name: 'Last 30 days' }).click()
    else await filters.getByRole('group', { name: /Received quick ranges/ }).getByRole('button', { name: 'Last 30 days' }).click()
    await filters.getByRole('button', { name: 'Show results' }).click()
    await expect(rows(page).first()).toBeVisible({ timeout: 15000 })
    const incomplete = rows(page).filter({ hasText: INCOMPLETE }).first()
    await (await incomplete.count() ? incomplete : rows(page).first()).click()
    await expect(page.getByRole('complementary', { name: 'Record inspector' })).toBeVisible({ timeout: 15000 })
    await page.waitForLoadState('networkidle')
  } },
  { name: 'filters', canvas: MAIN_CANVAS, index: 2, width: 1440, height: 900, setUp: async (page) => {
    await asRole(page, 'Business User', 1440, 900)
    await area(page, 'Records').click()
    await expect(searchBox(page)).toHaveAttribute('placeholder', /.+/)
    await page.getByRole('button', { name: /^Filters/ }).click()
    await expect(page.getByRole('dialog', { name: 'Filters' })).toBeVisible()
  } },
  { name: 'viewer', canvas: MAIN_CANVAS, index: 3, width: 1440, height: 900, setUp: async (page) => {
    await asRole(page, 'Document Steward', 1440, 900)
    await area(page, 'Records').click()
    await searchBox(page).fill(RECORD)
    await searchBox(page).press('Enter')
    await rows(page).filter({ hasText: RECORD }).first().click()
    await page.getByRole('complementary', { name: 'Record inspector' }).getByRole('button', { name: /Open documents/ }).click()
    await expect(page.getByRole('img', { name: /Document page 1/ })).toBeVisible({ timeout: 20000 })
    await page.waitForLoadState('networkidle')
  } },
  { name: 'account-menu', canvas: MAIN_CANVAS, index: 4, width: 1440, height: 560, setUp: async (page) => {
    await asRole(page, 'Business User', 1440, 560)
    await area(page, 'Home').click()
    await accountButton(page).click()
    await expect(page.getByRole('menu').or(page.getByRole('dialog', { name: /Account/ })).first()).toBeVisible()
  } },
]

// ---- Quick search boards (task 0022): docs/design/vero-quick-search-typing.html and -results.html ----
// The local seed has no Lena or Ava Patel; "patel" there finds 300000000004 (co-borrower Priya Patel, like the
// canvas's Omar Haddad) and 300000000006 (closing agent Patel & Sons Title, like Grace Lin).

const QUICK_TYPING = resolve(REPO, 'docs', 'design', 'vero-quick-search-typing.html')
const QUICK_RESULTS = resolve(REPO, 'docs', 'design', 'vero-quick-search-results.html')
const suggestions = (page: Page) => page.getByRole('listbox', { name: 'Search suggestions' })

export const QUICK_BOARDS: (Board & { setUp: (page: Page) => Promise<void> })[] = [
  { name: 'quick-search-typing', canvas: QUICK_TYPING, index: 0, width: 1440, height: 900, setUp: async (page) => {
    await asRole(page, 'Business User', 1440, 900)
    await area(page, 'Records').click()
    await searchBox(page).fill('patel')
    await expect(suggestions(page)).toBeVisible()
    await page.waitForLoadState('networkidle')
  } },
  { name: 'quick-search-results', canvas: QUICK_RESULTS, index: 0, width: 1440, height: 900, setUp: async (page) => {
    await asRole(page, 'Business User', 1440, 900)
    await area(page, 'Records').click()
    await searchBox(page).fill('patel')
    await searchBox(page).press('Enter')
    const row = rows(page).filter({ hasText: /Matched Co-borrower/ }).first()
    await expect(row).toBeVisible({ timeout: 15000 })
    await row.click()
    await expect(page.getByRole('complementary', { name: 'Record inspector' })).toBeVisible({ timeout: 15000 })
    await page.waitForLoadState('networkidle')
  } },
]

// ---- Admin boards (task 0019): docs/design/vero-admin-after.html ----

const ADMIN_CANVAS = resolve(REPO, 'docs', 'design', 'vero-admin-after.html')
/** Opens an admin page by its address, as the role that sees it on the canvas. */
const adminPage = (role: string, hash: string, height: number, then?: (page: Page) => Promise<void>) => async (page: Page) => {
  await asRole(page, role, 1440, height)
  await page.evaluate((h) => { window.location.hash = h }, hash)
  await page.waitForLoadState('networkidle')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible({ timeout: 15000 })
  if (then) await then(page)
  await page.waitForLoadState('networkidle')
}
const BA = 'Business Administrator', IT = 'IT Administrator', OPS = 'Platform Operations Administrator'

export const ADMIN_BOARDS: (Board & { setUp: (page: Page) => Promise<void> })[] = [
  { name: 'admin-integrations', index: 1, height: 900, setUp: adminPage(IT, '#/tenant/integrations', 900, async (page) => {
    await page.getByRole('region', { name: 'Sending systems' }).getByRole('button', { name: /Loan Origination System/ }).click()
  }) },
  { name: 'admin-problems', index: 2, height: 900, setUp: adminPage(OPS, '#/operations/problems', 900, async (page) => {
    await page.getByRole('table', { name: 'Problems list' }).locator('tbody tr').first().click()
    await expect(page.getByRole('dialog')).toBeVisible()
  }) },
  { name: 'admin-audit', index: 3, height: 860, setUp: adminPage(OPS, '#/operations/audit', 860) },
  { name: 'admin-roles', index: 4, height: 900, setUp: adminPage(BA, '#/governance/roles', 900) },
  { name: 'admin-tenant-storage', index: 5, height: 760, setUp: adminPage(IT, '#/tenant/storage', 760) },
  { name: 'admin-no-access', index: 6, height: 560, setUp: adminPage('Business User', '#/governance/access', 560) },
  { name: 'admin-people', index: 7, height: 760, setUp: adminPage(BA, '#/governance/access', 760) },
  { name: 'admin-keeping', index: 8, height: 820, setUp: adminPage(BA, '#/governance/retention', 820) },
  { name: 'admin-search-fields', index: 9, height: 900, setUp: adminPage(BA, '#/governance/metadata', 900, async (page) => {
    await page.getByLabel('Sending system').selectOption({ label: CANVAS_SOURCE })
    await page.getByRole('button', { name: 'Edit Borrower' }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
  }) },
  { name: 'admin-add-source', index: 10, height: 900, setUp: adminPage(IT, '#/tenant/integrations', 900, async (page) => {
    await page.getByRole('region', { name: 'Sending systems' }).getByRole('button', { name: 'Add', exact: true }).click()
    const editor = page.getByRole('dialog')
    await editor.getByLabel('Name', { exact: true }).fill('Title Records 2')
    await editor.getByLabel('Source key').fill('compare-only')
    await editor.getByRole('button', { name: 'Next' }).click()
    for (const [label, key, kind] of [['Contract number', 'contractNumber', 'Text, digits only'], ['Last name', 'lastName', 'Text, spaces trimmed']]) {
      await editor.getByRole('button', { name: 'Add a field' }).click()
      const field = editor.locator('fieldset.int-field').last()
      await field.getByLabel('Label').fill(label)
      await field.getByLabel('Key it sends').fill(key)
      await field.getByLabel('Kind of value').selectOption({ label: kind })
    }
  }) },
  { name: 'admin-about', index: 11, height: 700, setUp: adminPage(OPS, '#/operations/about', 700) },
  { name: 'admin-ops-overview', index: 12, height: 820, setUp: adminPage(OPS, '#/operations/overview', 820) },
  { name: 'admin-ops-storage', index: 13, height: 680, setUp: adminPage(OPS, '#/operations/storage', 680) },
  { name: 'admin-ops-health', index: 14, height: 760, setUp: adminPage(OPS, '#/operations/health', 760) },
].map((b) => ({ ...b, canvas: ADMIN_CANVAS, width: 1440 }))

// ---- Sensitivity boards (task 0029): docs/design/sensitivity/Sens-*.dc.html, one frame each ----
// Seed-SDV-Canvas-Demo.ps1 (task 0029): 300000000003 Marcus Lee has a Highly Confidential settlement agreement, and Dana
// Ruiz (Restricted steward) an approved print of it; 300000000005 Wei Chen has a Confidential income statement. The
// boards' own examples (Jordan Ellis's settlement agreement, Wei Chen's 4 documents) are data.
const SENS = (file: string) => resolve(REPO, 'docs', 'design', 'sensitivity', file)
const inspector = (page: Page) => page.getByRole('complementary', { name: 'Record inspector' })
async function openRecord(page: Page, number: string) {
  await area(page, 'Records').click()
  await searchBox(page).fill(number)
  await searchBox(page).press('Enter')
  await rows(page).filter({ hasText: number }).first().click()
  await expect(inspector(page)).toBeVisible({ timeout: 15000 })
  await expect(inspector(page).getByText(/level$/).first()).toBeVisible()
}
async function openDocument(page: Page, kind: RegExp) {
  await inspector(page).getByRole('button', { name: /Open documents/ }).click()
  await page.getByRole('navigation', { name: 'Record documents' }).getByRole('button', { name: kind }).click()
  await expect(page.getByRole('img', { name: /Document page 1/ })).toBeVisible({ timeout: 20000 })
}

export const SENSITIVITY_BOARDS: (Board & { setUp: (page: Page) => Promise<void> })[] = [
  { name: 'sens-results', canvas: SENS('Sens-Results.dc.html'), index: 0, width: 1440, height: 900, alone: true, setUp: async (page) => {
    // As the main results board: the last 30 days, so the list holds the restricted records too, with 300000000003 open.
    await MAIN_BOARDS[1].setUp(page)
    await expect(inspector(page).getByText(/level$/).first()).toBeVisible()
  } },
  { name: 'sens-viewer', canvas: SENS('Sens-Viewer.dc.html'), index: 0, width: 1440, height: 900, alone: true, setUp: async (page) => {
    await asRole(page, 'Restricted Steward', 1440, 900)
    await openRecord(page, INCOMPLETE)
    await openDocument(page, /Settlement agreement/)
    await page.getByRole('button', { name: 'Request download' }).click()
    await page.getByLabel('Why do you need it?').fill('Outside counsel needs a copy for the hearing on 2 December.')
    await page.waitForLoadState('networkidle')
  } },
  { name: 'sens-level', canvas: SENS('Sens-Level.dc.html'), index: 0, width: 1440, height: 900, alone: true, setUp: async (page) => {
    await asRole(page, 'Document Steward', 1440, 900)
    await openRecord(page, '300000000005')
    await openDocument(page, /Income statement/)
    await page.locator('.vw-bar').first().getByRole('button', { name: /^Level/ }).click()
    const dialog = page.getByRole('dialog', { name: 'Change sensitivity level' })
    await dialog.getByRole('radio', { name: /Whole contract/ }).check()
    await dialog.getByRole('radio', { name: /Highly Confidential/ }).check()
    await dialog.getByLabel('Reason').fill("Contains an employee's own loan file")
    await page.waitForLoadState('networkidle')
  } },
  // Task 0030. Seed-SDV-Canvas-Demo.ps1: 50 waiting print and download requests from five demo Restricted stewards,
  // a whole-contract request on 300000000002 and two legal holds. More than 10 wait, so the groups start collapsed;
  // as on the board, the first group is opened and ticked, and a second (collapsed) group is ticked too.
  { name: 'sens-approvals', canvas: SENS('Sens-Approvals.dc.html'), index: 0, width: 1440, height: 900, alone: true, setUp: async (page) => {
    await asRole(page, BA, 1440, 900)
    await page.evaluate(() => { window.location.hash = '#/governance/approvals' })
    const list = page.getByRole('region', { name: 'Waiting requests' })
    await expect(list).toBeVisible({ timeout: 15000 })
    const groups = list.getByRole('group')
    await groups.first().getByRole('button', { name: /^Expand / }).click()
    await groups.first().getByRole('checkbox', { name: /^Select all on / }).check()
    const second = groups.filter({ has: page.getByRole('button', { name: /^Expand / }) }).first()
    if (await second.count()) await second.getByRole('checkbox', { name: /^Select all on / }).check()
    await expect(page.getByRole('region', { name: 'Selected requests' })).toBeVisible()
    await page.waitForLoadState('networkidle')
  } },
  { name: 'sens-legalhold', canvas: SENS('Sens-LegalHold.dc.html'), index: 0, width: 1440, height: 820, alone: true, setUp: async (page) => {
    await asRole(page, BA, 1440, 820)
    await page.evaluate(() => { window.location.hash = '#/governance/holds' })
    await page.getByLabel('Contract number').fill(RECORD)
    await page.getByLabel('Reason').fill('Litigation: Ellis v. Acme Lending, case 26-CV-0412')
    await expect(page.getByText(/documents? on contract .* will be held/)).toBeVisible({ timeout: 15000 })
    await page.waitForLoadState('networkidle')
  } },
  // Task 0032: the Daily intake report for today, in the tenant's time zone. The first sending system is open, the
  // others collapsed, as on the board. Its numbers are today's real intake (data).
  { name: 'reports-intake', canvas: SENS('Reports-Intake.dc.html'), index: 0, width: 1440, height: 900, alone: true, setUp: async (page) => {
    await asRole(page, BA, 1440, 900)
    await page.evaluate(() => { window.location.hash = '#/governance/reports' })
    await expect(page.getByRole('region', { name: 'Deliveries by sending system' })).toBeVisible({ timeout: 15000 })
    await page.waitForLoadState('networkidle')
  } },
  // Task 0033: the Sensitivity report for this month, all sending systems, in the tenant's time zone. Its numbers are
  // the dev database's (data).
  { name: 'reports-sensitivity', canvas: SENS('Reports-Sensitivity.dc.html'), index: 0, width: 1440, height: 900, alone: true, setUp: async (page) => {
    await asRole(page, BA, 1440, 900)
    await page.evaluate(() => { window.location.hash = '#/governance/reports' })
    await page.getByRole('tablist', { name: 'Reports' }).getByRole('tab', { name: 'Sensitivity' }).click()
    await expect(page.getByRole('region', { name: 'Attempts to open restricted documents' })).toBeVisible({ timeout: 15000 })
    await page.waitForLoadState('networkidle')
  } },
]

test.describe('Canvas comparison', () => {
  for (const board of [...MAIN_BOARDS, ...QUICK_BOARDS, ...ADMIN_BOARDS, ...SENSITIVITY_BOARDS].filter((b) => !process.env.SDV_COMPARE_ONLY || b.name.includes(process.env.SDV_COMPARE_ONLY))) {
    test(board.name, async ({ page, browser }) => {
      test.setTimeout(90_000)
      const canvasPage = await browser.newPage()
      const canvas = await canvasShot(canvasPage, board)
      await board.setUp(page)
      const app = await appShot(page, board)
      await sideBySide(canvasPage, board.name, canvas, app, board.width, board.height)
      await canvasPage.close()
      // The canvas shows desktop light only. The same state in dark mode and at 390px, for a look (app only).
      await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'))
      writeFileSync(resolve(OUT, `${board.name}.app-dark.png`), await appShot(page, board))
      await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'light'))
      await page.setViewportSize({ width: 390, height: 844 })
      await page.waitForTimeout(300)
      writeFileSync(resolve(OUT, `${board.name}.app-390.png`), await page.screenshot({ fullPage: true, animations: 'disabled' }))
    })
  }
})
