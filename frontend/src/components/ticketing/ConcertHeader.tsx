import { ConcertPoster } from '@/components/ticketing/ConcertPoster'
import { Card } from '@/components/ui/card'
import { formatDateTime } from '@/lib/format'
import type { QueueConcert } from '@/pages/queue/queueNavigation'

// 예매 흐름 화면(좌석·결제) 맨 위의 공연 카드. 공연 상세가 화면 이동 값으로 넘긴 공연 요약을 보인다.
// 화면의 h1이다. 공연 요약이 없으면 화면이 sr-only 제목을 따로 둔다
export function ConcertHeader({ concert }: { concert: QueueConcert }) {
  return (
    <Card className="flex-row items-center gap-4 px-4 py-4 @min-[40rem]:gap-5 @min-[40rem]:px-5 @min-[40rem]:py-5">
      <ConcertPoster concertId={concert.concertId} className="h-[72px] w-14" />
      <div className="min-w-0">
        <h1 className="type-title text-gray-900 @max-[40rem]:text-[20px] @max-[40rem]:leading-[28px]">
          {concert.title}
        </h1>
        <p className="mt-1 type-body-sm text-gray-500 tabular-nums">
          {formatDateTime(concert.startsAt)} · {concert.venueName}
        </p>
      </div>
    </Card>
  )
}
