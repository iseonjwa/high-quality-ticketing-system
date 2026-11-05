import type { ConcertSummary, SaleStatus } from '@/api/concerts'
import { instantMillis } from '@/lib/instant'
import { saleStatusAt } from '@/lib/saleStatus'

// 홈 화면의 공연 묶음. 목록 응답(공연 번호·제목·다음 회차 시각)만으로 나눈다.
// Note: 목록 응답에는 서버 시각이 없어(ISE-99) 브라우저 시계로 나눈다. 예매 가능 여부는 서버 시각이 있는
// 공연 상세에서 다시 정하므로 홈의 묶음이 조금 어긋나도 잘못 예매되지 않는다.

export interface HomeConcert {
  concert: ConcertSummary
  // 다음 회차. 없으면 회차가 모두 끝났거나 아직 등록 전이다(목록 응답으로는 구분할 수 없다)
  next: { startsAt: string; openAt: string } | null
  status: SaleStatus | null
}

export interface HomeSections {
  // 대표 배너. 예매 중(공연이 가까운 순) 다음에 오픈 예정(오픈이 가까운 순)
  featured: HomeConcert[]
  openingSoon: HomeConcert[]
  onSale: HomeConcert[]
  // 다음 회차가 없거나 다음 회차가 이미 시작한 공연
  others: HomeConcert[]
}

export const FEATURED_LIMIT = 4

export function groupHomeConcerts(concerts: ConcertSummary[], nowMillis: number): HomeSections {
  const items = concerts.map((concert): HomeConcert => {
    const next =
      concert.nextStartsAt !== null && concert.nextOpenAt !== null
        ? { startsAt: concert.nextStartsAt, openAt: concert.nextOpenAt }
        : null
    return { concert, next, status: next ? saleStatusAt(next, nowMillis) : null }
  })
  const onSale = items
    .filter((item) => item.status === 'ON_SALE')
    .sort((a, b) => millis(a.next?.startsAt) - millis(b.next?.startsAt))
  const openingSoon = items
    .filter((item) => item.status === 'BEFORE_OPEN')
    .sort((a, b) => millis(a.next?.openAt) - millis(b.next?.openAt))
  const others = items.filter((item) => item.status === null || item.status === 'CLOSED')
  return {
    featured: [...onSale, ...openingSoon].slice(0, FEATURED_LIMIT),
    openingSoon,
    onSale,
    others,
  }
}

function millis(iso: string | undefined): number {
  return iso === undefined ? Number.POSITIVE_INFINITY : instantMillis(iso)
}
