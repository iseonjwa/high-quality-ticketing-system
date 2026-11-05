import { http } from 'msw'
import {
  mockDb,
  nextMockId,
  PAYMENT_TOKEN_MARGIN_MILLIS,
  releaseSeats,
  scheduleConfirmation,
  settleExpiredHolds,
  type MockPayment,
  type MockReservation,
  type StoredPaymentResponse,
} from '@/mocks/db'
import { fail, ok, readBody, requireIdempotencyKey, requireUser } from '@/mocks/respond'
import { mockScenario } from '@/mocks/scenario'

// 결제 승인·조회(`docs/design/payment/api.md`). 처리 순서는 api.md 1절 표, 상태별 응답 모양은 PaymentView(표 A)다.

// 실제 서버는 10초다. 목은 확인 중 화면을 빠르게 보려고 2초로 둔다
const MOCK_POLL_SECONDS = 2

// 결제의 현재 상태로 만든 응답(표 A)
function currentResult(payment: MockPayment): StoredPaymentResponse {
  const base = {
    paymentId: payment.paymentId,
    reservationId: payment.reservationId,
    status: payment.status,
  }
  switch (payment.status) {
    case 'UNKNOWN':
      return {
        status: 202,
        data: { ...base, nextPollAfterSeconds: MOCK_POLL_SECONDS },
        headers: { Location: `/api/v1/payments/${payment.paymentId}` },
      }
    case 'APPROVED':
      return {
        status: 200,
        data: {
          ...base,
          amount: payment.amount,
          // Instant라 `Z`(UTC)로 나간다
          approvedAt: new Date(payment.approvedAtMillis!).toISOString(),
        },
      }
    case 'DECLINED':
      return { status: 200, data: { ...base, reasonCode: payment.reasonCode } }
    case 'NEEDS_MANUAL_CHECK':
      throw new Error('NEEDS_MANUAL_CHECK는 응답 본문이 없다. respondCurrent를 쓴다')
  }
}

function respond(result: StoredPaymentResponse) {
  return ok(result.data, { status: result.status, headers: result.headers })
}

function approve(payment: MockPayment, reservation: MockReservation) {
  payment.status = 'APPROVED'
  payment.approvedAtMillis = Date.now()
  if (mockScenario().payment === 'late-approval') {
    // 승인이 선점 만료 뒤에 도착한 경우다. 예매는 EXPIRED가 되고 좌석은 풀린다(환불은 서버가 맡는다)
    reservation.status = 'EXPIRED'
    reservation.confirmAfterChecks = null
    releaseSeats(reservation.seatIds)
    return
  }
  // slow-confirm은 확정 이벤트가 오지 않는 경우다. 예매가 계속 HELD이고 선점 만료로도 바뀌지 않는다
  scheduleConfirmation(
    reservation,
    mockScenario().payment === 'slow-confirm' ? Number.POSITIVE_INFINITY : 1,
  )
}

// 결제의 현재 상태로 답한다. NEEDS_MANUAL_CHECK는 응답 모양이 에러(409)다(PaymentOutcome.of)
function respondCurrent(payment: MockPayment) {
  if (payment.status === 'NEEDS_MANUAL_CHECK') {
    return fail(409, 'PAYMENT_ALREADY_EXISTS', '이미 처리 중인 결제가 있습니다', {
      headers: { Location: `/api/v1/payments/${payment.paymentId}` },
    })
  }
  return respond(currentResult(payment))
}

// 결제 토큰 `mock-payment.{reservationId}.{발급 번호}`의 예매를 찾는다. 모양이 틀렸거나 남의 예매면 403이다.
function reservationOfToken(paymentToken: unknown, userId: number): MockReservation {
  const parts = typeof paymentToken === 'string' ? paymentToken.split('.') : []
  const reservation = mockDb.reservations.get(Number(parts[1]))
  if (
    parts.length !== 3 ||
    parts[0] !== 'mock-payment' ||
    reservation === undefined ||
    reservation.userId !== userId
  ) {
    throw fail(403, 'PAYMENT_TOKEN_INVALID', '결제 토큰이 올바르지 않습니다')
  }
  return reservation
}

function existingPaymentOf(reservationId: number) {
  return [...mockDb.payments.values()].find((payment) => payment.reservationId === reservationId)
}

export const paymentHandlers = [
  http.post('/api/v1/payments', async ({ request }) => {
    const user = requireUser(request)
    const key = `${user.userId}:${requireIdempotencyKey(request)}`
    const body = await readBody(request)
    if (body.method !== 'CARD' || !Number.isInteger(body.amount) || (body.amount as number) <= 0) {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다')
    }
    settleExpiredHolds()

    // 3단계: 같은 키 재요청은 토큰 검증보다 먼저 본다. 비교에 토큰 원문이 들어간다(PaymentRequestHash).
    // 처음 요청이 만든 결제면 그때 저장한 응답을 그대로 돌려준다. 202 뒤 승인됐어도 같은 키 POST는 202다.
    const bodyKey = JSON.stringify([body.paymentToken, body.amount])
    const previous = mockDb.paymentRequests.get(key)
    if (previous !== undefined) {
      if (previous.userId !== user.userId || previous.bodyKey !== bodyKey) {
        return fail(409, 'IDEMPOTENCY_KEY_REUSED', '같은 멱등키로 다른 요청을 보낼 수 없습니다')
      }
      return previous.storedResponse === null
        ? respondCurrent(mockDb.payments.get(previous.paymentId)!)
        : respond(previous.storedResponse)
    }

    // 4단계: 토큰·금액. 결제 가능 시간이 지난 토큰은 기존 결제를 찾는 데만 쓴다(조회 전용)
    const reservation = reservationOfToken(body.paymentToken, user.userId)
    if (body.amount !== reservation.totalAmount) {
      return fail(400, 'PAYMENT_AMOUNT_MISMATCH', '결제 금액이 토큰의 금액과 다릅니다')
    }
    const tokenExpired = Date.now() >= reservation.holdExpiresAtMillis - PAYMENT_TOKEN_MARGIN_MILLIS

    // 5단계: 같은 예매의 기존 결제(표 B)
    const existing = existingPaymentOf(reservation.reservationId)
    if (existing?.status === 'DECLINED') {
      return fail(
        409,
        'PAYMENT_ALREADY_CLOSED',
        '이 예매의 결제는 종료됐습니다. 새로 선점해 다시 시도하세요',
      )
    }
    if (existing !== undefined) {
      // 새 키를 기존 결제의 별칭으로 기록하고 현재 상태로 답한다(응답은 저장하지 않는다)
      mockDb.paymentRequests.set(key, {
        userId: user.userId,
        bodyKey,
        paymentId: existing.paymentId,
        storedResponse: null,
      })
      return respondCurrent(existing)
    }
    if (tokenExpired) {
      return fail(403, 'PAYMENT_TOKEN_INVALID', '결제 토큰이 올바르지 않습니다')
    }

    // 6단계: 결제사 허가. 못 얻으면 결제 행도 멱등 기록도 남지 않아 같은 키로 다시 보내면 새로 시도한다
    const scenario = mockScenario().payment
    if (scenario === 'pg-unavailable') {
      return fail(503, 'PG_UNAVAILABLE', '결제 대행사를 일시적으로 사용할 수 없습니다')
    }

    const payment: MockPayment = {
      paymentId: nextMockId(),
      reservationId: reservation.reservationId,
      userId: user.userId,
      status: 'UNKNOWN',
      amount: reservation.totalAmount,
      approvedAtMillis: null,
      reasonCode: null,
      checksUntilResolved: 1,
    }
    if (scenario === 'manual') {
      // PG가 다른 금액을 승인한 것처럼 운영자 확인이 필요한 상태로 둔다. 409 에러라 응답은 저장되지 않는다
      payment.status = 'NEEDS_MANUAL_CHECK'
      mockDb.payments.set(payment.paymentId, payment)
      mockDb.paymentRequests.set(key, {
        userId: user.userId,
        bodyKey,
        paymentId: payment.paymentId,
        storedResponse: null,
      })
      return respondCurrent(payment)
    }
    if (scenario === 'approved' || scenario === 'slow-confirm' || scenario === 'late-approval') {
      approve(payment, reservation)
    } else if (scenario === 'declined') {
      payment.status = 'DECLINED'
      payment.reasonCode = 'INSUFFICIENT_LIMIT'
      // 거절되면 이 선점으로는 다시 결제할 수 없어 좌석을 돌려준다(PAYMENT_ALREADY_CLOSED)
      reservation.status = 'RELEASED'
      releaseSeats(reservation.seatIds)
    }
    const result = currentResult(payment)
    mockDb.payments.set(payment.paymentId, payment)
    mockDb.paymentRequests.set(key, {
      userId: user.userId,
      bodyKey,
      paymentId: payment.paymentId,
      storedResponse: result,
    })
    return respond(result)
  }),

  http.get('/api/v1/payments/:paymentId', ({ request, params }) => {
    const user = requireUser(request)
    const payment = mockDb.payments.get(Number(params.paymentId))
    // 남의 결제와 없는 결제는 같은 404다(ADR 0021)
    if (payment === undefined || payment.userId !== user.userId) {
      return fail(404, 'NOT_FOUND', '대상을 찾을 수 없습니다')
    }
    if (payment.status === 'UNKNOWN') {
      payment.checksUntilResolved -= 1
      if (payment.checksUntilResolved <= 0) {
        approve(payment, mockDb.reservations.get(payment.reservationId)!)
      }
    }
    // 여섯 필드를 항상 내보낸다(PaymentDetail)
    return ok(
      {
        paymentId: payment.paymentId,
        reservationId: payment.reservationId,
        status: payment.status,
        amount: payment.amount,
        approvedAt:
          payment.approvedAtMillis === null
            ? null
            : new Date(payment.approvedAtMillis).toISOString(),
        refundedAt: null,
      },
      { message: '결제를 조회했습니다' },
    )
  }),
]
