import { z } from 'zod'
import { isoDateTime } from '@/api/envelope'

// 공연 상세의 [예매하기]가 대기열 화면에 넘기는 공연 요약이다. 대기열 API에는 공연 정보가 없어서
// 무엇을 기다리는지 보이려고 화면 이동 값(history state)으로 넘긴다. 같은 탭의 새로고침에는 남고,
// 주소만 새 탭으로 열거나 로그인 화면을 거쳐 오면 없다. 없으면 대기열 화면은 공연 줄 없이 그린다.

const queueConcertSchema = z.object({
  concertId: z.number().int().positive(),
  title: z.string().min(1),
  startsAt: isoDateTime,
  venueName: z.string(),
})
export type QueueConcert = z.infer<typeof queueConcertSchema>

const queueNavigationStateSchema = z.object({ concert: queueConcertSchema })

export function queueNavigationState(concert: QueueConcert) {
  return { concert }
}

export function readQueueConcert(state: unknown): QueueConcert | null {
  const parsed = queueNavigationStateSchema.safeParse(state)
  return parsed.success ? parsed.data.concert : null
}
