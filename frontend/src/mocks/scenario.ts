// 실패 화면을 재현하는 스위치. 테스트는 setMockScenario로, 브라우저는 주소의 `?mock=`로 고른다.

export interface MockScenario {
  // waiting: 순번 조회 두 번 뒤 READY, ready: 진입하자마자 READY
  // unavailable: 대기열 요청의 첫 1번이 503 SERVICE_UNAVAILABLE(Retry-After 1), 그다음부터 waiting과 같다
  // expired: READY까지는 waiting과 같고 첫 토큰 요청이 줄 기록을 지우고 404 QUEUE_NOT_JOINED를 준다. 다시 진입하면 waiting처럼 돈다
  queue: 'waiting' | 'ready' | 'unavailable' | 'expired'
  // seat-conflict: 첫 선점 요청이 409 SEAT_ALREADY_HELD로 끝나고 요청 좌석 하나가 팔린 상태가 된다. 다시 고르면 성공한다
  // hold-unknown: 첫 선점 요청은 서버에서 성립하지만 응답 대신 504를 준다. 같은 멱등키로 다시 보내면 처음 결과(201)를 준다
  seatHold: 'held' | 'seat-conflict' | 'hold-unknown'
  // approved: 승인 응답 200 뒤 예매 조회 두 번째에 CONFIRMED, declined: 거절 200 뒤 예매 RELEASED
  // unknown: 202 UNKNOWN 뒤 첫 결제 조회에서 APPROVED, pg-unavailable: 503 PG_UNAVAILABLE(결제 기록 없음)
  // manual: 승인 요청이 409 PAYMENT_ALREADY_EXISTS(결제는 NEEDS_MANUAL_CHECK로 남고 Location이 붙는다)
  // slow-confirm: 승인은 되지만 예매가 계속 HELD라 확정 확인이 시간을 넘긴다
  // late-approval: 결제는 승인되지만 승인이 선점 만료 뒤에 도착해 예매가 EXPIRED가 된다(서버는 환불한다)
  payment:
    | 'approved'
    | 'declined'
    | 'unknown'
    | 'pg-unavailable'
    | 'manual'
    | 'slow-confirm'
    | 'late-approval'
  // unavailable: 자동결제 카드 등록이 503 PG_UNAVAILABLE로 끝난다(해지는 영향 없다)
  billing: 'ok' | 'unavailable'
  // 추첨 3번(추첨 완료)의 데모 응모 결과와 응모 실패. 기본은 처음 조회 WON, 다음 조회부터 PAID(예매 확정)
  // waitlisted: 예비 순번 3번, lost: 낙첨
  // failed: 처음 조회 WON, 다음 조회부터 FAILED(BILLING_KEY_MISSING)
  // unknown: 첫 응모 POST가 서버에서는 성립하지만 504. 같은 멱등키로 다시 보내면 201(그 응모의 지금 상태)
  // closed: 응모 POST가 409 LOTTERY_NOT_OPEN
  lottery: 'default' | 'waitlisted' | 'lost' | 'failed' | 'unknown' | 'closed'
  // 예매 취소 뒤 환불. refunded: 결제 조회 두 번째에 REFUNDED, slow: 환불이 끝나지 않는다(운영자 확인을 기다리는 경우)
  refund: 'refunded' | 'slow'
}

const DEFAULT_SCENARIO: MockScenario = {
  queue: 'waiting',
  seatHold: 'held',
  payment: 'approved',
  billing: 'ok',
  lottery: 'default',
  refund: 'refunded',
}

let current: MockScenario = { ...DEFAULT_SCENARIO }

export function mockScenario(): MockScenario {
  return current
}

export function setMockScenario(patch: Partial<MockScenario>) {
  current = { ...current, ...patch }
}

export function resetMockScenario() {
  current = { ...DEFAULT_SCENARIO }
}

// `?mock=seat-conflict,payment-unknown`처럼 쉼표로 여러 개를 고른다
export const SCENARIO_SWITCHES = {
  'queue-ready': { queue: 'ready' },
  'queue-unavailable': { queue: 'unavailable' },
  'queue-expired': { queue: 'expired' },
  'seat-conflict': { seatHold: 'seat-conflict' },
  'hold-unknown': { seatHold: 'hold-unknown' },
  'payment-declined': { payment: 'declined' },
  'payment-unknown': { payment: 'unknown' },
  'pg-unavailable': { payment: 'pg-unavailable' },
  'payment-manual': { payment: 'manual' },
  'payment-slow-confirm': { payment: 'slow-confirm' },
  'payment-late-approval': { payment: 'late-approval' },
  'billing-unavailable': { billing: 'unavailable' },
  'lottery-waitlisted': { lottery: 'waitlisted' },
  'lottery-lost': { lottery: 'lost' },
  'lottery-failed': { lottery: 'failed' },
  'lottery-unknown': { lottery: 'unknown' },
  'lottery-closed': { lottery: 'closed' },
  'refund-slow': { refund: 'slow' },
} as const satisfies Record<string, Partial<MockScenario>>

export function parseScenarioSwitches(value: string): Partial<MockScenario> {
  const patch: Partial<MockScenario> = {}
  for (const name of value.split(',').map((part) => part.trim())) {
    if (name === '') {
      continue
    }
    if (!(name in SCENARIO_SWITCHES)) {
      console.warn(
        `[mock] 모르는 시나리오 "${name}". 쓸 수 있는 값:`,
        Object.keys(SCENARIO_SWITCHES),
      )
      continue
    }
    Object.assign(patch, SCENARIO_SWITCHES[name as keyof typeof SCENARIO_SWITCHES])
  }
  return patch
}
