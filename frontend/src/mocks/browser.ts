import { setupWorker } from 'msw/browser'
import { mockSettings } from '@/mocks/db'
import { handlers } from '@/mocks/handlers'
import { mockScenario, parseScenarioSwitches, setMockScenario } from '@/mocks/scenario'

const SCENARIO_STORAGE_KEY = 'iseonjwa.mockScenario'

// `npm run dev:mock`에서만 부른다. 화면을 옮겨 다녀도 시나리오가 유지되게 주소의 `?mock=`를 탭에 저장한다.
// `?mock=`(빈 값)으로 열면 기본 시나리오로 돌아간다.
export async function startMockApi() {
  const fromUrl = new URLSearchParams(window.location.search).get('mock')
  if (fromUrl !== null) {
    sessionStorage.setItem(SCENARIO_STORAGE_KEY, fromUrl)
  }
  setMockScenario(parseScenarioSwitches(sessionStorage.getItem(SCENARIO_STORAGE_KEY) ?? ''))
  mockSettings.acceptUnknownRefreshTokens = true

  // 목이 없는 요청(화면 자원 등)은 그대로 내보낸다
  await setupWorker(...handlers).start({ onUnhandledFrame: 'bypass' })
  console.info('[mock] API 목을 켰습니다. 시나리오:', mockScenario())
}
