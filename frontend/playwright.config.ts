import { defineConfig, devices } from '@playwright/test'
import type { E2eOptions } from './e2e/fixtures.ts'

const MSW_PORT = 5174

// Note: compose 프로젝트만 돌릴 때는 MSW용 개발 서버가 필요 없다. CLI 인자로 대상 프로젝트를 읽어 서버를 건너뛴다.
const projectArgs = process.argv.flatMap((arg, i, all) =>
  arg.startsWith('--project=')
    ? [arg.slice('--project='.length)]
    : arg === '--project'
      ? [all[i + 1]]
      : [],
)
const needsMswServer = projectArgs.length === 0 || projectArgs.includes('msw')

export default defineConfig<E2eOptions>({
  testDir: './e2e',
  timeout: 120_000,
  expect: { timeout: 15_000 },
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    locale: 'ko-KR',
    timezoneId: 'Asia/Seoul',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'msw',
      use: { ...devices['Desktop Chrome'], baseURL: `http://localhost:${MSW_PORT}`, target: 'msw' },
    },
    {
      name: 'compose',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8000',
        target: 'compose',
      },
    },
  ],
  webServer: needsMswServer
    ? {
        command: `npm run dev:mock -- --port ${MSW_PORT} --strictPort`,
        url: `http://localhost:${MSW_PORT}`,
        reuseExistingServer: !process.env.CI,
        timeout: 120_000,
      }
    : undefined,
})
