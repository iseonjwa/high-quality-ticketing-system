import { useQuery } from '@tanstack/react-query'
import { useEffect } from 'react'
import { z } from 'zod'
import { isApiError } from '@/api/apiError'
import { getLottery, lotteryQueryKeys } from '@/api/lotteries'

// 이 브라우저에서 설정한 추첨(회차 번호 → 추첨 번호). 운영자용 추첨 조회 API가 없어 설정 응답을 기억해 둔다.
// 추첨 번호는 비밀값이 아니다. 다른 브라우저에서 설정했으면 모른다(운영 첫 화면의 "추첨 번호로 열기"로 연다).
// Note: 실제 API와 목(dev:mock)은 같은 주소(localhost)를 써서 저장소를 나눠 쓴다. 목은 새로고침하면 번호가 처음부터
// 다시 나가 다른 회차의 기록과 겹친다. 그래서 모드별로 키를 나누고, 쓰기 전에 공개 조회로 맞는지 확인한다.
const KEY = `iseonjwa.adminLotteries.${import.meta.env.MODE}`

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

function writeAll(records: Record<string, number>) {
  localStorage.setItem(KEY, JSON.stringify(records))
}

export function readLotteryIdOfSchedule(scheduleId: number): number | null {
  return readAll()[String(scheduleId)] ?? null
}

export function saveLotteryOfSchedule(scheduleId: number, lotteryId: number) {
  writeAll({ ...readAll(), [String(scheduleId)]: lotteryId })
}

export function forgetLotteryOfSchedule(scheduleId: number) {
  const records = readAll()
  delete records[String(scheduleId)]
  writeAll(records)
}

// 고치기 화면에서 없는 추첨(404)이면 그 번호를 가리키는 기록을 모두 지운다
export function forgetLottery(lotteryId: number) {
  writeAll(
    Object.fromEntries(Object.entries(readAll()).filter(([, recorded]) => recorded !== lotteryId)),
  )
}

export type RecordedLottery =
  { state: 'none' } | { state: 'checking' } | { state: 'found'; lotteryId: number }

// 회차의 기록을 공개 추첨 조회로 확인한다. 추첨이 없거나(404) 다른 회차의 추첨이면 기록을 지우고 없는 것으로 본다.
// 조회가 다른 이유로 실패하면 기록을 믿는다(설정 화면에서 409로 다시 알 수 있다)
export function useRecordedLottery(scheduleId: number): RecordedLottery {
  const recorded = readLotteryIdOfSchedule(scheduleId)
  const lottery = useQuery({
    queryKey: lotteryQueryKeys.info(recorded ?? 0),
    queryFn: () => getLottery(recorded ?? 0),
    enabled: recorded !== null,
  })
  const stale =
    recorded !== null &&
    ((lottery.data !== undefined && lottery.data.scheduleId !== scheduleId) ||
      (lottery.data === undefined && isApiError(lottery.error, 'NOT_FOUND')))

  useEffect(() => {
    if (stale) {
      forgetLotteryOfSchedule(scheduleId)
    }
  }, [stale, scheduleId])

  if (recorded === null || stale) {
    return { state: 'none' }
  }
  if (lottery.data === undefined && !lottery.isError) {
    return { state: 'checking' }
  }
  return { state: 'found', lotteryId: recorded }
}
