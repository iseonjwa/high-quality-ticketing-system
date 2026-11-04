import { act, renderHook, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import { holdSeats } from '@/api/reservations'
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

  it('결제 토큰이 바뀌면 새 멱등키를 쓴다', async () => {
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

    expect(requests[1].headers.get('Idempotency-Key')).not.toBe(
      requests[0].headers.get('Idempotency-Key'),
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
})
