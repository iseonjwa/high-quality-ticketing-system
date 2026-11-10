import { act, renderHook, waitFor } from '@testing-library/react'
import { http } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import { holdSeats } from '@/api/reservations'
import { readPaymentAttempt, readTossCheckout, savePaymentAttempt } from '@/auth/paymentAttempts'
import { ok } from '@/mocks/respond'
import { reloadPage } from '@/lib/reloadPage'
import { openTossPaymentWindow } from '@/pages/payment/tossPaymentWindow'
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
const CHECKOUT_PATH = '/api/v1/payments/checkout'

// 결제창만 바꿔 끼운다. 오류 코드 판별은 실제 구현을 쓴다
vi.mock('@/pages/payment/tossPaymentWindow', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/pages/payment/tossPaymentWindow')>()),
  openTossPaymentWindow: vi.fn(),
}))
const openWindow = vi.mocked(openTossPaymentWindow)

// 다시 불러오기는 jsdom에서 바꿔 끼울 수 없어 모듈을 대신한다
vi.mock('@/lib/reloadPage', () => ({ reloadPage: vi.fn() }))
const reload = vi.mocked(reloadPage)

beforeEach(async () => {
  openWindow.mockReset()
  reload.mockReset()
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
      // 결제 준비(checkout)는 흐름 시작 때 한 번 나가므로 결제 조회만 센다
      if (pathname.startsWith(`${PAYMENTS_PATH}/`) && pathname !== `${PAYMENTS_PATH}/checkout`) {
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

  describe('결제 준비(checkout)', () => {
    function tossCheckout(held: { amount: number }, overrides: Record<string, unknown> = {}) {
      mockServer.use(
        http.post(CHECKOUT_PATH, () =>
          ok({
            provider: 'TOSS',
            orderId: 'order-1-abcdef',
            clientKey: 'test_ck_fake',
            amount: held.amount,
            orderName: '콘서트 예매 1',
            ...overrides,
          }),
        ),
      )
    }

    it('MOCK이면 checkout 뒤 바로 승인을 보내고 시도를 저장한다', async () => {
      const held = await hold()
      const checkouts = recordRequests(CHECKOUT_PATH)
      const payments = recordRequests(PAYMENTS_PATH)
      const { result } = renderHook(() => usePaymentFlow(held))

      act(() => result.current.submit())
      expect(result.current.state.step).toBe('preparing')

      await waitFor(() => expect(result.current.state.step).toBe('confirmed'), { timeout: 5000 })
      expect(checkouts).toHaveLength(1)
      expect(await checkouts[0].json()).toEqual({ paymentToken: held.paymentToken })
      expect(payments).toHaveLength(1)
      expect(openWindow).not.toHaveBeenCalled()
    })

    it('TOSS면 결제창을 정확한 값으로 열고 결제창 기록만 저장하며 승인 시도는 저장하지 않는다', async () => {
      const held = await hold()
      tossCheckout(held)
      openWindow.mockReturnValue(new Promise(() => {}))
      const payments = recordRequests(PAYMENTS_PATH)
      const { result } = renderHook(() => usePaymentFlow(held))

      act(() => result.current.submit())

      await waitFor(() => expect(result.current.state.step).toBe('redirecting'))
      expect(openWindow).toHaveBeenCalledWith({
        clientKey: 'test_ck_fake',
        orderId: 'order-1-abcdef',
        amount: held.amount,
        orderName: '콘서트 예매 1',
        successUrl: `${window.location.origin}/reservations/${held.reservationId}/payment/toss/success`,
        failUrl: `${window.location.origin}/reservations/${held.reservationId}/payment/toss/fail`,
      })
      expect(readTossCheckout(held.reservationId)).toEqual({
        token: held.paymentToken,
        orderId: 'order-1-abcdef',
        amount: held.amount,
      })
      expect(readPaymentAttempt(held.reservationId)).toBeNull()
      expect(payments).toHaveLength(0)
    })

    it('결제창으로 떠났다가 뒤로 가기로 되살아나면(bfcache) 상태를 손으로 되돌리지 않고 다시 불러온다', async () => {
      const held = await hold()
      tossCheckout(held)
      openWindow.mockReturnValue(new Promise(() => {}))
      const { result } = renderHook(() => usePaymentFlow(held))

      act(() => result.current.submit())
      await waitFor(() => expect(result.current.state.step).toBe('redirecting'))

      // 되살아난 것이 아닌 pageshow는 무시한다
      act(() => {
        window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: false }))
      })
      expect(reload).not.toHaveBeenCalled()

      act(() => {
        window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
      })
      expect(reload).toHaveBeenCalledTimes(1)
      // ready로 되돌리면 확정된 예매에 결제 버튼이 다시 열린다. 새로 불러온 화면이 상태를 다시 정한다
      expect(result.current.state.step).toBe('redirecting')
    })

    it('결제창을 열지 않은 단계의 pageshow는 다시 불러오지 않는다', async () => {
      const held = await hold()
      renderHook(() => usePaymentFlow(held))

      act(() => {
        window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }))
      })

      expect(reload).not.toHaveBeenCalled()
    })

    it('결제창이 USER_CANCEL로 거절되면 tossCanceled가 되고 결제창 기록을 지운다', async () => {
      const held = await hold()
      tossCheckout(held)
      openWindow.mockRejectedValue({ code: 'USER_CANCEL', message: '취소' })
      const { result } = renderHook(() => usePaymentFlow(held))

      act(() => result.current.submit())

      await waitFor(() => expect(result.current.state).toEqual({ step: 'tossCanceled' }))
      expect(readTossCheckout(held.reservationId)).toBeNull()
    })

    it('결제창이 다른 코드로 거절되면 tossFailed가 되고, 다시 누르면 checkout부터 다시 한다', async () => {
      const held = await hold()
      tossCheckout(held)
      openWindow.mockRejectedValue({ code: 'INVALID_CARD_COMPANY' })
      const checkouts = recordRequests(CHECKOUT_PATH)
      const { result } = renderHook(() => usePaymentFlow(held))

      act(() => result.current.submit())
      await waitFor(() =>
        expect(result.current.state).toEqual({ step: 'tossFailed', code: 'INVALID_CARD_COMPANY' }),
      )
      act(() => result.current.submit())

      await waitFor(() => expect(checkouts).toHaveLength(2))
      expect(readTossCheckout(held.reservationId)).toBeNull()
    })

    it('저장한 시도가 있으면 checkout 없이 그 토큰·키·paymentKey로 승인을 보낸다', async () => {
      const held = await hold()
      savePaymentAttempt(held.reservationId, {
        token: held.paymentToken,
        key: 'stored-key',
        clientPaymentRef: 'tgen_stored',
      })
      const checkouts = recordRequests(CHECKOUT_PATH)
      const payments = recordRequests(PAYMENTS_PATH)
      const { result } = renderHook(() => usePaymentFlow(held))

      await waitFor(() => expect(payments).toHaveLength(1))

      expect(checkouts).toHaveLength(0)
      expect(payments[0].headers.get('Idempotency-Key')).toBe('stored-key')
      expect(await payments[0].json()).toEqual({
        paymentToken: held.paymentToken,
        amount: held.amount,
        method: 'CARD',
        clientPaymentRef: 'tgen_stored',
      })
      await waitFor(() => expect(result.current.state.step).not.toBe('ready'))
    })

    it('checkout이 403 PAYMENT_TOKEN_INVALID면 tokenExpired다', async () => {
      const held = await hold()
      mockServer.use(
        http.post(CHECKOUT_PATH, () => fail(403, 'PAYMENT_TOKEN_INVALID', '토큰이 올바르지 않다')),
      )
      const { result } = renderHook(() => usePaymentFlow(held))

      act(() => result.current.submit())

      await waitFor(() => expect(result.current.state).toEqual({ step: 'tokenExpired' }))
    })

    it('checkout의 그 밖의 오류는 failed이고 다시 누르면 checkout부터 다시 한다', async () => {
      const held = await hold()
      mockServer.use(http.post(CHECKOUT_PATH, () => fail(500, 'INTERNAL_SERVER_ERROR', '실패')))
      const checkouts = recordRequests(CHECKOUT_PATH)
      const { result } = renderHook(() => usePaymentFlow(held))

      act(() => result.current.submit())
      await waitFor(() => expect(result.current.state.step).toBe('failed'))
      act(() => result.current.submit())

      await waitFor(() => expect(checkouts).toHaveLength(2))
    })

    it('응답 금액이 화면 금액과 다르면 결제창을 열지 않고 멈춘다', async () => {
      const held = await hold()
      tossCheckout(held, { amount: held.amount + 1 })
      const { result } = renderHook(() => usePaymentFlow(held))

      act(() => result.current.submit())

      await waitFor(() => expect(result.current.state.step).toBe('failed'))
      expect(openWindow).not.toHaveBeenCalled()
      expect(readTossCheckout(held.reservationId)).toBeNull()
    })
  })

  describe('토스연동 건너뛰기(submitMockBypass)', () => {
    it('건너뛰기는 checkout 없이 pgProvider MOCK으로 승인을 보내고 그 표시까지 시도에 저장한다', async () => {
      const held = await hold()
      const checkouts = recordRequests(CHECKOUT_PATH)
      const payments = recordRequests(PAYMENTS_PATH)
      const { result } = renderHook(() => usePaymentFlow(held))

      act(() => result.current.submitMockBypass())

      await waitFor(() => expect(result.current.state.step).toBe('confirmed'), { timeout: 5000 })
      expect(checkouts).toHaveLength(0)
      expect(openWindow).not.toHaveBeenCalled()
      expect(await payments[0].json()).toEqual({
        paymentToken: held.paymentToken,
        amount: held.amount,
        method: 'CARD',
        pgProvider: 'MOCK',
      })
    })

    it('건너뛰기 시도가 결과를 모른 채 남으면 화면을 다시 열 때 같은 키·같은 표시로 이어 보낸다', async () => {
      const held = await hold()
      savePaymentAttempt(held.reservationId, {
        token: held.paymentToken,
        key: 'bypass-key',
        pgProvider: 'MOCK',
      })
      const payments = recordRequests(PAYMENTS_PATH)
      renderHook(() => usePaymentFlow(held))

      await waitFor(() => expect(payments).toHaveLength(1))
      expect(payments[0].headers.get('Idempotency-Key')).toBe('bypass-key')
      expect(await payments[0].json()).toMatchObject({ pgProvider: 'MOCK' })
    })

    it('저장한 시도가 있으면 건너뛰기를 눌러도 새 시도를 만들지 않고 저장한 것을 다시 보낸다', async () => {
      const held = await hold()
      savePaymentAttempt(held.reservationId, {
        token: held.paymentToken,
        key: 'stored-key',
        clientPaymentRef: 'tgen_stored',
      })
      mockServer.use(http.post(PAYMENTS_PATH, () => fail(503, 'PG_UNAVAILABLE', 'PG 장애')))
      const payments = recordRequests(PAYMENTS_PATH)
      const { result } = renderHook(() => usePaymentFlow(held))
      await waitFor(() => expect(result.current.state.step).toBe('pgUnavailable'))

      act(() => result.current.submitMockBypass())

      await waitFor(() => expect(payments).toHaveLength(2))
      expect(payments[1].headers.get('Idempotency-Key')).toBe('stored-key')
      const resent = (await payments[1].json()) as Record<string, unknown>
      expect(resent).toMatchObject({ clientPaymentRef: 'tgen_stored' })
      expect(resent).not.toHaveProperty('pgProvider')
    })
  })
})
