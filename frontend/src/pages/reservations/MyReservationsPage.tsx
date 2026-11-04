import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { getMyReservations, reservationQueryKeys } from '@/api/reservations'
import { paths, reservationPath } from '@/app/paths'
import { EmptyState } from '@/components/ticketing/EmptyState'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { ReservationStatusBadge } from '@/components/ticketing/ReservationStatusBadge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime, formatWon } from '@/lib/format'
import { reservationNavigationState } from '@/pages/reservations/reservationNavigation'

// 내 예매 목록(ISE-117, samples/reservations.html). 서버가 최신순으로 주고 상태 필터는 없다.
// 20건씩 받고 [더 보기]로 다음 쪽을 붙인다(목록 응답에 totalElements만 있어 받은 수와 비교한다)
const PAGE_SIZE = 20

export function MyReservationsPage() {
  const reservations = useInfiniteQuery({
    queryKey: reservationQueryKeys.mine,
    queryFn: ({ pageParam }) => getMyReservations({ page: pageParam, size: PAGE_SIZE }),
    initialPageParam: 0,
    getNextPageParam: (last, pages) => {
      const loaded = pages.reduce((sum, page) => sum + page.content.length, 0)
      return loaded < last.totalElements && last.content.length > 0 ? last.page + 1 : undefined
    },
  })

  return (
    <main className="mx-auto grid w-full max-w-[720px] gap-5">
      <h1 className="type-title text-gray-900">내 예매</h1>
      {reservations.isPending ? (
        <div role="status" className="grid gap-3">
          <span className="sr-only">내 예매를 불러오고 있어요</span>
          {Array.from({ length: 3 }, (_, index) => (
            <Skeleton key={index} className="h-[92px] w-full" />
          ))}
        </div>
      ) : reservations.isError ? (
        <ErrorNotice error={reservations.error} onRetry={() => void reservations.refetch()} />
      ) : reservations.data.pages[0].content.length === 0 ? (
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
          <ul className="grid gap-3">
            {reservations.data.pages
              .flatMap((page) => page.content)
              .map((item) => (
                <li key={item.reservationId}>
                  <Link
                    to={reservationPath(item.reservationId)}
                    state={reservationNavigationState({
                      title: item.concertTitle,
                      startsAt: item.startsAt,
                      venueName: null,
                    })}
                    className="block rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-ring"
                  >
                    <Card className="grid grid-cols-[1fr_auto] items-center gap-x-4 gap-y-1 px-4 py-4 transition-colors hover:border-gray-200 @min-[40rem]:px-5 @min-[40rem]:py-5">
                      <span className="min-w-0 type-heading text-gray-900">
                        {item.concertTitle}
                      </span>
                      <span className="justify-self-end">
                        <ReservationStatusBadge status={item.status} />
                      </span>
                      <span className="type-body-sm text-gray-500 tabular-nums">
                        {formatDateTime(item.startsAt)} · {item.reservationNo}
                      </span>
                      <span className="justify-self-end font-bold text-gray-900 tabular-nums">
                        {formatWon(item.totalAmount)}
                      </span>
                    </Card>
                  </Link>
                </li>
              ))}
          </ul>
          {reservations.hasNextPage ? (
            <Button
              variant="outline"
              className="w-full"
              loading={reservations.isFetchingNextPage}
              onClick={() => void reservations.fetchNextPage()}
            >
              더 보기
            </Button>
          ) : null}
        </>
      )}
    </main>
  )
}
