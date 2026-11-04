import { z } from 'zod'
import { entityId, won } from '@/api/envelope'
import { heldSeatHoldSchema, type HeldSeatHold } from '@/api/reservations'
import { readQueueConcert, type QueueConcert } from '@/pages/queue/queueNavigation'

// 좌석 화면의 [결제하러 가기]가 결제 화면(ISE-116)에 넘기는 값이다. 결제 가능 시각(paymentTokenExpiresAt)은
// 선점 응답에만 있고, 공연 요약은 좌석·예매 API에 없어서 화면 이동 값(history state)으로 넘긴다.
// 같은 탭의 새로고침에는 남는다. 없으면 결제 화면이 예매 조회로 다시 만든다.

const paymentSeatSchema = z.object({
  seatId: entityId,
  section: z.string(),
  grade: z.string(),
  price: won,
  row: z.number().int().positive(),
  number: z.number().int().positive(),
})
export type PaymentSeat = z.infer<typeof paymentSeatSchema>

const paymentNavigationSchema = z.object({
  hold: heldSeatHoldSchema,
  seats: z.array(paymentSeatSchema),
})

export interface PaymentNavigation {
  hold: HeldSeatHold
  seats: PaymentSeat[]
  concert: QueueConcert | null
}

export function paymentNavigationState({ hold, seats, concert }: PaymentNavigation) {
  return concert === null ? { hold, seats } : { hold, seats, concert }
}

export function readPaymentNavigation(state: unknown): PaymentNavigation | null {
  const parsed = paymentNavigationSchema.safeParse(state)
  if (!parsed.success) {
    return null
  }
  return { ...parsed.data, concert: readQueueConcert(state) }
}
