import { existsSync, readFileSync } from 'node:fs'
import { parseEnv } from 'node:util'
import { defineConfig, devices } from '@playwright/test'
import type { E2eOptions } from './e2e/fixtures.ts'

const MSW_PORT = 5174

// Note: 관리자 계정은 E2E_ADMIN_*가 없을 때만 저장소 루트 .env의 두 키를 읽는다. 값은 출력하지 않는다.
if (!process.env.E2E_ADMIN_EMAIL || !process.env.E2E_ADMIN_PASSWORD) {
  const envFile = new URL('../.env', import.meta.url)
  if (existsSync(envFile)) {
    const { ADMIN_EMAIL, ADMIN_PASSWORD } = parseEnv(readFileSync(envFile, 'utf8'))
    if (ADMIN_EMAIL && ADMIN_PASSWORD) {
      process.env.E2E_ADMIN_EMAIL ??= ADMIN_EMAIL
      process.env.E2E_ADMIN_PASSWORD ??= ADMIN_PASSWORD
    }
  }
}

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
        baseURL: process.env.E2E_BASE_URL ?? 'http://127.0.0.1:8000',
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
