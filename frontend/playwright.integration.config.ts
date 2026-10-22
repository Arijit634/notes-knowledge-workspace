import { defineConfig, devices } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'

// Separate opt-in suite: no HTTP fixtures, traces, response dumps or security screenshots.
process.env.NKW_INTEGRATION_RUNTIME ??= resolve('..', '.runtime', `browser-integration-${randomUUID()}`)
// In the pinned runner this disables failure-time DOM snapshots, which could
// otherwise capture a synthetic one-time MFA/recovery value. Failures still fail.
process.env.PLAYWRIGHT_NO_COPY_PROMPT = '1'
export default defineConfig({
  testDir: './e2e-integrated',
  outputDir: process.env.NKW_INTEGRATION_RUNTIME + '/browser-output',
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 15_000 },
  reporter: [['line'], ['./e2e-integrated/sanitized-reporter.ts']],
  globalTeardown: './e2e-integrated/stop-stack.ts',
  use: { baseURL: 'https://localhost:4175', trace: 'off', screenshot: 'off', video: 'off' },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'], launchOptions: { firefoxUserPrefs: { 'security.enterprise_roots.enabled': true } } } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
  ],
  webServer: {
    command: 'node e2e-integrated/start-stack.mjs',
    url: 'https://localhost:4175',
    reuseExistingServer: false,
    timeout: 240_000,
    gracefulShutdown: { signal: 'SIGTERM', timeout: 90_000 },
  },
})
