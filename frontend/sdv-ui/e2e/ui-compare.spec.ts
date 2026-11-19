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

export type Board = { name: string; canvas: string; index: number; width: number; height: number }

async function canvasShot(page: Page, board: Board): Promise<Buffer> {
  // Room for the frame at full width beside the canvas's 360px label column: a narrower window squeezes the frame.
  await page.setViewportSize({ width: board.width + 600, height: board.height + 200 })
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
  if (!((await accountButton(page).getAttribute('aria-label')) ?? '').endsWith(', ' + role.toLowerCase().replace(/^./, (c) => c.toUpperCase()))) {
    await accountButton(page).click()
    await Promise.all([page.waitForEvent('load'), page.getByLabel('Local test role').selectOption({ label: role })])
    await expect(accountButton(page)).toBeVisible({ timeout: 15000 })
  }
  // Every compare run starts the same way: the canvas's loan system (Seed-SDV-Canvas-Demo.ps1) as the remembered choice.
  const schema = await (await page.request.get('/api/v1/business/search-schema', { headers: { 'X-SDV-Tenant-Id': '11111111-1111-1111-1111-111111111111', 'X-SDV-Subject': 'local-business-user', 'X-SDV-Roles': 'BUSINESS_USER' } })).json() as { sources: { sourceId: string; displayName: string }[] }
  const lexa = schema.sources.find((s) => s.displayName === CANVAS_SOURCE) ?? schema.sources.find((s) => s.displayName === 'LexaVault')
  if (lexa) await page.evaluate((id) => window.localStorage.setItem('vero.records.sourceId', id), lexa.sourceId)
  await page.evaluate(() => window.localStorage.setItem('sdv.r7.theme', 'light'))
  await page.reload()
  await expect(accountButton(page)).toBeVisible({ timeout: 15000 })
}

const area = (page: Page, name: string) => page.getByRole('banner').getByRole('navigation', { name: 'Areas' }).getByRole('button', { name: new RegExp(`^${name}( .+)?$`) })
const searchBox = (page: Page) => page.getByRole('searchbox', { name: 'Search records' })
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

test.describe('Canvas comparison', () => {
  for (const board of MAIN_BOARDS) {
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
