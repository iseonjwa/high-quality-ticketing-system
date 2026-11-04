import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { toast } from 'sonner'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { clearSession } from '@/auth/session'
import { demoModeStore } from '@/demo/demoMode'
import { clearRequestLog } from '@/demo/requestLog'
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
// 토스트 목록은 sonner 모듈 전역에 남고 새로 그린 Toaster가 남은 토스트를 다시 받으므로 모두 닫아 둔다.
afterEach(() => {
  cleanup()
  toast.dismiss()
  mockServer.resetHandlers()
  mockServer.events.removeAllListeners()
  resetMockDb()
  resetMockScenario()
  clearSession()
  demoModeStore.setState(demoModeStore.getInitialState())
  clearRequestLog()
  sessionStorage.clear()
  // 자동결제 카드 기록(billingKeyRecord)이 localStorage에 있다. 테스트끼리 섞이지 않게 함께 비운다
  localStorage.clear()
})

afterAll(() => {
  mockServer.close()
})
