import { z } from 'zod'
import { apiRequest } from '@/api/client'
import { entityId, isoDateTime, pageSchema, won } from '@/api/envelope'

// 선점(`docs/design/reservation/contracts.md`, ADR 0032)과 예매 조회(SA 11-5).

const heldReservationSchema = z.object({
  requestId: z.uuid(),
  reservationId: entityId,
  reservationNo: z.string(),
  scheduleId: entityId,
  // 선점된 좌석, 오름차순
  seatIds: z.array(entityId),
  holdExpiresAt: isoDateTime,
  totalAmount: won,
})

// 선점이 성립한 응답. 결제 화면에 넘기는 값이다
export const heldSeatHoldSchema = heldReservationSchema.extend({
  status: z.literal('HELD'),
  paymentToken: z.string().min(1),
  // 선점 만료보다 10초 이르다(SA 7-3). 결제 가능 시간은 이 값으로 보인다
  paymentTokenExpiresAt: isoDateTime,
})
export type HeldSeatHold = z.infer<typeof heldSeatHoldSchema>

// 동기형 201. 같은 키 재요청 때 이미 결제가 끝났으면 CONFIRMED이고 결제 토큰이 null이다(contracts.md 3-2 ③)
export const seatHoldSchema = z.discriminatedUnion('status', [
  heldSeatHoldSchema,
  heldReservationSchema.extend({
    status: z.literal('CONFIRMED'),
    paymentToken: z.null(),
    paymentTokenExpiresAt: z.null(),
  }),
])
export type SeatHold = z.infer<typeof seatHoldSchema>

export const reservationStatusSchema = z.enum([
  'HELD',
  'CONFIRMED',
  'EXPIRED',
  'RELEASED',
  'CANCELED',
])
export type ReservationStatus = z.infer<typeof reservationStatusSchema>

export const reservationDetailSchema = z.object({
  reservationId: entityId,
  reservationNo: z.string(),
  scheduleId: entityId,
  status: reservationStatusSchema,
  // DB 컬럼이 nullable이라 서버 응답도 null일 수 있다. 선점 때 항상 채우므로 실제로는 늘 값이 있다
  holdExpiresAt: isoDateTime.nullable(),
  totalAmount: won,
  seats: z.array(
    z.object({
      seatId: entityId,
      section: z.string(),
      row: z.number().int().positive(),
      number: z.number().int().positive(),
      grade: z.string(),
      price: won,
    }),
  ),
  // HELD일 때만 있다
  paymentToken: z.string().nullable(),
  // CONFIRMED일 때만 채워진다
  tickets: z.array(z.object({ ticketNo: z.string(), seatId: entityId })),
})
export type ReservationDetail = z.infer<typeof reservationDetailSchema>

export const myReservationSchema = z.object({
  reservationId: entityId,
  reservationNo: z.string(),
  concertTitle: z.string(),
  startsAt: isoDateTime,
  status: reservationStatusSchema,
  totalAmount: won,
})
export type MyReservation = z.infer<typeof myReservationSchema>

// 좌석 일괄 선점. 입장 토큰과 멱등키가 필요하다.
// 409 SEAT_ALREADY_HELD면 좌석을 다시 조회해 고르고, 좌석이 바뀌었으니 새 멱등키로 보낸다.
// Note: 접수형 202(`REQUESTED`)는 M4 고도화 모드라 ISE-120에서 응답 스키마에 더한다.
export function holdSeats(
  scheduleId: number,
  seatIds: number[],
  idempotencyKey: string,
  options: { signal?: AbortSignal } = {},
) {
  return apiRequest({
    method: 'POST',
    path: `/schedules/${scheduleId}/reservations`,
    body: { seatIds },
    schema: seatHoldSchema,
    idempotencyKey,
    entryScheduleId: scheduleId,
    signal: options.signal,
  })
}

// 예매 조회 캐시 키. 'reservations'는 사용자별 조회라 로그아웃 때 지운다(resetUserQueries)
export const reservationQueryKeys = {
  detail: (reservationId: number) => ['reservations', 'detail', reservationId] as const,
  mine: ['reservations', 'mine'] as const,
}

export function getReservation(reservationId: number, options: { signal?: AbortSignal } = {}) {
  return apiRequest({
    method: 'GET',
    path: `/reservations/${reservationId}`,
    schema: reservationDetailSchema,
    signal: options.signal,
  })
}

export function getMyReservations(params: { page?: number; size?: number } = {}) {
  const query = new URLSearchParams({
    page: String(params.page ?? 0),
    size: String(params.size ?? 20),
  })
  return apiRequest({
    method: 'GET',
    path: `/reservations/me?${query}`,
    schema: pageSchema(myReservationSchema),
  })
}
