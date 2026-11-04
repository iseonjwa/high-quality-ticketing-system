import { http } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from '@/api/apiError'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import { readEntryToken } from '@/auth/entryTokens'
import { mockDb } from '@/mocks/db'
import { fail } from '@/mocks/respond'
import { setMockScenario } from '@/mocks/scenario'
import { mockServer } from '@/mocks/server'
import { submitHold, type HoldOutcome } from '@/pages/seats/seatHold'
import {
  admitTo,
  ON_SALE_SCHEDULE_ID,
  recordRequests,
  SEAT_A1,
  SEAT_A2,
  SEAT_A3,
  signInAsDemo,
} from '@/test/api'

const HOLD_PATH = `/api/v1/schedules/${ON_SALE_SCHEDULE_ID}/reservations`

beforeEach(async () => {
  await signInAsDemo()
})

// 정상 흐름은 기다림이 몇 번뿐이다. 이보다 많으면 끝없이 다시 보내는 것으로 보고 멈춘다
const MAX_WAITS = 10

// 기다린 시간을 남기고 바로 끝나는 sleep으로 한 번의 선점을 끝까지 돌린다.
// Note: 가짜 sleep이 바로 끝나서 흐름이 끝나지 않으면 타이머가 돌 틈이 없어 테스트 시간 제한도 걸리지 않는다.
// 그래서 기다림 횟수로 끊는다
async function run(
  seatIds: number[],
  options: { idempotencyKey?: string; controller?: AbortController } = {},
) {
  const waits: number[] = []
  const retrying: ApiError[] = []
  const controller = options.controller ?? new AbortController()
  const outcome = await submitHold({
    scheduleId: ON_SALE_SCHEDULE_ID,
    seatIds,
    idempotencyKey: options.idempotencyKey ?? newIdempotencyKey(),
    signal: controller.signal,
    onRetrying: (error) => retrying.push(error),
    sleep: (millis) => {
      waits.push(millis)
      if (waits.length >= MAX_WAITS) {
        controller.abort()
      }
      return Promise.resolve()
    },
    random: () => 0.5,
  })
  return { outcome, waits, retrying }
}

function failedWith(outcome: HoldOutcome): ApiError {
  expect(outcome.kind).toBe('failed')
  const error = (outcome as Extract<HoldOutcome, { kind: 'failed' }>).error
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

describe('좌석 선점 요청', () => {
  it('201이면 held이고 선점 결과를 그대로 준다', async () => {
    await admitTo(ON_SALE_SCHEDULE_ID)

    const { outcome, waits, retrying } = await run([SEAT_A2, SEAT_A1])

    expect(outcome.kind).toBe('held')
    const hold = (outcome as Extract<HoldOutcome, { kind: 'held' }>).hold
    expect(hold.status).toBe('HELD')
    expect(hold.seatIds).toEqual([SEAT_A1, SEAT_A2])
    expect(waits).toEqual([])
    expect(retrying).toEqual([])
  })

  it('seat-conflict: 409 SEAT_ALREADY_HELD는 conflict이고 다시 보내지 않는다', async () => {
    setMockScenario({ seatHold: 'seat-conflict' })
    await admitTo(ON_SALE_SCHEDULE_ID)
    const requests = recordRequests(HOLD_PATH)

    const { outcome, waits } = await run([SEAT_A1, SEAT_A2])

    expect(outcome).toEqual({ kind: 'conflict' })
    expect(requests).toHaveLength(1)
    expect(waits).toEqual([])
  })

  it('입장 토큰이 없으면 요청 없이 entryExpired다', async () => {
    const requests = recordRequests(HOLD_PATH)

    const { outcome } = await run([SEAT_A1])

    expect(outcome).toEqual({ kind: 'entryExpired' })
    expect(requests).toHaveLength(0)
  })

  it('403 ENTRY_TOKEN_INVALID는 entryExpired이고 저장한 토큰이 지워진다', async () => {
    await admitTo(ON_SALE_SCHEDULE_ID)
    expect(readEntryToken(ON_SALE_SCHEDULE_ID)).not.toBeNull()
    mockServer.use(
      http.post(HOLD_PATH, () => fail(403, 'ENTRY_TOKEN_INVALID', '입장 토큰이 올바르지 않습니다')),
    )

    const { outcome } = await run([SEAT_A1])

    expect(outcome).toEqual({ kind: 'entryExpired' })
    expect(readEntryToken(ON_SALE_SCHEDULE_ID)).toBeNull()
  })

  it('hold-unknown: 504 뒤 같은 키로 다시 보내 처음 결과를 받고 좌석은 한 번만 선점된다', async () => {
    setMockScenario({ seatHold: 'hold-unknown' })
    await admitTo(ON_SALE_SCHEDULE_ID)
    const requests = recordRequests(HOLD_PATH)
    const key = newIdempotencyKey()

    const { outcome, waits, retrying } = await run([SEAT_A1, SEAT_A2], { idempotencyKey: key })

    expect(outcome.kind).toBe('held')
    expect(retrying).toHaveLength(1)
    expect(retrying[0].status).toBe(504)
    expect(waits).toHaveLength(1)
    expect(requests).toHaveLength(2)
    expect(requests.map((request) => request.headers.get('Idempotency-Key'))).toEqual([key, key])
    expect(await requests[0].text()).toBe(await requests[1].text())
    expect(mockDb.reservations.size).toBe(1)
    expect(mockDb.holdRequests.size).toBe(1)
  })

  it('503 Retry-After: 2는 2200ms(random 0.5) 기다린 뒤 다시 보낸다', async () => {
    await admitTo(ON_SALE_SCHEDULE_ID)
    mockServer.use(
      http.post(
        HOLD_PATH,
        () =>
          fail(503, 'SERVICE_UNAVAILABLE', '잠시 후 다시 시도해 주세요', {
            headers: { 'Retry-After': '2' },
          }),
        { once: true },
      ),
    )

    const { outcome, waits, retrying } = await run([SEAT_A1])

    expect(waits).toEqual([2200])
    expect(retrying.map((error) => error.status)).toEqual([503])
    expect(outcome.kind).toBe('held')
  })

  it('계속 504면 처음을 포함해 4번 보내고 마지막 오류와 함께 failed다', async () => {
    await admitTo(ON_SALE_SCHEDULE_ID)
    mockServer.use(http.post(HOLD_PATH, () => fail(504, 'INTERNAL_SERVER_ERROR', '시간 초과')))
    const requests = recordRequests(HOLD_PATH)

    const { outcome, waits, retrying } = await run([SEAT_A1])

    expect(failedWith(outcome).status).toBe(504)
    expect(requests).toHaveLength(4)
    expect(retrying).toHaveLength(3)
    expect(waits).toHaveLength(3)
  })

  it('PURCHASE_LIMIT_EXCEEDED는 failed이고 다시 보내지 않는다', async () => {
    await admitTo(ON_SALE_SCHEDULE_ID)
    // 회차 31은 1인당 4매다. 4매를 잡은 뒤 한 매를 더 요청한다
    await run([SEAT_A1, SEAT_A2, SEAT_A3, SEAT_A3 + 1])
    const requests = recordRequests(HOLD_PATH)

    const { outcome, waits } = await run([SEAT_A3 + 2])

    expect(failedWith(outcome).code).toBe('PURCHASE_LIMIT_EXCEEDED')
    expect(requests).toHaveLength(1)
    expect(waits).toEqual([])
  })

  it('400 SEAT_COUNT_MISMATCH는 failed이고 다시 보내지 않는다', async () => {
    await admitTo(ON_SALE_SCHEDULE_ID)
    const requests = recordRequests(HOLD_PATH)

    const { outcome } = await run([])

    expect(failedWith(outcome).code).toBe('SEAT_COUNT_MISMATCH')
    expect(requests).toHaveLength(1)
  })

  it('같은 키에 다른 좌석이면 IDEMPOTENCY_KEY_REUSED로 failed다', async () => {
    await admitTo(ON_SALE_SCHEDULE_ID)
    const key = newIdempotencyKey()
    await run([SEAT_A1], { idempotencyKey: key })

    const { outcome } = await run([SEAT_A2], { idempotencyKey: key })

    expect(failedWith(outcome).code).toBe('IDEMPOTENCY_KEY_REUSED')
  })

  it('시작 전에 취소돼 있으면 요청 없이 aborted다', async () => {
    await admitTo(ON_SALE_SCHEDULE_ID)
    const requests = recordRequests(HOLD_PATH)
    const controller = new AbortController()
    controller.abort()

    const { outcome } = await run([SEAT_A1], { controller })

    expect(outcome).toEqual({ kind: 'aborted' })
    expect(requests).toHaveLength(0)
  })

  it('다시 보내기를 기다리다 취소되면 더 보내지 않고 aborted다', async () => {
    await admitTo(ON_SALE_SCHEDULE_ID)
    mockServer.use(http.post(HOLD_PATH, () => fail(504, 'INTERNAL_SERVER_ERROR', '시간 초과')))
    const requests = recordRequests(HOLD_PATH)
    const controller = new AbortController()
    const outcome = await submitHold({
      scheduleId: ON_SALE_SCHEDULE_ID,
      seatIds: [SEAT_A1],
      idempotencyKey: newIdempotencyKey(),
      signal: controller.signal,
      sleep: () => {
        controller.abort()
        return Promise.resolve()
      },
    })

    expect(outcome).toEqual({ kind: 'aborted' })
    expect(requests).toHaveLength(1)
  })

  it('요청이 진행 중일 때 취소되면 aborted다', async () => {
    await admitTo(ON_SALE_SCHEDULE_ID)
    const controller = new AbortController()
    mockServer.use(
      http.post(HOLD_PATH, async () => {
        controller.abort()
        await new Promise((resolve) => setTimeout(resolve, 20))
        return fail(504, 'INTERNAL_SERVER_ERROR', '시간 초과')
      }),
    )

    const { outcome, waits } = await run([SEAT_A1], { controller })

    expect(outcome).toEqual({ kind: 'aborted' })
    expect(waits).toEqual([])
  })
})
