import { act, renderHook } from '@testing-library/react'
import { http } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fail } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { useHoldRequest, type HoldResult } from '@/pages/seats/useHoldRequest'
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
const unavailable = () => fail(503, 'SERVICE_UNAVAILABLE', '서비스를 일시적으로 사용할 수 없습니다')
const entryRejected = () => fail(403, 'ENTRY_TOKEN_INVALID', '입장 토큰이 올바르지 않습니다')
const countMismatch = () =>
  fail(400, 'SEAT_COUNT_MISMATCH', '선택한 좌석 수나 좌석이 올바르지 않습니다')

// 요청마다 앞에서부터 하나씩 꺼내 답하고, 다 쓰면 마지막 답을 되풀이한다
function scriptResponses(...responders: (() => Response)[]) {
  const queue = [...responders]
  mockServer.use(http.post(HOLD_API, () => (queue.length > 1 ? queue.shift()! : queue[0])()))
}

// 정해 둔 답을 앞에서부터 한 번씩 쓰고, 다 쓰면 기본 목 핸들러(정상 선점)로 넘긴다
function scriptThenDefault(...responders: (() => Response)[]) {
  const queue = [...responders]
  mockServer.use(http.post(HOLD_API, () => queue.shift()?.()))
}

// 3초 기다림을 건너뛰는 sleep. 부른 시간을 남긴다
function instantSleep() {
  return vi.fn<(millis: number, signal: AbortSignal) => Promise<void>>(() => Promise.resolve())
}

function keysOf(requests: Request[]) {
  return requests.map((request) => request.headers.get('Idempotency-Key'))
}

// 결과를 모르는 요청은 같은 키로 다시 보내느라 1초·2초·4초를 기다린다. 가짜 시계로 건너뛴다
async function submitAndSettle(
  result: { current: ReturnType<typeof useHoldRequest> },
  seatIds: number[],
) {
  let pending: Promise<HoldResult> | undefined
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

    expect(first.kind).toBe('unknown')
    expect(second.kind).toBe('unknown')
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
    scriptResponses(conflict)
    const requests = recordRequests(HOLD_API)
    const { result } = renderHook(() => useHoldRequest(ON_SALE_SCHEDULE_ID))

    const first = await submitAndSettle(result, [SEAT_A1, SEAT_A2])
    const second = await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    expect(first.kind).toBe('conflict')
    expect(second.kind).toBe('conflict')
    const keys = keysOf(requests)
    expect(keys).toHaveLength(2)
    expect(keys[1]).not.toBe(keys[0])
    expect(result.current.outstanding).toBe(false)
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

    let pending: Promise<HoldResult> | undefined
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

    let pending: Promise<HoldResult> | undefined
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

describe('useHoldRequest 결과 미확인', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
  })

  function renderWithSleep() {
    const sleep = instantSleep()
    const rendered = renderHook(() => useHoldRequest(ON_SALE_SCHEDULE_ID, { sleep }))
    return { ...rendered, sleep }
  }

  it('504 뒤 503으로 소진되면 unknown이고 키를 남기며 outstanding이다. 다시 보내면 같은 키다', async () => {
    scriptResponses(unknownResult, unavailable)
    const requests = recordRequests(HOLD_API)
    const { result } = renderWithSleep()

    const first = await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    expect(first).toEqual({ kind: 'unknown' })
    expect(result.current.outstanding).toBe(true)
    expect(result.current.state).toBe('idle')
    expect(requests).toHaveLength(4)

    const second = await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    expect(second).toEqual({ kind: 'unknown' })
    expect(new Set(keysOf(requests)).size).toBe(1)
  })

  it('504 뒤 그 밖의 실패(500)도 unknown이다', async () => {
    scriptResponses(unknownResult, () => fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류'))
    const { result } = renderWithSleep()

    const outcome = await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    expect(outcome).toEqual({ kind: 'unknown' })
    expect(result.current.outstanding).toBe(true)
  })

  it('결과를 모른 적이 없으면 503 소진은 unknown이 아니라 failed이고 키를 버린다', async () => {
    scriptResponses(unavailable)
    const requests = recordRequests(HOLD_API)
    const { result } = renderWithSleep()

    const first = await submitAndSettle(result, [SEAT_A1, SEAT_A2])
    await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    expect(first.kind).toBe('failed')
    expect(result.current.outstanding).toBe(false)
    const keys = keysOf(requests)
    expect(keys).toHaveLength(8)
    expect(keys[4]).not.toBe(keys[0])
  })

  it('504 뒤 409는 3초를 기다려 같은 키로 한 번 더 보내고, 201이면 held다', async () => {
    scriptThenDefault(unknownResult, conflict)
    const requests = recordRequests(HOLD_API)
    const { result, sleep } = renderWithSleep()

    const outcome = await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    expect(outcome.kind).toBe('held')
    expect(sleep).toHaveBeenCalledTimes(1)
    expect(sleep.mock.calls[0][0]).toBe(3000)
    expect(requests).toHaveLength(3)
    expect(new Set(keysOf(requests)).size).toBe(1)
    expect(result.current.outstanding).toBe(false)
  })

  it('504 뒤 409가 기다린 뒤에도 409면 진짜 conflict이고 키를 버린다', async () => {
    scriptResponses(unknownResult, conflict)
    const requests = recordRequests(HOLD_API)
    const { result, sleep } = renderWithSleep()

    const outcome = await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    expect(outcome).toEqual({ kind: 'conflict' })
    expect(sleep).toHaveBeenCalledTimes(1)
    expect(result.current.outstanding).toBe(false)
    expect(requests).toHaveLength(3)

    await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    const keys = keysOf(requests)
    expect(new Set(keys.slice(0, 3)).size).toBe(1)
    expect(keys[3]).not.toBe(keys[0])
  })

  it('결과를 모른 적이 없는 409는 기다리지 않고 바로 conflict다', async () => {
    scriptResponses(conflict)
    const requests = recordRequests(HOLD_API)
    const { result, sleep } = renderWithSleep()

    const outcome = await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    expect(outcome).toEqual({ kind: 'conflict' })
    expect(sleep).not.toHaveBeenCalled()
    expect(requests).toHaveLength(1)
  })

  it('504 뒤 403은 maybeHeld가 켜진 entryExpired이고 키를 버린다', async () => {
    scriptResponses(unknownResult, entryRejected)
    const { result, sleep } = renderWithSleep()

    const outcome = await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    expect(outcome).toEqual({ kind: 'entryExpired', maybeHeld: true })
    expect(sleep).not.toHaveBeenCalled()
    expect(result.current.outstanding).toBe(false)
  })

  it('결과를 모른 적이 없는 403은 maybeHeld가 꺼진 entryExpired다', async () => {
    scriptResponses(entryRejected)
    const { result } = renderWithSleep()

    const outcome = await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    expect(outcome).toEqual({ kind: 'entryExpired', maybeHeld: false })
  })

  it('504 뒤 재요청이 201이면 held이고 outstanding이 꺼진다', async () => {
    scriptThenDefault(unknownResult)
    const { result } = renderWithSleep()

    const outcome = await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    expect(outcome.kind).toBe('held')
    expect(result.current.outstanding).toBe(false)
  })

  it('abandon은 남은 키를 버려 다음 요청이 새 키를 쓴다', async () => {
    scriptResponses(unknownResult)
    const requests = recordRequests(HOLD_API)
    const { result } = renderWithSleep()

    await submitAndSettle(result, [SEAT_A1, SEAT_A2])
    expect(result.current.outstanding).toBe(true)
    act(() => result.current.abandon())
    expect(result.current.outstanding).toBe(false)
    await submitAndSettle(result, [SEAT_A1, SEAT_A2])

    const keys = keysOf(requests)
    expect(keys[keys.length - 1]).not.toBe(keys[0])
    expect(new Set(keys).size).toBe(2)
  })
})
