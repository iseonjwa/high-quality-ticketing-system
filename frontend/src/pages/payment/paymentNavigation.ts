import { z } from 'zod'
import { heldSeatHoldSchema, type HeldSeatHold } from '@/api/reservations'
import { heldSeatSchema, type HeldSeat } from '@/auth/heldSeats'
import { readQueueConcert, type QueueConcert } from '@/pages/queue/queueNavigation'

// 좌석 화면의 [결제하러 가기]가 결제 화면(ISE-116)에 넘기는 값이다. 결제 가능 시각(paymentTokenExpiresAt)은
// 선점 응답에만 있고, 공연 요약은 좌석·예매 API에 없어서 화면 이동 값(history state)으로 넘긴다.
// 같은 탭의 새로고침에는 남는다. 없으면 결제 화면이 예매 조회로 다시 만든다.

const paymentNavigationSchema = z.object({
  hold: heldSeatHoldSchema,
  seats: z.array(heldSeatSchema),
  // 서버 시각 - 브라우저 시각(밀리초). 좌석 응답의 serverTime으로 잰 값이고, 없으면 null
  clockOffsetMillis: z.number().nullable().default(null),
})

export interface PaymentNavigation {
  hold: HeldSeatHold
  seats: HeldSeat[]
  concert: QueueConcert | null
  clockOffsetMillis: number | null
}

export function paymentNavigationState({
  hold,
  seats,
  concert,
  clockOffsetMillis,
}: PaymentNavigation) {
  const base = { hold, seats, clockOffsetMillis }
  return concert === null ? base : { ...base, concert }
}

export function readPaymentNavigation(state: unknown): PaymentNavigation | null {
  const parsed = paymentNavigationSchema.safeParse(state)
  if (!parsed.success) {
    return null
  }
  return { ...parsed.data, concert: readQueueConcert(state) }
}
