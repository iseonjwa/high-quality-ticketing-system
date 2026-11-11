import type { Lottery } from '@/api/lotteries'
import { instantMillis } from '@/lib/instant'

// beforeEntry 응모 시작 전, entry 응모 중, afterEntry 마감 뒤 추첨 전, drawing 추첨 시각이 지났지만 아직 추첨 전 상태,
// drawn 추첨 완료(배정·승계 중), done 종료
export type LotteryPhase = 'beforeEntry' | 'entry' | 'afterEntry' | 'drawing' | 'drawn' | 'done'

// 추첨 화면과 공연 상세의 추첨 안내가 같은 기준으로 단계를 나눈다. 응모 시작 전에도 상태는 OPEN이라 기간으로 가른다.
// 응모·취소 가능 여부는 서버가 정한다(LOTTERY_NOT_OPEN). now는 부르는 화면이 정한 시계의 밀리초다
export function lotteryPhaseOf(lottery: Lottery, now: number): LotteryPhase {
  if (lottery.status === 'DONE') {
    return 'done'
  }
  if (lottery.status === 'DRAWN') {
    return 'drawn'
  }
  if (lottery.status === 'OPEN' && now < instantMillis(lottery.entryStartAt)) {
    return 'beforeEntry'
  }
  if (lottery.status === 'OPEN' && now < instantMillis(lottery.entryEndAt)) {
    return 'entry'
  }
  return now < instantMillis(lottery.drawAt) ? 'afterEntry' : 'drawing'
}
