import { beforeEach, describe, expect, it } from 'vitest'
import { http } from 'msw'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import { approvePayment, checkoutPayment } from '@/api/payments'
import { ok } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { recordRequests, signInAsDemo } from '@/test/api'

const CHECKOUT_PATH = '/api/v1/payments/checkout'
const PAYMENTS_PATH = '/api/v1/payments'

beforeEach(async () => {
  await signInAsDemo()
})

function respondCheckout(data: unknown) {
  mockServer.use(http.post(CHECKOUT_PATH, () => ok(data)))
}

describe('checkoutPayment', () => {
  it('MOCK 응답을 읽는다', async () => {
    respondCheckout({ provider: 'MOCK', amount: 1000, orderName: '콘서트 예매 1' })

    await expect(checkoutPayment('token')).resolves.toEqual({
      provider: 'MOCK',
      amount: 1000,
      orderName: '콘서트 예매 1',
    })
  })

  it('TOSS 응답을 읽는다', async () => {
    const data = {
      provider: 'TOSS',
      orderId: 'order-1-abcdef',
      clientKey: 'test_ck_fake',
      amount: 1000,
      orderName: '콘서트 예매 1',
    }
    respondCheckout(data)

    await expect(checkoutPayment('token')).resolves.toEqual(data)
  })

  it('TOSS 응답에 clientKey가 없으면 실패한다', async () => {
    respondCheckout({
      provider: 'TOSS',
      orderId: 'order-1-abcdef',
      amount: 1000,
      orderName: '콘서트 예매 1',
    })

    await expect(checkoutPayment('token')).rejects.toThrow()
  })

  it('본문에 결제 토큰만 담고 멱등키는 붙이지 않는다', async () => {
    respondCheckout({ provider: 'MOCK', amount: 1000, orderName: '콘서트 예매 1' })
    const requests = recordRequests(CHECKOUT_PATH)

    await checkoutPayment('token-1')

    expect(await requests[0].json()).toEqual({ paymentToken: 'token-1' })
    expect(requests[0].headers.get('Idempotency-Key')).toBeNull()
  })
})

describe('approvePayment: clientPaymentRef', () => {
  const APPROVED = {
    paymentId: 1,
    reservationId: 1,
    status: 'APPROVED',
    amount: 1000,
    approvedAt: '2026-11-10T00:00:00Z',
  }

  it('값이 없으면 본문에 키를 넣지 않는다', async () => {
    mockServer.use(http.post(PAYMENTS_PATH, () => ok(APPROVED)))
    const requests = recordRequests(PAYMENTS_PATH)

    await approvePayment(
      { paymentToken: 'token', amount: 1000, method: 'CARD', clientPaymentRef: undefined },
      newIdempotencyKey(),
    )

    const sent = (await requests[0].json()) as Record<string, unknown>
    expect(Object.keys(sent).sort()).toEqual(['amount', 'method', 'paymentToken'])
  })

  it('값이 있으면 본문에 넣는다', async () => {
    mockServer.use(http.post(PAYMENTS_PATH, () => ok(APPROVED)))
    const requests = recordRequests(PAYMENTS_PATH)

    await approvePayment(
      { paymentToken: 'token', amount: 1000, method: 'CARD', clientPaymentRef: 'tgen_1' },
      newIdempotencyKey(),
    )

    expect(await requests[0].json()).toEqual({
      paymentToken: 'token',
      amount: 1000,
      method: 'CARD',
      clientPaymentRef: 'tgen_1',
    })
  })
})
