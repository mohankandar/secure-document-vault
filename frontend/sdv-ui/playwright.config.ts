import { defineConfig, devices } from '@playwright/test'

const baseURL = process.env.SDV_UI_BASE_URL ?? 'http://localhost:5183'

export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  expect: { timeout: 8_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
  ],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
    // A stuck click fails within this time instead of using up the whole test, so clean-up steps still run.
    actionTimeout: 20_000,
  },
  projects: [
    {
      name: 'desktop-chromium',
      use: {
        ...devices['Desktop Chrome'],
        viewport: { width: 1440, height: 1000 },
      },
    },
    {
      name: 'ipad',
      use: {
        ...devices['iPad Pro 11'],
      },
    },
  ],
})
