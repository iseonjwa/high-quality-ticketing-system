import { z } from 'zod'
import { apiRequest } from '@/api/client'
import { entityId, isoDateTime, pageSchema, won } from '@/api/envelope'

// 공연·좌석 조회(SA 11-5). 공연 목록·상세는 reservation-service ConcertController(ISE-99)를 따른다.
// 시각은 Instant(`Z`)로 오지만 `+09:00`도 받는다. 좌석 조회는 아직 백엔드에 없어 SA 계약대로 둔다.

export const saleStatusSchema = z.enum(['BEFORE_OPEN', 'ON_SALE', 'CLOSED'])
export type SaleStatus = z.infer<typeof saleStatusSchema>

export const concertSummarySchema = z.object({
  concertId: entityId,
  title: z.string(),
  nextStartsAt: isoDateTime.nullable(),
  nextOpenAt: isoDateTime.nullable(),
})

export const scheduleSummarySchema = z.object({
  scheduleId: entityId,
  venueName: z.string(),
  startsAt: isoDateTime,
  openAt: isoDateTime,
  maxPerUser: z.number().int().positive(),
  saleStatus: saleStatusSchema,
})
export type ScheduleSummary = z.infer<typeof scheduleSummarySchema>

export const concertDetailSchema = z.object({
  concertId: entityId,
  title: z.string(),
  // 공연 설명은 등록 때 필수가 아니라 null로 온다(Concert.description)
  description: z.string().nullable(),
  // 오픈 카운트다운을 브라우저 시계가 아니라 서버 시각으로 맞추는 기준(design-system.md 7절)
  serverTime: isoDateTime,
  schedules: z.array(scheduleSummarySchema),
})
export type ConcertDetail = z.infer<typeof concertDetailSchema>

export const seatSchema = z.object({
  seatId: entityId,
  // 행·번호는 정수다(ADR 0033)
  row: z.number().int().positive(),
  number: z.number().int().positive(),
  // HELD·SOLD를 나눠 내려 주지 않는다. 누가 선점 중인지 드러내지 않기 위해서다
  available: z.boolean(),
})

export const seatSectionSchema = z.object({
  section: z.string(),
  grade: z.string(),
  price: won,
  seats: z.array(seatSchema),
})

export const scheduleSeatsSchema = z.object({
  scheduleId: entityId,
  serverTime: isoDateTime,
  sections: z.array(seatSectionSchema),
})
export type ScheduleSeats = z.infer<typeof scheduleSeatsSchema>

export type ConcertSummary = z.infer<typeof concertSummarySchema>

export const concertQueryKeys = {
  list: (page: number) => ['concerts', 'list', page] as const,
  detail: (concertId: number) => ['concerts', 'detail', concertId] as const,
}

export function getConcerts(params: { page?: number; size?: number } = {}) {
  const query = new URLSearchParams({
    page: String(params.page ?? 0),
    size: String(params.size ?? 20),
  })
  return apiRequest({
    method: 'GET',
    path: `/concerts?${query}`,
    schema: pageSchema(concertSummarySchema),
    auth: false,
  })
}

export function getConcert(concertId: number) {
  return apiRequest({
    method: 'GET',
    path: `/concerts/${concertId}`,
    schema: concertDetailSchema,
    auth: false,
  })
}

// 입장 토큰이 필요하다. 저장한 토큰이 없으면 요청 없이 403 ENTRY_TOKEN_INVALID로 끝난다.
export function getScheduleSeats(scheduleId: number) {
  return apiRequest({
    method: 'GET',
    path: `/schedules/${scheduleId}/seats`,
    schema: scheduleSeatsSchema,
    entryScheduleId: scheduleId,
  })
}
