import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from '@/api/apiError'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import { approvePayment, checkoutPayment, getPayment } from '@/api/payments'
import { getReservation, holdSeats } from '@/api/reservations'
import { mockDb } from '@/mocks/db'
import { setMockScenario } from '@/mocks/scenario'
import {
  admitTo,
  ON_SALE_SCHEDULE_ID,
  SEAT_A1,
  SEAT_A2,
  signInAsAdmin,
  signInAsDemo,
} from '@/test/api'

// 목이 서버의 결제·예매 조회 동작(contracts-remaining 1·2절)과 같은지 확인한다.

beforeEach(async () => {
  await signInAsDemo()
})

async function hold(seatId = SEAT_A1) {
  await admitTo(ON_SALE_SCHEDULE_ID)
  const result = await holdSeats(ON_SALE_SCHEDULE_ID, [seatId], newIdempotencyKey())
  if (result.status !== 'HELD') {
    throw new Error('HELD여야 한다')
  }
  return result
}

async function rejectionOf(promise: Promise<unknown>): Promise<ApiError> {
  const error = await promise.then(
    () => null,
    (reason: unknown) => reason,
  )
  expect(error).toBeInstanceOf(ApiError)
  return error as ApiError
}

function body(held: { paymentToken: string; totalAmount: number }) {
  return { paymentToken: held.paymentToken, amount: held.totalAmount, method: 'CARD' as const }
}

describe('결제 목: 멱등키는 사용자별이다', () => {
  it('다른 사용자가 같은 키를 써도 서로 독립이다', async () => {
    const key = newIdempotencyKey()
    const demoHold = await hold()
    await approvePayment(body(demoHold), key)

    await signInAsAdmin()
    const adminHold = await hold(SEAT_A2)
    const result = await approvePayment(body(adminHold), key)

    expect(result.status).toBe('APPROVED')
    expect(result.reservationId).toBe(adminHold.reservationId)
  })
})

describe('결제 목: 승인 요청 409', () => {
  it('payment-manual: 409 PAYMENT_ALREADY_EXISTS이고 결제는 NEEDS_MANUAL_CHECK로 남는다', async () => {
    setMockScenario({ payment: 'manual' })
    const held = await hold()
    const key = newIdempotencyKey()

    const error = await rejectionOf(approvePayment(body(held), key))

    expect(error.status).toBe(409)
    expect(error.code).toBe('PAYMENT_ALREADY_EXISTS')
    const [payment] = [...mockDb.payments.values()]
    expect((await getPayment(payment.paymentId)).status).toBe('NEEDS_MANUAL_CHECK')
    // 같은 키로 다시 보내도 같은 409다
    expect((await rejectionOf(approvePayment(body(held), key))).code).toBe('PAYMENT_ALREADY_EXISTS')
  })
})

describe('예매 조회 목', () => {
  it('결제 가능 시간(만료 10초 전)이 지나면 HELD여도 paymentToken이 null이다', async () => {
    const held = await hold()
    expect((await getReservation(held.reservationId)).paymentToken).not.toBeNull()

    mockDb.reservations.get(held.reservationId)!.holdExpiresAtMillis = Date.now() + 5000
    const reservation = await getReservation(held.reservationId)

    expect(reservation.status).toBe('HELD')
    expect(reservation.paymentToken).toBeNull()
  })

  it.each([0, -1])('예매 번호 %i는 400 VALIDATION_FAILED다', async (id) => {
    const error = await rejectionOf(getReservation(id))

    expect(error.status).toBe(400)
    expect(error.code).toBe('VALIDATION_FAILED')
  })

  it('payment-slow-confirm: 승인돼도 예매는 계속 HELD이고 선점 만료 시각이 지나도 만료되지 않는다', async () => {
    setMockScenario({ payment: 'slow-confirm' })
    const held = await hold()
    const result = await approvePayment(body(held), newIdempotencyKey())
    expect(result.status).toBe('APPROVED')

    for (let check = 0; check < 40; check++) {
      expect((await getReservation(held.reservationId)).status).toBe('HELD')
    }
    mockDb.reservations.get(held.reservationId)!.holdExpiresAtMillis = Date.now() - 1000
    expect((await getReservation(held.reservationId)).status).toBe('HELD')
  })
})

describe('결제 목: 결제 준비(checkout)', () => {
  it('MOCK 모드로 예매의 금액과 주문명을 준다', async () => {
    const held = await hold()

    const result = await checkoutPayment(held.paymentToken)

    expect(result).toEqual({
      provider: 'MOCK',
      amount: held.totalAmount,
      orderName: `콘서트 예매 ${held.reservationId}`,
      mockBypass: false,
    })
  })

  it('올바르지 않은 토큰은 403 PAYMENT_TOKEN_INVALID다', async () => {
    const error = await rejectionOf(checkoutPayment('garbage'))

    expect(error.status).toBe(403)
    expect(error.code).toBe('PAYMENT_TOKEN_INVALID')
  })

  it('결제 가능 시간이 지난 토큰은 조회 전용 유예 없이 403이다', async () => {
    const held = await hold()
    mockDb.reservations.get(held.reservationId)!.holdExpiresAtMillis = Date.now() + 5000

    const error = await rejectionOf(checkoutPayment(held.paymentToken))

    expect(error.status).toBe(403)
    expect(error.code).toBe('PAYMENT_TOKEN_INVALID')
  })
})
