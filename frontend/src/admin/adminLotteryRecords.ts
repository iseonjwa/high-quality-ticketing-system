import { z } from 'zod'

// 이 브라우저에서 설정한 추첨(회차 번호 → 추첨 번호). 운영자용 추첨 조회 API가 없어 설정 응답을 기억해 둔다.
// 추첨 번호는 비밀값이 아니다. 다른 브라우저에서 설정했으면 모른다(운영 첫 화면의 "추첨 번호로 열기"로 연다).
const KEY = 'iseonjwa.adminLotteries'

const recordsSchema = z.record(z.string().regex(/^\d+$/), z.number().int().positive())

function readAll(): Record<string, number> {
  const raw = localStorage.getItem(KEY)
  if (raw === null) {
    return {}
  }
  try {
    const parsed = recordsSchema.safeParse(JSON.parse(raw))
    if (parsed.success) {
      return parsed.data
    }
  } catch {
    // 깨진 값은 아래에서 지운다
  }
  localStorage.removeItem(KEY)
  return {}
}

export function readLotteryIdOfSchedule(scheduleId: number): number | null {
  return readAll()[String(scheduleId)] ?? null
}

export function saveLotteryOfSchedule(scheduleId: number, lotteryId: number) {
  localStorage.setItem(KEY, JSON.stringify({ ...readAll(), [String(scheduleId)]: lotteryId }))
}
