import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { clearSession } from '@/auth/session'
import { resetMockDb } from '@/mocks/db'
import { resetMockScenario } from '@/mocks/scenario'
import { mockServer } from '@/mocks/server'

// 목이 없는 요청은 실패시킨다. 계약에 없는 경로를 부르면 테스트에서 바로 드러난다.
beforeAll(() => {
  mockServer.listen({ onUnhandledFrame: 'error' })
})

// Note: Vitest 전역 API를 켜지 않아서 Testing Library의 자동 정리가 동작하지 않는다.
// 테스트마다 렌더링한 DOM을 직접 지워 다음 테스트에 남지 않게 한다.
// 목 상태·시나리오·토큰 저장소도 테스트마다 처음으로 돌린다.
afterEach(() => {
  cleanup()
  mockServer.resetHandlers()
  mockServer.events.removeAllListeners()
  resetMockDb()
  resetMockScenario()
  clearSession()
  sessionStorage.clear()
})

afterAll(() => {
  mockServer.close()
})
