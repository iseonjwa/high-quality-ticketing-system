import { z } from 'zod'
import { apiRequest } from '@/api/client'
import { entityId } from '@/api/envelope'

// 운영자 등록 API(SA 11-5, reservation-service AdminConcertController·AdminScheduleController).
// ADMIN만 쓸 수 있고, USER에게는 Gateway가 403 FORBIDDEN을 준다.

export const seatGradeSchema = z.enum(['VIP', 'R', 'S', 'LOTTERY'])
export type SeatGrade = z.infer<typeof seatGradeSchema>

export const concertCreatedSchema = z.object({ concertId: entityId })
export const scheduleCreatedSchema = z.object({ scheduleId: entityId })
export const scheduleSeatsCreatedSchema = z.object({
  scheduleId: entityId,
  createdSeats: z.number().int().nonnegative(),
})

export interface ConcertCreateInput {
  title: string
  description?: string
}

export interface ScheduleCreateInput {
  venueId: number
  // ISO-8601(Instant). openAt은 startsAt보다 앞서야 한다
  startsAt: string
  openAt: string
  // 비우면 서버 기본값(4, 5)을 쓴다
  maxPerUser?: number
  holdMinutes?: number
}

export interface SeatPriceInput {
  grade: SeatGrade
  price: number
}

export function createConcert(input: ConcertCreateInput) {
  return apiRequest({
    method: 'POST',
    path: '/admin/concerts',
    body: input,
    schema: concertCreatedSchema,
  })
}

export function createSchedule(concertId: number, input: ScheduleCreateInput) {
  return apiRequest({
    method: 'POST',
    path: `/admin/concerts/${concertId}/schedules`,
    body: input,
    schema: scheduleCreatedSchema,
  })
}

// 공연장 좌석 배치를 복사해 회차 좌석을 만든다. 가격 등급은 공연장 좌석의 등급과 정확히 같아야 한다.
export function createScheduleSeats(scheduleId: number, prices: SeatPriceInput[]) {
  return apiRequest({
    method: 'POST',
    path: `/admin/schedules/${scheduleId}/seats`,
    body: { prices },
    schema: scheduleSeatsCreatedSchema,
  })
}
