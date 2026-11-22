import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 75_000,
  expect: { timeout: 8000 },
  use: {
    ...devices['Desktop Chrome'],
    channel: 'chromium',
    baseURL: process.env.KH_E2E_BASE_URL || 'http://127.0.0.1:18080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
})
