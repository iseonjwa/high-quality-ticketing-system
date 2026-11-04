import { act, renderHook } from '@testing-library/react'
import { http } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fail } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import type { HoldOutcome } from '@/pages/seats/seatHold'
import { useHoldRequest } from '@/pages/seats/useHoldRequest'
import {
  admitTo,
  ON_SALE_SCHEDULE_ID,
  recordRequests,
  SEAT_A1,
  SEAT_A2,
  SEAT_A3,
  signInAsDemo,
} from '@/test/api'

const HOLD_API = `/api/v1/schedules/${ON_SALE_SCHEDULE_ID}/reservations`

beforeEach(async () => {
  await signInAsDemo()
  await admitTo(ON_SALE_SCHEDULE_ID)
})

afterEach(() => {
  vi.useRealTimers()
})

const unknownResult = () => fail(504, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다')
const conflict = () => fail(409, 'SEAT_ALREADY_HELD', '이미 선택된 좌석입니다')
const countMismatch = () =>
  fail(400, 'SEAT_COUNT_MISMATCH', '선택한 좌석 수나 좌석이 올바르지 않습니다')

// 요청마다 앞에서부터 하나씩 꺼내 답하고, 다 쓰면 마지막 답을 되풀이한다
function scriptResponses(...responders: (() => Response)[]) {
  const queue = [...responders]
  mockServer.use(http.post(HOLD_API, () => (queue.length > 1 ? queue.shift()! : queue[0])()))
}

function keysOf(requests: Request[]) {
  return requests.map((request) => request.headers.get('Idempotency-Key'))
}

// 결과를 모르는 요청은 같은 키로 다시 보내느라 1초·2초·4초를 기다린다. 가짜 시계로 건너뛴다
async function submitAndSettle(
  result: { current: ReturnType<typeof useHoldRequest> },
  seatIds: number[],
) {
  let pending: Promise<HoldOutcome> | undefined
  act(() => {
    pending = result.current.submit(seatIds)
  })
  await act(() => vi.advanceTimersByTimeAsync(10_000))
  return pending!
}

describe('useHoldRequest 멱등키', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
  })

  it('결과를 끝내 모르면 같은 좌석에는 같은 키를 쓰고, 순서만 다른 같은 좌석도 같은 키다', async () => {
    scriptResponses(unknownResult)
    const requests = recordRequests(HOLD_API)
    const { result } = renderHook(() => useHoldRequest(ON_SALE_SCHEDULE_ID))

    const first = await submitAndSettle(result, [SEAT_A1, SEAT_A2])
    const second = await submitAndSettle(result, [SEAT_A2, SEAT_A1])

    expect(first.kind).toBe('failed')
    expect(second.kind).toBe('failed')
    expect(requests.length).toBeGreaterThanOrEqual(2)
    expect(new Set(keysOf(requests)).size).toBe(1)
  })

  it('좌석을 오름차순으로 보낸다', async () => {
    scriptResponses(conflict)
    const requests = recordRequests(HOLD_API)
    const { result } = renderHook(() => useHoldRequest(ON_SALE_SCHEDULE_ID))

    await submitAndSettle(result, [SEAT_A3, SEAT_A1, SEAT_A2])

    expect(await requests[0].json()).toEqual({ seatIds: [SEAT_A1, SEAT_A2, SEAT_A3] })
  })

  it('결과가 정해진 409 뒤에는 같은 좌석이라도 새 키를 쓴다', async () => {
    scriptResponses(unknownResult, unknownResult, unknownResult, unknownResult, conflict)
    const requests = recordRequests(HOLD_API)
    const { result } = renderHook(() => useHoldRequest(ON_SALE_SCHEDULE_ID))

    await submitAndSettle(result, [SEAT_A1, SEAT_A2]) // 결과 모름: 키를 남긴다 (4번 보냄)
    const conflicted = await submitAndSettle(result, [SEAT_A1, SEAT_A2]) // 같은 키로 보냈더니 409
    const third = await submitAndSettle(result, [SEAT_A1, SEAT_A2]) // 새 키

    expect(conflicted.kind).toBe('conflict')
    expect(third.kind).toBe('conflict')
    const keys = keysOf(requests)
    expect(keys).toHaveLength(6)
    expect(new Set(keys.slice(0, 5)).size).toBe(1)
    expect(keys[5]).not.toBe(keys[0])
  })

  it('성공 뒤에는 새 키를 쓴다', async () => {
    const requests = recordRequests(HOLD_API)
    const { result } = renderHook(() => useHoldRequest(ON_SALE_SCHEDULE_ID))

    const first = await submitAndSettle(result, [SEAT_A1, SEAT_A2])
    // 이미 잡힌 좌석이라 목은 409로 답한다. 키만 본다
    await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    expect(first.kind).toBe('held')
    const keys = keysOf(requests)
    expect(keys).toHaveLength(2)
    expect(keys[1]).not.toBe(keys[0])
  })

  it('400 뒤에는 새 키를 쓴다', async () => {
    scriptResponses(countMismatch)
    const requests = recordRequests(HOLD_API)
    const { result } = renderHook(() => useHoldRequest(ON_SALE_SCHEDULE_ID))

    const first = await submitAndSettle(result, [SEAT_A1, SEAT_A2])
    await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    expect(first.kind).toBe('failed')
    const keys = keysOf(requests)
    expect(keys).toHaveLength(2)
    expect(keys[1]).not.toBe(keys[0])
  })

  it('결과를 모르는 채로 좌석이 바뀌면 새 키를 쓴다', async () => {
    scriptResponses(unknownResult)
    const requests = recordRequests(HOLD_API)
    const { result } = renderHook(() => useHoldRequest(ON_SALE_SCHEDULE_ID))

    await submitAndSettle(result, [SEAT_A1, SEAT_A2])
    await submitAndSettle(result, [SEAT_A1, SEAT_A3])

    expect(new Set(keysOf(requests)).size).toBe(2)
  })

  it('요청 중에는 busy이고 끝나면 idle로 돌아온다', async () => {
    scriptResponses(conflict)
    const { result } = renderHook(() => useHoldRequest(ON_SALE_SCHEDULE_ID))
    expect(result.current.state).toBe('idle')
    expect(result.current.busy).toBe(false)

    let pending: Promise<HoldOutcome> | undefined
    act(() => {
      pending = result.current.submit([SEAT_A1, SEAT_A2])
    })
    expect(result.current.state).toBe('sending')
    expect(result.current.busy).toBe(true)

    await act(async () => {
      await pending
    })
    expect(result.current.state).toBe('idle')
  })
})

describe('useHoldRequest 정리', () => {
  it('화면을 떠나면 다시 보내기를 멈추고 결과를 aborted로 돌려준다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    scriptResponses(unknownResult)
    const requests = recordRequests(HOLD_API)
    const { result, unmount } = renderHook(() => useHoldRequest(ON_SALE_SCHEDULE_ID))

    let pending: Promise<HoldOutcome> | undefined
    act(() => {
      pending = result.current.submit([SEAT_A1, SEAT_A2])
    })
    await vi.waitFor(() => expect(requests).toHaveLength(1))
    unmount()
    await act(() => vi.advanceTimersByTimeAsync(10_000))

    expect((await pending!).kind).toBe('aborted')
    expect(requests).toHaveLength(1)
  })
})
