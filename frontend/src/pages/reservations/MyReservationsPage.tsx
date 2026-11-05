import { useInfiniteQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import {
  getMyReservations,
  reservationQueryKeys,
  type MyReservation,
  type ReservationStatus,
} from '@/api/reservations'
import { paths, reservationPath } from '@/app/paths'
import { EmptyState } from '@/components/ticketing/EmptyState'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { ReservationStatusBadge } from '@/components/ticketing/ReservationStatusBadge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { formatWon, kstParts } from '@/lib/format'
import { cn } from '@/lib/utils'
import { reservationNavigationState } from '@/pages/reservations/reservationNavigation'

// 내 예매 목록(ISE-117, samples/reservations.html). 서버가 최신순으로 주고 상태 필터는 없다.
// 20건씩 받고 [더 보기]로 다음 쪽을 붙인다(목록 응답에 totalElements만 있어 받은 수와 비교한다)
const PAGE_SIZE = 20

export function MyReservationsPage() {
  const reservations = useInfiniteQuery({
    queryKey: reservationQueryKeys.mine,
    queryFn: ({ pageParam }) => getMyReservations({ page: pageParam, size: PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last) =>
      (last.page + 1) * last.size < last.totalElements ? last.page + 1 : undefined,
  })
  // 서버는 쪽 번호(offset)로 준다. 쪽 사이에 새 예매가 생기면 앞 쪽의 마지막 항목이 다음 쪽에 또 온다. 예매 번호로 거른다
  const items = reservations.data
    ? [
        ...new Map(
          reservations.data.pages
            .flatMap((page) => page.content)
            .map((item) => [item.reservationId, item] as const),
        ).values(),
      ]
    : []
  const listRef = useRef<HTMLUListElement>(null)
  // [더 보기]로 붙인 첫 항목. 마지막 쪽이면 버튼이 사라져 초점이 body로 떨어지므로 그 항목으로 옮긴다
  const focusFromRef = useRef<number | null>(null)
  const [loadedMessage, setLoadedMessage] = useState('')
  useEffect(() => {
    const from = focusFromRef.current
    if (from !== null && items.length > from) {
      listRef.current?.querySelectorAll('a')[from]?.focus()
      setLoadedMessage(`${items.length - from}건 더 불러왔어요`)
      focusFromRef.current = null
    }
  }, [items.length])

  function loadMore() {
    focusFromRef.current = items.length
    void reservations.fetchNextPage()
  }

  return (
    <main className="grid min-w-0 content-start gap-6">
      <div className="grid gap-1">
        <h1 className="type-title text-gray-900">내 예매</h1>
        <p className="type-body-sm text-gray-500">최근에 예매한 순서예요</p>
      </div>
      <p role="status" className="sr-only">
        {loadedMessage}
      </p>
      {reservations.data === undefined && reservations.isPending ? (
        <div role="status" className="grid gap-3">
          <span className="sr-only">내 예매를 불러오고 있어요</span>
          {Array.from({ length: 3 }, (_, index) => (
            <Skeleton key={index} className="h-[92px] w-full" />
          ))}
        </div>
      ) : reservations.data === undefined ? (
        <ErrorNotice error={reservations.error} onRetry={() => void reservations.refetch()} />
      ) : items.length === 0 ? (
        <EmptyState
          title="아직 예매한 공연이 없어요"
          description="공연을 골라 예매해 보세요."
          action={
            <Button asChild>
              <Link to={paths.home}>공연 보러 가기</Link>
            </Button>
          }
        />
      ) : (
        <>
          <ul ref={listRef} className="grid gap-3">
            {items.map((item) => (
              <li key={item.reservationId}>
                <Link
                  to={reservationPath(item.reservationId)}
                  state={reservationNavigationState({
                    title: item.concertTitle,
                    startsAt: item.startsAt,
                    venueName: null,
                  })}
                  className="group block rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                >
                  <TicketRow item={item} />
                </Link>
              </li>
            ))}
          </ul>
          {/* 받은 목록은 그대로 두고, 다음 쪽이나 다시 받기가 실패한 것만 버튼 옆에 알린다 */}
          {reservations.isFetchNextPageError ? (
            <ErrorNotice error={reservations.error} onRetry={loadMore} />
          ) : reservations.isRefetchError ? (
            <ErrorNotice error={reservations.error} onRetry={() => void reservations.refetch()} />
          ) : null}
          {reservations.hasNextPage ? (
            <Button
              variant="outline"
              className="w-full"
              loading={reservations.isFetchingNextPage}
              onClick={loadMore}
            >
              더 보기
            </Button>
          ) : null}
        </>
      )}
    </main>
  )
}

// 상태에 따라 티켓 왼쪽 날짜 칸의 색을 바꾼다. 색만으로 구분하지 않도록 오른쪽에 상태 배지를 함께 둔다
const STUB_TONE: Record<ReservationStatus, string> = {
  CONFIRMED: 'bg-primary-600 text-white',
  HELD: 'bg-warning-50 text-warning-700',
  EXPIRED: 'bg-gray-100 text-gray-500',
  RELEASED: 'bg-gray-100 text-gray-500',
  CANCELED: 'bg-gray-100 text-gray-500',
}

// 티켓 모양 한 줄: 왼쪽 공연 날짜 칸, 절취선, 오른쪽 공연 제목·예매 번호·상태·금액
function TicketRow({ item }: { item: MyReservation }) {
  const date = kstParts(item.startsAt)
  const ended = item.status !== 'CONFIRMED' && item.status !== 'HELD'
  return (
    <article className="relative grid grid-cols-[84px_minmax(0,1fr)] overflow-hidden rounded-lg border border-gray-100 bg-gray-0 shadow-sm transition-shadow duration-(--dur-fast) group-hover:shadow-md @min-[40rem]:grid-cols-[104px_minmax(0,1fr)]">
      <div
        className={cn(
          'grid content-center justify-items-center gap-0.5 py-4 tabular-nums',
          STUB_TONE[item.status],
        )}
      >
        <span className="type-caption opacity-80">
          {date.year}.{date.month}
        </span>
        <span className="text-[28px] leading-8 font-extrabold">{date.day}</span>
        <span className="type-caption opacity-80">
          {date.weekday} {date.hour}:{date.minute}
        </span>
      </div>
      {/* 절취선: 점선과 위아래 반원 홈 */}
      <span
        aria-hidden="true"
        className="pointer-events-none absolute inset-y-0 left-[84px] border-l-2 border-dashed border-gray-200 @min-[40rem]:left-[104px]"
      >
        <span className="absolute -top-2 -left-[9px] size-4 rounded-full border border-gray-100 bg-background" />
        <span className="absolute -bottom-2 -left-[9px] size-4 rounded-full border border-gray-100 bg-background" />
      </span>
      <div className="grid min-w-0 gap-2 py-4 pr-4 pl-5 @min-[40rem]:pr-5 @min-[40rem]:pl-6">
        <div className="flex items-start justify-between gap-3">
          <span
            className={cn(
              'min-w-0 type-heading line-clamp-2',
              ended ? 'text-gray-500' : 'text-gray-900',
            )}
          >
            {item.concertTitle}
          </span>
          <span className="shrink-0">
            <ReservationStatusBadge status={item.status} />
          </span>
        </div>
        <div className="flex flex-wrap items-end justify-between gap-x-4 gap-y-1">
          <span className="type-caption text-gray-500 tabular-nums">
            예매 번호 {item.reservationNo}
          </span>
          <span className="font-bold text-gray-900 tabular-nums">
            {formatWon(item.totalAmount)}
          </span>
        </div>
      </div>
    </article>
  )
}
