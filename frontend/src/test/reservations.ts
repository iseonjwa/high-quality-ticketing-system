import { mockDb, nextMockId, type MockPayment, type MockReservation } from '@/mocks/db'
import { ON_SALE_SCHEDULE_ID, SEAT_A1, SEAT_A2 } from '@/test/api'

// 예매 화면 테스트가 목 서버에 예매를 바로 만드는 도우미. 로그인한 데모 사용자(42)의 예매다.
// 좌석 두 개(330,000원)를 기본으로 하고, 선점 만료는 결제 가능 시간이 한참 남은 때다.
const DEMO_USER_ID = 42

export function seedReservation(fields: Partial<MockReservation> = {}): MockReservation {
  const reservationId = fields.reservationId ?? nextMockId()
  const reservation: MockReservation = {
    reservationId,
    reservationNo: `R20261102-${String(reservationId).padStart(6, '0')}`,
    requestId: crypto.randomUUID(),
    userId: DEMO_USER_ID,
    scheduleId: ON_SALE_SCHEDULE_ID,
    seatIds: [SEAT_A1, SEAT_A2],
    status: 'HELD',
    holdExpiresAtMillis: Date.now() + 5 * 60_000,
    totalAmount: 330000,
    confirmAfterChecks: null,
    ...fields,
  }
  mockDb.reservations.set(reservationId, reservation)
  return reservation
}

// 예매에 묶인 승인된 결제를 목 서버에 바로 만든다(확정·취소 예매의 paymentId와 환불 상태 화면용)
export function seedPayment(
  reservation: MockReservation,
  fields: Partial<MockPayment> = {},
): MockPayment {
  const paymentId = fields.paymentId ?? nextMockId()
  const payment: MockPayment = {
    paymentId,
    reservationId: reservation.reservationId,
    userId: reservation.userId,
    status: 'APPROVED',
    amount: reservation.totalAmount,
    approvedAtMillis: Date.now(),
    reasonCode: null,
    checksUntilResolved: 0,
    ...fields,
  }
  mockDb.payments.set(paymentId, payment)
  return payment
}
