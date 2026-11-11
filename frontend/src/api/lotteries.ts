import { z } from 'zod'
import { isApiError } from '@/api/apiError'
import { apiRequest } from '@/api/client'
import { entityId, isoDateTime } from '@/api/envelope'

// 추첨 정보·응모·취소·내 결과(reservation-service LotteryController, `docs/design/lottery/`).
// 추첨 정보는 공개 조회다. 응모는 동기 201이다(SA 문서의 202가 아니다). 입장 토큰·대기열을 거치지 않는다.

export const lotteryStatusSchema = z.enum(['OPEN', 'CLOSED', 'DRAWN', 'DONE'])
export type LotteryStatus = z.infer<typeof lotteryStatusSchema>

export const lotterySchema = z.object({
  lotteryId: entityId,
  scheduleId: entityId,
  // 응모 시작 전에도 OPEN이다. 응모 가능 여부는 기간으로 본다
  status: lotteryStatusSchema,
  seatCount: z.number().int().positive(),
  entryStartAt: isoDateTime,
  entryEndAt: isoDateTime,
  drawAt: isoDateTime,
  rules: z.object({
    minPerEntry: z.number().int().positive(),
    maxPerEntry: z.number().int().positive(),
    adjacentSeatsGuaranteed: z.boolean(),
    reentryAllowed: z.boolean(),
    billingKeyRequired: z.boolean(),
  }),
})
export type Lottery = z.infer<typeof lotterySchema>

export const entryStatusSchema = z.enum([
  'ENTERED',
  'CANCELED',
  'WON',
  'WAITLISTED',
  'LOST',
  'PAID',
  'FAILED',
])
export type EntryStatus = z.infer<typeof entryStatusSchema>

export const entrySchema = z.object({
  entryId: entityId,
  lotteryId: entityId,
  status: entryStatusSchema,
  quantity: z.number().int().positive(),
  // 추첨 전 null. 당첨자는 순위, 예비자는 승계 순번이다
  drawRank: z.number().int().positive().nullable(),
  // 당첨 뒤 좌석이 배정되면 채워진다. 결제 재시도로 비워질 수 있다
  reservationId: entityId.nullable(),
  // FAILED일 때 사유(결제 거절 사유, HOLD_EXPIRED, NO_SEAT, ASSIGN_ERROR)
  failReason: z.string().nullable(),
})
export type LotteryEntry = z.infer<typeof entrySchema>

export const lotteryQueryKeys = {
  info: (lotteryId: number) => ['lotteries', 'info', lotteryId] as const,
  ofSchedule: (scheduleId: number) => ['lotteries', 'ofSchedule', scheduleId] as const,
  myEntry: (lotteryId: number) => ['lotteries', 'myEntry', lotteryId] as const,
}

export function getLottery(lotteryId: number) {
  return apiRequest({
    method: 'GET',
    path: `/lotteries/${lotteryId}`,
    schema: lotterySchema,
    auth: false,
  })
}

// 회차의 추첨(ISE-227, ScheduleLotteryController). 공개 조회이고 응답은 getLottery와 같다.
// 추첨이 없는 회차(지운 추첨 포함)는 404라 화면이 다루기 쉽게 null로 돌려준다
export async function getScheduleLottery(scheduleId: number): Promise<Lottery | null> {
  try {
    return await apiRequest({
      method: 'GET',
      path: `/schedules/${scheduleId}/lottery`,
      schema: lotterySchema,
      auth: false,
    })
  } catch (error) {
    if (isApiError(error, 'NOT_FOUND')) {
      return null
    }
    throw error
  }
}

// 응모하지 않았으면 404다. 화면이 다루기 쉽게 null로 돌려준다
export async function getMyEntry(lotteryId: number): Promise<LotteryEntry | null> {
  try {
    return await apiRequest({
      method: 'GET',
      path: `/lotteries/${lotteryId}/entries/me`,
      schema: entrySchema,
    })
  } catch (error) {
    if (isApiError(error, 'NOT_FOUND')) {
      return null
    }
    throw error
  }
}

// 응모. 멱등키는 서버에서 사용자별이 아니라 전역이다(같은 키·같은 내용이면 그 응모의 지금 상태를 201로 준다)
export function enterLottery(lotteryId: number, quantity: number, idempotencyKey: string) {
  return apiRequest({
    method: 'POST',
    path: `/lotteries/${lotteryId}/entries`,
    body: { quantity },
    schema: entrySchema,
    idempotencyKey,
  })
}

// 응모 취소. 마감 전 ENTERED만 된다. 이미 취소했어도 200이다
export function cancelEntry(lotteryId: number) {
  return apiRequest({
    method: 'DELETE',
    path: `/lotteries/${lotteryId}/entries/me`,
    schema: entrySchema,
  })
}
