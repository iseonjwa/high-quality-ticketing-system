import type { SaleStatus } from '@/api/concerts'
import { instantMillis } from '@/lib/instant'

// 서버 규칙(Schedule.saleStatusAt)과 같다. 오픈 전 BEFORE_OPEN, 오픈부터 공연 시작 전까지 ON_SALE, 시작한 뒤 CLOSED.
// 응답의 saleStatus는 응답한 순간의 값이다. 화면을 띄워 둔 채 오픈 시각이 지나면 새로고침 없이 바뀌어야 해서
// 서버 시각으로 보정한 지금(ServerClock)으로 다시 정한다. 남은 회차가 없으면(시각이 null) 판매 종료다.
export function saleStatusAt(
  times: { openAt: string | null; startsAt: string | null },
  nowMillis: number,
): SaleStatus {
  if (times.openAt === null || times.startsAt === null) {
    return 'CLOSED'
  }
  if (nowMillis < instantMillis(times.openAt)) {
    return 'BEFORE_OPEN'
  }
  return nowMillis < instantMillis(times.startsAt) ? 'ON_SALE' : 'CLOSED'
}
