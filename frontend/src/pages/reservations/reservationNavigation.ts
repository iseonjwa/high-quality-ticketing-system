import { z } from 'zod'
import { readQueueConcert, type QueueConcert } from '@/pages/queue/queueNavigation'

// 예매 상세(결과) 화면으로 넘기는 값이다. 예매 상세 응답에는 공연 제목이 없어 결제 화면이 가진 공연 요약을
// 화면 이동 값으로 넘긴다. fromPayment면 결제에서 막 확정된 결과로 보인다(예매 단계 "완료").

const reservationNavigationSchema = z.object({ fromPayment: z.boolean() })

export interface ReservationNavigation {
  concert: QueueConcert | null
  fromPayment: boolean
}

export function reservationNavigationState({ concert, fromPayment }: ReservationNavigation) {
  return concert === null ? { fromPayment } : { fromPayment, concert }
}

export function readReservationNavigation(state: unknown): ReservationNavigation {
  const parsed = reservationNavigationSchema.safeParse(state)
  return {
    concert: readQueueConcert(state),
    fromPayment: parsed.success ? parsed.data.fromPayment : false,
  }
}
