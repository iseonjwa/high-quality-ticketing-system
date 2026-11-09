import { z } from 'zod'
import { entityId, isoDateTime } from '@/api/envelope'

// 예매 상세·결과 화면으로 넘기는 공연 제목 줄이다. 예매 상세 응답에는 공연 제목·일시가 없어서
// 결제 화면(공연 요약)과 내 예매 목록(공연 제목·시작 시각)이 가진 값을 화면 이동 값으로 넘긴다.
// 없으면(주소로 들어옴) 예매 번호를 제목으로 쓴다.
// concertId는 결제에서 넘어올 때만 있다(내 예매 목록 응답에는 공연 번호가 없다). 있으면 결과 티켓에 포스터를 붙인다.

const reservationHeadingSchema = z.object({
  title: z.string().min(1),
  startsAt: isoDateTime,
  venueName: z.string().nullable(),
  concertId: entityId.optional(),
})
export type ReservationHeading = z.infer<typeof reservationHeadingSchema>

const reservationNavigationSchema = z.object({ heading: reservationHeadingSchema })

export function reservationNavigationState(heading: ReservationHeading | null) {
  return heading === null ? {} : { heading }
}

export function readReservationHeading(state: unknown): ReservationHeading | null {
  const parsed = reservationNavigationSchema.safeParse(state)
  return parsed.success ? parsed.data.heading : null
}
