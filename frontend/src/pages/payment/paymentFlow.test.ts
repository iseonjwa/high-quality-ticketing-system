import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from '@/api/apiError'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import { approvePayment } from '@/api/payments'
import { queryRetryDelay } from '@/api/queryClient'
import { holdSeats } from '@/api/reservations'
import { mockDb, toKstIso } from '@/mocks/db'
import { fail, ok } from '@/mocks/respond'
import { setMockScenario } from '@/mocks/scenario'
import { mockServer } from '@/mocks/server'
import {
  runPaymentFlow,
  type PaymentFlowOutcome,
  type PaymentFlowState,
} from '@/pages/payment/paymentFlow'
import { admitTo, ON_SALE_SCHEDULE_ID, recordRequests, SEAT_A1, signInAsDemo } from '@/test/api'

const PAYMENTS_PATH = '/api/v1/payments'

// 정상 흐름은 기다림이 몇 번뿐이다. 이보다 많으면 끝없이 다시 보내는 것으로 보고 멈춘다.
// 확정 확인이 30초까지 1초씩 기다리는 경우(30번)가 가장 길다.
const MAX_WAITS = 50

interface Held {
  reservationId: number
  paymentToken: string
  amount: number
}

beforeEach(async () => {
  await signInAsDemo()
})

async function hold(): Promise<Held> {
  await admitTo(ON_SALE_SCHEDULE_ID)
  const result = await holdSeats(ON_SALE_SCHEDULE_ID, [SEAT_A1], newIdempotencyKey())
  if (result.status !== 'HELD') {
    throw new Error('HELD여야 한다')
  }
  return {
    reservationId: result.reservationId,
    paymentToken: result.paymentToken,
    amount: result.totalAmount,
  }
}

interface RunResult {
  outcome: PaymentFlowOutcome
  states: PaymentFlowState[]
  waits: number[]
  controller: AbortController
}

// 기다린 시간을 남기고 바로 끝나는 sleep으로 흐름을 끝까지 돌린다.
// Note: 가짜 sleep이 바로 끝나서 흐름이 끝나지 않으면 타이머가 돌 틈이 없어 테스트 시간 제한도 걸리지 않는다.
// 그래서 기다림 횟수로 끊는다
async function run(
  held: Held,
  options: {
    idempotencyKey?: string
    onSleep?: (waits: number[], controller: AbortController) => void
  } = {},
): Promise<RunResult> {
  const states: PaymentFlowState[] = []
  const waits: number[] = []
  const controller = new AbortController()
  const outcome = await runPaymentFlow({
    ...held,
    idempotencyKey: options.idempotencyKey ?? newIdempotencyKey(),
    signal: controller.signal,
    onState: (state) => states.push(state),
    sleep: (millis) => {
      waits.push(millis)
      options.onSleep?.(waits, controller)
      if (waits.length >= MAX_WAITS) {
        controller.abort()
      }
      return Promise.resolve()
    },
    random: () => 0.5,
  })
  return { outcome, states, waits, controller }
}

function steps(states: PaymentFlowState[]) {
  return states.map((state) => state.step)
}

function reservationBody(held: Held, status: string) {
  return {
    reservationId: held.reservationId,
    reservationNo: 'R20261104-000001',
    scheduleId: ON_SALE_SCHEDULE_ID,
    status,
    holdExpiresAt: toKstIso(Date.now() + 60_000),
    totalAmount: held.amount,
    seats: [{ seatId: SEAT_A1, section: 'A', row: 1, number: 1, grade: 'VIP', price: held.amount }],
    paymentToken: null,
    tickets: [],
  }
}

function paymentDetailBody(held: Held, status: string) {
  return {
    paymentId: 9001,
    reservationId: held.reservationId,
    status,
    amount: held.amount,
    approvedAt: null,
    refundedAt: null,
  }
}

function errorOnce(path: string, method: 'get' | 'post', response: () => Response) {
  mockServer.use(http[method](path, response, { once: true }))
}

describe('runPaymentFlow 정상 흐름', () => {
  it('200 APPROVED는 확정이 아니다. 예매 조회로 CONFIRMED를 확인한 뒤 confirmed다', async () => {
    const held = await hold()

    const { outcome, states, waits } = await run(held)

    expect(steps(states)).toEqual(['paying', 'confirming', 'confirmed'])
    expect(outcome).toMatchObject({ step: 'confirmed' })
    const confirmed = states.at(-1)
    expect(confirmed).toMatchObject({ reservation: { status: 'CONFIRMED' } })
    // 확정 확인은 첫 조회를 바로 하고 이후 1초마다다
    expect(waits).toEqual([1000])
  })

  it('202는 서버가 준 nextPollAfterSeconds 뒤에 결제를 조회하고, APPROVED면 확정을 확인한다', async () => {
    setMockScenario({ payment: 'unknown' })
    const held = await hold()

    const { outcome, states, waits } = await run(held)

    expect(steps(states)).toEqual(['paying', 'checking', 'confirming', 'confirmed'])
    expect(states[1]).toMatchObject({ step: 'checking', paymentId: expect.any(Number) })
    expect(outcome.step).toBe('confirmed')
    // 목의 nextPollAfterSeconds는 2초다. 고정값(10초)을 쓰지 않는다
    expect(waits).toEqual([2000, 1000])
  })

  it('조회에서도 REQUESTED·UNKNOWN이면 마지막으로 받은 간격마다 다시 조회한다', async () => {
    setMockScenario({ payment: 'unknown' })
    const held = await hold()
    let polls = 0
    mockServer.use(
      http.get('/api/v1/payments/:paymentId', ({ params }) => {
        polls += 1
        const status = polls < 3 ? (polls === 1 ? 'REQUESTED' : 'UNKNOWN') : 'APPROVED'
        return ok({ ...paymentDetailBody(held, status), paymentId: Number(params.paymentId) })
      }),
      http.get('/api/v1/reservations/:reservationId', () => ok(reservationBody(held, 'CONFIRMED'))),
    )

    const { outcome, waits } = await run(held)

    expect(polls).toBe(3)
    expect(waits.slice(0, 3)).toEqual([2000, 2000, 2000])
    expect(outcome.step).toBe('confirmed')
  })
})

describe('runPaymentFlow 승인 응답 분기', () => {
  it('200 DECLINED는 사유와 함께 declined다', async () => {
    setMockScenario({ payment: 'declined' })
    const held = await hold()

    const { outcome, states } = await run(held)

    expect(steps(states)).toEqual(['paying', 'declined'])
    expect(outcome).toEqual({ step: 'declined', reasonCode: 'INSUFFICIENT_LIMIT' })
  })

  it('200 REFUNDED는 refunded다', async () => {
    const held = await hold()
    mockServer.use(
      http.post(PAYMENTS_PATH, () =>
        ok({
          paymentId: 9001,
          reservationId: held.reservationId,
          status: 'REFUNDED',
          amount: held.amount,
          refundedAt: '2026-11-04T11:00:00.000Z',
        }),
      ),
    )

    const { outcome } = await run(held)

    expect(outcome).toEqual({ step: 'refunded' })
  })

  it('payment-manual: 409 PAYMENT_ALREADY_EXISTS는 manualCheck다', async () => {
    setMockScenario({ payment: 'manual' })
    const held = await hold()

    const { outcome, waits } = await run(held)

    expect(outcome).toEqual({ step: 'manualCheck' })
    expect(waits).toEqual([])
  })

  it('pg-unavailable: 503 PG_UNAVAILABLE는 다시 보내지 않고 pgUnavailable이다', async () => {
    setMockScenario({ payment: 'pg-unavailable' })
    const held = await hold()
    const requests = recordRequests(PAYMENTS_PATH)

    const { outcome, waits } = await run(held)

    expect(outcome).toEqual({ step: 'pgUnavailable' })
    expect(waits).toEqual([])
    expect(requests).toHaveLength(1)
  })

  it.each([
    [403, 'PAYMENT_TOKEN_INVALID', { step: 'tokenExpired' }],
    [409, 'RESERVATION_NOT_PAYABLE', { step: 'tokenExpired' }],
    [409, 'PAYMENT_ALREADY_EXISTS', { step: 'manualCheck' }],
    [409, 'PAYMENT_ALREADY_CLOSED', { step: 'closed' }],
  ])('%i %s는 %o로 끝난다', async (status, code, expected) => {
    const held = await hold()
    mockServer.use(http.post(PAYMENTS_PATH, () => fail(status, code, '실패')))

    const { outcome, waits } = await run(held)

    expect(outcome).toEqual(expected)
    expect(waits).toEqual([])
  })

  it.each([
    [400, 'PAYMENT_AMOUNT_MISMATCH'],
    [409, 'IDEMPOTENCY_KEY_REUSED'],
    [404, 'NOT_FOUND'],
    [500, 'INTERNAL_SERVER_ERROR'],
  ])('%i %s는 다시 보내지 않고 failed다', async (status, code) => {
    const held = await hold()
    mockServer.use(http.post(PAYMENTS_PATH, () => fail(status, code, '실패')))
    const requests = recordRequests(PAYMENTS_PATH)

    const { outcome } = await run(held)

    expect(outcome).toMatchObject({ step: 'failed', error: expect.any(ApiError) })
    expect(requests).toHaveLength(1)
  })
})

describe('runPaymentFlow 결제 조회', () => {
  it.each([
    ['DECLINED', { step: 'declined', reasonCode: null }],
    ['REFUNDED', { step: 'refunded' }],
    ['NEEDS_MANUAL_CHECK', { step: 'manualCheck' }],
  ])('조회 %s는 %o로 끝난다', async (status, expected) => {
    setMockScenario({ payment: 'unknown' })
    const held = await hold()
    mockServer.use(
      http.get('/api/v1/payments/:paymentId', () => ok(paymentDetailBody(held, status))),
    )

    const { outcome } = await run(held)

    expect(outcome).toEqual(expected)
  })

  it('조회가 503이면 단계를 그대로 두고 지연 뒤 다시 조회한다', async () => {
    setMockScenario({ payment: 'unknown' })
    const held = await hold()
    errorOnce('/api/v1/payments/:paymentId', 'get', () =>
      fail(503, 'SERVICE_UNAVAILABLE', '잠시 후', { headers: { 'Retry-After': '4' } }),
    )

    const { outcome, states, waits } = await run(held)

    expect(outcome.step).toBe('confirmed')
    expect(steps(states)).toEqual(['paying', 'checking', 'confirming', 'confirmed'])
    const retryAfter = new ApiError({
      status: 503,
      code: 'SERVICE_UNAVAILABLE',
      message: '',
      retryAfterSeconds: 4,
    })
    // 첫 기다림은 서버 간격, 실패 뒤 기다림은 Retry-After를 지킨 지연이다
    expect(waits.slice(0, 2)).toEqual([2000, queryRetryDelay(0, retryAfter, () => 0.5, 3000)])
  })

  it.each([404, 500])('조회가 %i이면 failed다', async (status) => {
    setMockScenario({ payment: 'unknown' })
    const held = await hold()
    mockServer.use(http.get('/api/v1/payments/:paymentId', () => fail(status, 'X', '실패')))

    const { outcome } = await run(held)

    expect(outcome).toMatchObject({ step: 'failed', error: expect.any(ApiError) })
  })
})

describe('runPaymentFlow 결과 모름 재전송', () => {
  it('응답이 없으면(0) 같은 멱등키·같은 본문으로 다시 보내고, checking(paymentId null)을 거친다', async () => {
    const held = await hold()
    errorOnce(PAYMENTS_PATH, 'post', () => HttpResponse.error())
    const requests = recordRequests(PAYMENTS_PATH)
    const idempotencyKey = newIdempotencyKey()

    const { outcome, states, waits } = await run(held, { idempotencyKey })

    expect(outcome.step).toBe('confirmed')
    expect(steps(states).slice(0, 3)).toEqual(['paying', 'checking', 'confirming'])
    expect(states[1]).toEqual({ step: 'checking', paymentId: null })
    expect(requests).toHaveLength(2)
    expect(requests[0].headers.get('Idempotency-Key')).toBe(idempotencyKey)
    expect(requests[1].headers.get('Idempotency-Key')).toBe(idempotencyKey)
    expect(await requests[1].text()).toBe(await requests[0].text())
    const unknown = new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '' })
    expect(waits[0]).toBe(queryRetryDelay(0, unknown, () => 0.5, 3000))
  })

  it('504 뒤 다시 보내 처음 결과(202)를 받으면 그 간격으로 조회한다', async () => {
    setMockScenario({ payment: 'unknown' })
    const held = await hold()
    const idempotencyKey = newIdempotencyKey()
    // 서버는 처리했지만 Gateway가 시한을 넘긴 경우: 먼저 서버에서 처리해 두고, 흐름의 첫 요청 응답만 504로 바꾼다
    await approvePayment(
      { paymentToken: held.paymentToken, amount: held.amount, method: 'CARD' },
      idempotencyKey,
    )
    errorOnce(PAYMENTS_PATH, 'post', () => fail(504, 'INTERNAL_SERVER_ERROR', '시한 초과'))
    const sent = recordRequests(PAYMENTS_PATH)

    const { outcome, states } = await run(held, { idempotencyKey })

    expect(outcome.step).toBe('confirmed')
    // 504 → 같은 키 재전송(처음 결과 202) → 서버 간격 뒤 조회
    expect(steps(states)).toEqual(['paying', 'checking', 'checking', 'confirming', 'confirmed'])
    expect(mockDb.payments.size).toBe(1)
    expect(sent).toHaveLength(2)
    expect(sent.every((r) => r.headers.get('Idempotency-Key') === idempotencyKey)).toBe(true)
  })

  it('429·502·503 SERVICE_UNAVAILABLE는 Retry-After를 지켜 같은 키로 다시 보낸다', async () => {
    const held = await hold()
    errorOnce(PAYMENTS_PATH, 'post', () =>
      fail(503, 'SERVICE_UNAVAILABLE', '잠시 후', { headers: { 'Retry-After': '4' } }),
    )
    const requests = recordRequests(PAYMENTS_PATH)

    const { outcome, states, waits } = await run(held)

    expect(outcome.step).toBe('confirmed')
    // 결과를 모르는 것이 아니라 아직 접수 전이라 checking을 거치지 않는다
    expect(steps(states)).toEqual(['paying', 'confirming', 'confirmed'])
    const retryAfter = new ApiError({
      status: 503,
      code: 'SERVICE_UNAVAILABLE',
      message: '',
      retryAfterSeconds: 4,
    })
    expect(waits[0]).toBe(queryRetryDelay(0, retryAfter, () => 0.5, 3000))
    expect(waits[0]).toBeGreaterThanOrEqual(4000)
    expect(requests).toHaveLength(2)
  })

  it.each([429, 502])('%i도 같은 키로 다시 보낸다', async (status) => {
    const held = await hold()
    errorOnce(PAYMENTS_PATH, 'post', () => fail(status, 'RATE_LIMITED', '잠시 후'))
    const requests = recordRequests(PAYMENTS_PATH)

    const { outcome } = await run(held)

    expect(outcome.step).toBe('confirmed')
    expect(requests).toHaveLength(2)
  })

  it('다시 보내기는 8번까지다. 끝내 모르면 처음 요청 포함 9번 보내고 slowConfirm이다', async () => {
    const held = await hold()
    mockServer.use(http.post(PAYMENTS_PATH, () => fail(504, 'INTERNAL_SERVER_ERROR', '시한 초과')))
    const requests = recordRequests(PAYMENTS_PATH)

    const { outcome, waits, controller } = await run(held)

    expect(controller.signal.aborted).toBe(false)
    expect(outcome).toEqual({ step: 'slowConfirm', approved: false })
    expect(requests).toHaveLength(9)
    expect(waits).toHaveLength(8)
  })
})

describe('runPaymentFlow 예매 확정 확인', () => {
  it.each([
    ['EXPIRED', { step: 'refunded' }],
    ['RELEASED', { step: 'notConfirmed' }],
    ['CANCELED', { step: 'notConfirmed' }],
  ])('승인 뒤 예매가 %s면 %o다', async (status, expected) => {
    const held = await hold()
    mockServer.use(
      http.get('/api/v1/reservations/:reservationId', () => ok(reservationBody(held, status))),
    )

    const { outcome } = await run(held)

    expect(outcome).toEqual(expected)
  })

  it('HELD가 계속되면 30초 뒤 slowConfirm이다', async () => {
    setMockScenario({ payment: 'slow-confirm' })
    const held = await hold()

    const { outcome, waits, controller } = await run(held)

    expect(controller.signal.aborted).toBe(false)
    expect(outcome).toEqual({ step: 'slowConfirm', approved: true })
    expect(waits).toEqual(Array<number>(30).fill(1000))
  })

  it('예매 조회가 503이면 단계를 그대로 두고 다시 조회한다', async () => {
    const held = await hold()
    errorOnce('/api/v1/reservations/:reservationId', 'get', () =>
      fail(503, 'SERVICE_UNAVAILABLE', '잠시 후'),
    )

    const { outcome, states } = await run(held)

    expect(outcome.step).toBe('confirmed')
    expect(steps(states)).toEqual(['paying', 'confirming', 'confirmed'])
  })

  it.each([404, 500])('예매 조회가 %i이면 failed다', async (status) => {
    const held = await hold()
    mockServer.use(http.get('/api/v1/reservations/:reservationId', () => fail(status, 'X', '실패')))

    const { outcome } = await run(held)

    expect(outcome).toMatchObject({ step: 'failed' })
  })
})

describe('runPaymentFlow 취소', () => {
  it('기다리는 중에 취소하면 더 보내지 않고 aborted로 끝난다', async () => {
    setMockScenario({ payment: 'unknown' })
    const held = await hold()
    const detailRequests = recordRequests('/api/v1/payments/1001')
    const reservationRequests = recordRequests(`/api/v1/reservations/${held.reservationId}`)

    const { outcome, states } = await run(held, {
      onSleep: (_waits, controller) => controller.abort(),
    })

    expect(outcome).toEqual({ step: 'aborted' })
    expect(detailRequests).toHaveLength(0)
    expect(reservationRequests).toHaveLength(0)
    // 취소한 뒤에는 화면에 상태를 알리지 않는다
    expect(steps(states)).toEqual(['paying', 'checking'])
  })

  it('시작 전에 취소돼 있으면 요청을 보내지 않는다', async () => {
    const held = await hold()
    const requests = recordRequests(PAYMENTS_PATH)
    const controller = new AbortController()
    controller.abort()

    const outcome = await runPaymentFlow({
      ...held,
      idempotencyKey: newIdempotencyKey(),
      signal: controller.signal,
      onState: () => {},
    })

    expect(outcome).toEqual({ step: 'aborted' })
    expect(requests).toHaveLength(0)
  })
})
