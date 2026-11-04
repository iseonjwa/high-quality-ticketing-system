import { setupServer } from 'msw/node'
import { handlers } from '@/mocks/handlers'

// 테스트(Node)에서 같은 핸들러로 요청을 가로챈다. 시작·정리는 `src/test/setup.ts`가 한다.
export const mockServer = setupServer(...handlers)
