import { z } from 'zod'
import { apiRequest } from '@/api/client'
import { isoDateTime } from '@/api/envelope'
import { saveEntryToken } from '@/auth/entryTokens'

// 대기열 API(SA 11-5, ADR 0031). 진입과 순번 조회는 같은 모양을 준다.

const waitingSchema = z.object({
  status: z.literal('WAITING'),
  // max(0, seq - admitted)라 이탈자 번호가 섞여 실제보다 클 수 있다
  position: z.number().int().nonnegative(),
  estimatedWaitSeconds: z.number().int().nonnegative(),
  // 서버가 jitter까지 섞은 다음 조회 간격(ms). 그대로 기다린다. 202의 nextPollAfterSeconds와 단위가 다르다
  nextPollAfterMillis: z.number().int().nonnegative(),
})

// READY면 폴링을 멈추고 토큰 API를 부른다(차례가 됐거나 이미 발급됨)
const readySchema = z.object({ status: z.literal('READY') })

export const queuePositionSchema = z.discriminatedUnion('status', [waitingSchema, readySchema])
export type QueuePosition = z.infer<typeof queuePositionSchema>

export const entryTokenSchema = z.object({
  status: z.literal('ADMITTED'),
  entryToken: z.string().min(1),
  entryTokenExpiresAt: isoDateTime,
})
export type EntryToken = z.infer<typeof entryTokenSchema>

// 새로 섬 201, 이미 줄에 있음 200. 화면은 둘을 구분하지 않는다
export function joinQueue(scheduleId: number) {
  return apiRequest({
    method: 'POST',
    path: `/schedules/${scheduleId}/queue`,
    schema: queuePositionSchema,
  })
}

// 읽기 전용 순번 조회. 줄에 없으면 404 QUEUE_NOT_JOINED
export function getQueuePosition(scheduleId: number) {
  return apiRequest({
    method: 'GET',
    path: `/schedules/${scheduleId}/queue/me`,
    schema: queuePositionSchema,
  })
}

// 입장 토큰을 받아 회차별로 저장한다. 처음 발급과 재발급이 같은 모양이다.
// READY 전이면 409 QUEUE_NOT_READY, 발급 기록이 만료됐으면 404 QUEUE_NOT_JOINED다.
export async function issueEntryToken(scheduleId: number): Promise<EntryToken> {
  const admitted = await apiRequest({
    method: 'POST',
    path: `/schedules/${scheduleId}/queue/token`,
    schema: entryTokenSchema,
  })
  saveEntryToken(scheduleId, {
    token: admitted.entryToken,
    expiresAt: admitted.entryTokenExpiresAt,
  })
  return admitted
}
