import { z } from 'zod'
import { apiRequest } from '@/api/client'
import { entityId, isoDateTime, won } from '@/api/envelope'

// 결제 API(`docs/design/payment/api.md`). 상태마다 필드가 다르다(PaymentView, 표 A).

const pendingSchema = z.object({
  paymentId: entityId,
  reservationId: entityId,
  // 둘 다 "확인 중"이다. 실패로 다루지 않는다
  status: z.enum(['REQUESTED', 'UNKNOWN']),
  nextPollAfterSeconds: z.number().int().positive(),
})

const approvedSchema = z.object({
  paymentId: entityId,
  reservationId: entityId,
  status: z.literal('APPROVED'),
  amount: won,
  // 결제 시각은 Instant라 `Z`(UTC)로 온다. isoDateTime이 오프셋과 Z를 모두 받는다
  approvedAt: isoDateTime,
})

const declinedSchema = z.object({
  paymentId: entityId,
  reservationId: entityId,
  status: z.literal('DECLINED'),
  // 우리 DeclineReason 값. 결제 거절은 에러가 아니라 200이다(SA 11-1)
  reasonCode: z.string(),
})

const refundedSchema = z.object({
  paymentId: entityId,
  reservationId: entityId,
  status: z.literal('REFUNDED'),
  amount: won,
  refundedAt: isoDateTime,
})

// POST 응답: 승인·거절 200, 확인 중 202
export const paymentResultSchema = z.discriminatedUnion('status', [
  pendingSchema,
  approvedSchema,
  declinedSchema,
  refundedSchema,
])
export type PaymentResult = z.infer<typeof paymentResultSchema>

export const paymentStatusSchema = z.enum([
  'REQUESTED',
  'UNKNOWN',
  'APPROVED',
  'NEEDS_MANUAL_CHECK',
  'DECLINED',
  'REFUNDED',
])

// GET 응답은 여섯 필드를 항상 준다(api.md 2절)
export const paymentDetailSchema = z.object({
  paymentId: entityId,
  reservationId: entityId,
  status: paymentStatusSchema,
  amount: won,
  approvedAt: isoDateTime.nullable(),
  refundedAt: isoDateTime.nullable(),
})
export type PaymentDetail = z.infer<typeof paymentDetailSchema>

// 결제 승인. 응답을 못 받았거나 504면(isOutcomeUnknown) 같은 멱등키와 같은 본문으로 다시 보낸다.
// 서버는 PG를 다시 부르지 않고 처음 응답을 그대로 돌려준다. 결제 토큰 원문도 본문 비교에 들어가므로,
// 예매 조회로 토큰을 다시 받았다면 옛 키로 보내면 409 IDEMPOTENCY_KEY_REUSED다. 그때는 새 키로 보낸다
// (같은 예매의 기존 결제에 연결돼 PG를 다시 부르지 않는다, api.md 표 B).
// APPROVED는 돈이 승인됐다는 뜻이고 예매 확정은 getReservation이 CONFIRMED가 될 때까지 다시 조회해 확인한다.
export function approvePayment(
  body: { paymentToken: string; amount: number; method: 'CARD' },
  idempotencyKey: string,
  options: { signal?: AbortSignal } = {},
) {
  return apiRequest({
    method: 'POST',
    path: '/payments',
    body,
    schema: paymentResultSchema,
    idempotencyKey,
    signal: options.signal,
  })
}

export function getPayment(paymentId: number, options: { signal?: AbortSignal } = {}) {
  return apiRequest({
    method: 'GET',
    path: `/payments/${paymentId}`,
    schema: paymentDetailSchema,
    signal: options.signal,
  })
}
