import { z } from 'zod'
import { seatGradeSchema, type SeatGrade } from '@/api/admin'
import { apiRequest } from '@/api/client'
import { entityId, isoDateTime } from '@/api/envelope'
import { lotteryStatusSchema } from '@/api/lotteries'

// 운영자 추첨 설정(reservation-service AdminLotteryController). ADMIN만 쓴다. 멱등키가 없다.
// Note: 운영자용 추첨 조회·목록 API가 없다. 추첨 번호는 설정 응답에서만 나오고, 수정 화면은 공개 조회(GET /lotteries/{id})로
// 현재 값을 채운다. 거기에는 뗀 좌석 등급(sourceGrade)·예비 순번 수(waitlistCount)가 없다.

// 추첨 시각이 한국 시간 03:00~05:00 밖이면 오는 경고. 막지 않는다. 모르는 경고도 받아 그대로 보인다
export const DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW = 'DRAW_AT_OUTSIDE_LOW_TRAFFIC_WINDOW'

export const adminLotterySchema = z.object({
  lotteryId: entityId,
  scheduleId: entityId,
  status: lotteryStatusSchema,
  sourceGrade: seatGradeSchema,
  seatCount: z.number().int().positive(),
  waitlistCount: z.number().int().nonnegative(),
  maxPerEntry: z.number().int().min(1).max(2),
  entryStartAt: isoDateTime,
  entryEndAt: isoDateTime,
  drawAt: isoDateTime,
  warnings: z.array(z.string()),
})
export type AdminLottery = z.infer<typeof adminLotterySchema>

export interface LotteryCreateInput {
  // 일반 등급(VIP·R·S)에서 seatCount석을 떼어 추첨석으로 바꾼다
  sourceGrade: Exclude<SeatGrade, 'LOTTERY'>
  seatCount: number
  waitlistCount: number
  maxPerEntry: number
  // ISO-8601. entryStartAt < entryEndAt <= drawAt, drawAt + 60분 <= 회차 시작
  entryStartAt: string
  entryEndAt: string
  drawAt: string
}

// 보낸 필드만 고친다. 응모가 시작됐거나 일반 오픈이 지나면 409 LOTTERY_SETUP_CLOSED다
export type LotteryUpdateInput = Partial<LotteryCreateInput>

export function createLottery(scheduleId: number, input: LotteryCreateInput) {
  return apiRequest({
    method: 'POST',
    path: `/admin/schedules/${scheduleId}/lotteries`,
    body: input,
    schema: adminLotterySchema,
  })
}

export function updateLottery(lotteryId: number, input: LotteryUpdateInput) {
  return apiRequest({
    method: 'PATCH',
    path: `/admin/lotteries/${lotteryId}`,
    body: input,
    schema: adminLotterySchema,
  })
}
