import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { ChevronRight, Plus } from 'lucide-react'
import { Link } from 'react-router'
import { getConcerts } from '@/api/concerts'
import { adminConcertPath, paths } from '@/app/paths'
import { EmptyState } from '@/components/ticketing/EmptyState'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'

// 운영 첫 화면. 공연을 고르면 회차와 좌석을 만드는 화면으로 간다.
// 공연 목록 API(최근 등록순)를 그대로 쓴다. 운영자 전용 목록 API는 없다
const PAGE_SIZE = 50

export function AdminHomePage() {
  // Note: 사용자 화면 목록(concertQueryKeys.list)과 쪽 크기가 달라 키를 나눈다. 같은 키면 서로의 캐시를 덮는다.
  // 키 첫 칸은 공개 조회와 같은 'concerts'라, 등록 뒤 'concerts' 전체를 새로 받게 하면 두 목록이 함께 바뀐다
  const concerts = useQuery({
    queryKey: ['concerts', 'adminList'] as const,
    queryFn: () => getConcerts({ page: 0, size: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  })

  return (
    <main className="mx-auto grid w-full max-w-[880px] gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="grid gap-1">
          <h1 className="type-title text-gray-900">운영</h1>
          <p className="type-body-sm text-gray-500">공연을 등록하고 회차와 좌석을 만들어요</p>
        </div>
        <Button asChild>
          <Link to={paths.adminConcertNew}>
            <Plus aria-hidden="true" />
            공연 등록
          </Link>
        </Button>
      </div>

      {concerts.isPending ? (
        <Card className="gap-3 px-5" role="status">
          <span className="sr-only">공연 목록을 불러오고 있어요</span>
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </Card>
      ) : concerts.isError ? (
        <ErrorNotice error={concerts.error} onRetry={() => void concerts.refetch()} />
      ) : concerts.data.content.length === 0 ? (
        <EmptyState
          title="아직 등록한 공연이 없어요"
          action={
            <Button asChild variant="outline">
              <Link to={paths.adminConcertNew}>공연 등록</Link>
            </Button>
          }
        />
      ) : (
        <Card className="gap-0 py-0">
          <ul className="divide-y divide-gray-100">
            {concerts.data.content.map((concert) => (
              <li key={concert.concertId}>
                <Link
                  to={adminConcertPath(concert.concertId)}
                  className="flex items-center gap-3 px-5 py-4 outline-none hover:bg-gray-50 focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                >
                  <div className="grid min-w-0 flex-1 gap-0.5">
                    <span className="type-body font-semibold text-gray-900">{concert.title}</span>
                    <span className="type-body-sm text-gray-500 tabular-nums">
                      {concert.nextStartsAt
                        ? `다음 회차 ${formatDateTime(concert.nextStartsAt)}`
                        : '예정된 회차가 없어요'}
                    </span>
                  </div>
                  <span className="type-caption text-gray-500 tabular-nums">
                    #{concert.concertId}
                  </span>
                  <ChevronRight aria-hidden="true" className="size-4 text-gray-400" />
                </Link>
              </li>
            ))}
          </ul>
        </Card>
      )}
      {concerts.data && concerts.data.totalElements > PAGE_SIZE ? (
        <p className="type-caption text-gray-500">
          최근 등록한 {PAGE_SIZE}개만 보여요. 더 오래된 공연은 주소의 공연 번호로 열어 주세요
        </p>
      ) : null}
    </main>
  )
}
