import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Note: Vitest 전역 API를 켜지 않아서 Testing Library의 자동 정리가 동작하지 않는다.
// 테스트마다 렌더링한 DOM을 직접 지워 다음 테스트에 남지 않게 한다.
afterEach(() => {
  cleanup()
})
