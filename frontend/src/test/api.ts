import { joinQueue, issueEntryToken } from '@/api/queue'
import { login } from '@/api/auth'
import { ADMIN_USER, DEMO_USER } from '@/mocks/db'
import { setMockScenario } from '@/mocks/scenario'
import { mockServer } from '@/mocks/server'

// API 테스트가 함께 쓰는 준비 단계. 목 상태는 테스트마다 `setup.ts`가 처음으로 돌린다.

export const ON_SALE_SCHEDULE_ID = 31
export const BEFORE_OPEN_SCHEDULE_ID = 32
export const CONCERT_ID = 7

// 회차 31 A구역 1열의 1·2번 좌석(목 데이터)
export const SEAT_A1 = 1001
export const SEAT_A2 = 1002
export const SEAT_A3 = 1003

export function signInAsDemo() {
  return login({ email: DEMO_USER.email, password: DEMO_USER.password })
}

export function signInAsAdmin() {
  return login({ email: ADMIN_USER.email, password: ADMIN_USER.password })
}

// 대기 없이 입장 토큰까지 받는다
export async function admitTo(scheduleId: number) {
  setMockScenario({ queue: 'ready' })
  await joinQueue(scheduleId)
  return issueEntryToken(scheduleId)
}

// 경로가 같은 요청을 보낸 순서대로 모은다. 리스너는 테스트마다 `setup.ts`가 지운다.
// 핸들러가 본문을 읽기 전에 복사해 두므로 테스트에서 본문(text())도 읽을 수 있다.
export function recordRequests(pathname: string): Request[] {
  const requests: Request[] = []
  mockServer.events.on('request:start', ({ request }) => {
    if (new URL(request.url).pathname === pathname) {
      requests.push(request.clone())
    }
  })
  return requests
}

// 그 경로로 요청이 나가면 풀리는 약속. 경합을 재현할 때 "요청이 진행 중인 순간"을 기다리는 데 쓴다.
export function requestStarted(pathname: string): Promise<void> {
  return new Promise((resolve) => {
    mockServer.events.on('request:start', ({ request }) => {
      if (new URL(request.url).pathname === pathname) {
        resolve()
      }
    })
  })
}
