import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  timeout: 90000,
  workers: 1,
  reporter: [['list'], ['json', { outputFile: 'test-results/auth-results.json' }]],
  use: {
    baseURL: process.env.CAREPLAN_BASE_URL || 'http://localhost:3000',
    channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
})
