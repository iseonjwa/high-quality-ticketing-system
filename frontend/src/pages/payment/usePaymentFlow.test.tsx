import { act, renderHook, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import { holdSeats } from '@/api/reservations'
import { readPaymentAttempt, savePaymentAttempt } from '@/auth/paymentAttempts'
import { fail } from '@/mocks/respond'
import { setMockScenario } from '@/mocks/scenario'
import { mockServer } from '@/mocks/server'
import { usePaymentFlow } from '@/pages/payment/usePaymentFlow'
import {
  admitTo,
  ON_SALE_SCHEDULE_ID,
  recordRequests,
  requestStarted,
  SEAT_A1,
  signInAsDemo,
} from '@/test/api'

const PAYMENTS_PATH = '/api/v1/payments'

beforeEach(async () => {
  await signInAsDemo()
})

async function hold() {
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

describe('usePaymentFlow', () => {
  it('처음에는 ready이고 submit하면 흐름이 끝나 confirmed가 된다', async () => {
    const held = await hold()
    const { result } = renderHook(() => usePaymentFlow(held))

    expect(result.current.state).toEqual({ step: 'ready' })
    act(() => result.current.submit())

    await waitFor(() => expect(result.current.state.step).toBe('confirmed'), { timeout: 5000 })
  })

  it('같은 토큰으로 실패 뒤 다시 누르면 같은 멱등키를 쓴다', async () => {
    setMockScenario({ payment: 'pg-unavailable' })
    const held = await hold()
    const requests = recordRequests(PAYMENTS_PATH)
    const { result } = renderHook(() => usePaymentFlow(held))

    act(() => result.current.submit())
    await waitFor(() => expect(result.current.state.step).toBe('pgUnavailable'))
    act(() => result.current.submit())
    await waitFor(() => expect(requests).toHaveLength(2))

    expect(requests[1].headers.get('Idempotency-Key')).toBe(
      requests[0].headers.get('Idempotency-Key'),
    )
  })

  it('결제를 시작한 뒤 화면이 받은 토큰이 바뀌어도 처음 토큰·키로 보낸다', async () => {
    mockServer.use(http.post(PAYMENTS_PATH, () => fail(500, 'INTERNAL_SERVER_ERROR', '실패')))
    const held = await hold()
    const requests = recordRequests(PAYMENTS_PATH)
    const { result, rerender } = renderHook((props) => usePaymentFlow(props), {
      initialProps: held,
    })

    act(() => result.current.submit())
    await waitFor(() => expect(result.current.state.step).toBe('failed'))
    rerender({ ...held, paymentToken: `${held.paymentToken}.next` })
    act(() => result.current.submit())
    await waitFor(() => expect(requests).toHaveLength(2))

    expect(requests[1].headers.get('Idempotency-Key')).toBe(
      requests[0].headers.get('Idempotency-Key'),
    )
    expect(((await requests[1].clone().json()) as { paymentToken: string }).paymentToken).toBe(
      held.paymentToken,
    )
  })

  it('진행 중에 submit을 또 부르면 무시한다', async () => {
    setMockScenario({ payment: 'unknown' })
    const held = await hold()
    const requests = recordRequests(PAYMENTS_PATH)
    const { result } = renderHook(() => usePaymentFlow(held))

    act(() => {
      result.current.submit()
      result.current.submit()
    })
    await waitFor(() => expect(result.current.state.step).toBe('checking'))
    act(() => result.current.submit())

    expect(requests).toHaveLength(1)
  })

  it('언마운트하면 흐름을 멈춰 요청이 더 가지 않는다', async () => {
    setMockScenario({ payment: 'unknown' })
    const held = await hold()
    const started = requestStarted(PAYMENTS_PATH)
    const detailRequests: string[] = []
    mockServer.events.on('request:start', ({ request }) => {
      const { pathname } = new URL(request.url)
      if (pathname.startsWith(`${PAYMENTS_PATH}/`)) {
        detailRequests.push(pathname)
      }
    })
    const { result, unmount } = renderHook(() => usePaymentFlow(held))
    act(() => result.current.submit())
    await started
    await waitFor(() => expect(result.current.state.step).toBe('checking'))

    unmount()
    // 목의 202 간격은 2초다. 그 안에 취소돼 조회가 나가지 않아야 한다
    await new Promise((resolve) => setTimeout(resolve, 2300))

    expect(detailRequests).toHaveLength(0)
  })

  it('결제를 시작하면 토큰과 멱등키를 저장한다', async () => {
    setMockScenario({ payment: 'pg-unavailable' })
    const held = await hold()
    const requests = recordRequests(PAYMENTS_PATH)
    const { result } = renderHook(() => usePaymentFlow(held))

    act(() => result.current.submit())
    await waitFor(() => expect(result.current.state.step).toBe('pgUnavailable'))

    expect(readPaymentAttempt(held.reservationId)).toEqual({
      token: held.paymentToken,
      key: requests[0].headers.get('Idempotency-Key'),
    })
  })

  it('저장한 시도가 있고 토큰이 같으면 화면을 열 때 같은 키로 바로 이어서 보낸다', async () => {
    const held = await hold()
    const key = newIdempotencyKey()
    savePaymentAttempt(held.reservationId, { token: held.paymentToken, key })
    const requests = recordRequests(PAYMENTS_PATH)

    const { result } = renderHook(() => usePaymentFlow(held))

    await waitFor(() => expect(result.current.state.step).toBe('confirmed'), { timeout: 5000 })
    expect(requests).toHaveLength(1)
    expect(requests[0].headers.get('Idempotency-Key')).toBe(key)
  })

  it('이어 보낸 뒤 다시 열어도(새로고침) 같은 키로 처음 결과를 받는다', async () => {
    setMockScenario({ payment: 'unknown' })
    const held = await hold()
    const requests = recordRequests(PAYMENTS_PATH)
    const first = renderHook(() => usePaymentFlow(held))
    act(() => first.result.current.submit())
    await waitFor(() => expect(first.result.current.state.step).toBe('checking'))
    first.unmount()

    const second = renderHook(() => usePaymentFlow(held))

    await waitFor(() => expect(requests).toHaveLength(2))
    expect(requests[1].headers.get('Idempotency-Key')).toBe(
      requests[0].headers.get('Idempotency-Key'),
    )
    expect(second.result.current.state.step).not.toBe('ready')
    second.unmount()
  })

  it('저장한 시도의 토큰이 화면이 받은 토큰과 달라도 저장한 토큰·키로 이어 보낸다', async () => {
    const held = await hold()
    const stored = { token: held.paymentToken, key: newIdempotencyKey() }
    savePaymentAttempt(held.reservationId, stored)
    const requests = recordRequests(PAYMENTS_PATH)

    // 예매 상세로 다시 열면 조회마다 새로 서명한 토큰을 받는다
    renderHook(() => usePaymentFlow({ ...held, paymentToken: `${held.paymentToken}.resigned` }))

    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0].headers.get('Idempotency-Key')).toBe(stored.key)
    expect(((await requests[0].clone().json()) as { paymentToken: string }).paymentToken).toBe(
      stored.token,
    )
    expect(readPaymentAttempt(held.reservationId)?.key ?? stored.key).toBe(stored.key)
  })

  it('저장한 시도가 없으면 눌러야 보낸다', async () => {
    const held = await hold()
    const requests = recordRequests(PAYMENTS_PATH)

    const { result } = renderHook(() => usePaymentFlow(held))
    await Promise.resolve()

    expect(result.current.state).toEqual({ step: 'ready' })
    expect(requests).toHaveLength(0)
  })

  it.each([
    ['declined', 'declined' as const],
    ['manual', 'manualCheck' as const],
  ])('끝난 단계(%s)가 되면 저장한 시도를 지운다', async (scenario, step) => {
    setMockScenario({ payment: scenario as 'declined' | 'manual' })
    const held = await hold()
    const { result } = renderHook(() => usePaymentFlow(held))

    act(() => result.current.submit())
    await waitFor(() => expect(result.current.state.step).toBe(step))

    expect(readPaymentAttempt(held.reservationId)).toBeNull()
  })

  it('확정되면 저장한 시도를 지운다', async () => {
    const held = await hold()
    const { result } = renderHook(() => usePaymentFlow(held))

    act(() => result.current.submit())
    await waitFor(() => expect(result.current.state.step).toBe('confirmed'), { timeout: 5000 })

    expect(readPaymentAttempt(held.reservationId)).toBeNull()
  })

  it('pgUnavailable·failed에서는 저장한 시도를 남긴다', async () => {
    mockServer.use(http.post(PAYMENTS_PATH, () => fail(500, 'INTERNAL_SERVER_ERROR', '실패')))
    const held = await hold()
    const { result } = renderHook(() => usePaymentFlow(held))

    act(() => result.current.submit())
    await waitFor(() => expect(result.current.state.step).toBe('failed'))

    expect(readPaymentAttempt(held.reservationId)?.token).toBe(held.paymentToken)
  })
})
