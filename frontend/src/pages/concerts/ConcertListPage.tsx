import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router'
import { concertQueryKeys, getConcerts, type ConcertSummary } from '@/api/concerts'
import { concertPath } from '@/app/paths'
import { ConcertPoster } from '@/components/ticketing/ConcertPoster'
import { EmptyState } from '@/components/ticketing/EmptyState'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { SaleStatusBadge } from '@/components/ticketing/SaleStatusBadge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'
import { saleStatusAt } from '@/lib/saleStatus'
import { cn } from '@/lib/utils'
import { useNow } from '@/lib/serverClock'

const PAGE_SIZE = 20

// 주소의 page는 사람이 읽는 1부터, API는 0부터 센다
// 서버의 page는 int라 그 범위를 넘는 값은 첫 페이지로 본다
const MAX_PAGE = 2 ** 31 - 1

function readPage(value: string | null): number {
  if (value === null || !/^\d+$/.test(value)) {
    return 1
  }
  const page = Number(value)
  return page >= 1 && page <= MAX_PAGE ? page : 1
}

export function ConcertListPage() {
  const [searchParams] = useSearchParams()
  const page = readPage(searchParams.get('page'))
  const concerts = useQuery({
    queryKey: concertQueryKeys.list(page - 1),
    queryFn: () => getConcerts({ page: page - 1, size: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  })
  const data = concerts.data
  const totalPages = data ? Math.ceil(data.totalElements / PAGE_SIZE) : 0

  return (
    <main className="flex flex-col gap-6">
      <div className="grid gap-1">
        <h1 className="type-title text-gray-900">공연</h1>
        <p className="type-body-sm text-gray-500">
          오픈 시각이 되면 공연 상세에서 예매할 수 있어요
        </p>
      </div>

      {/* 받은 데이터가 있으면 먼저 그린다. 다시 조회하다 실패해도 목록을 오류 알림으로 바꾸지 않는다 */}
      {data === undefined ? (
        concerts.isError ? (
          <ErrorNotice error={concerts.error} onRetry={() => void concerts.refetch()} />
        ) : (
          <ConcertListSkeleton />
        )
      ) : data.content.length === 0 ? (
        data.totalElements > 0 ? (
          <EmptyState
            title="이 페이지에는 공연이 없어요"
            action={
              <Button asChild variant="outline" size="sm">
                <Link to="?">첫 페이지로</Link>
              </Button>
            }
          />
        ) : (
          <EmptyState
            title="아직 예매할 수 있는 공연이 없어요"
            description="운영자가 공연을 등록하면 여기에 보여요"
          />
        )
      ) : (
        // 다른 페이지를 받는 동안은 이전 목록을 흐리게 둔다(keepPreviousData)
        <ul
          aria-busy={concerts.isPlaceholderData || undefined}
          className={cn(
            'grid gap-4 transition-opacity @min-[48rem]:grid-cols-2',
            concerts.isPlaceholderData && 'opacity-60',
          )}
        >
          {data.content.map((concert) => (
            <li key={concert.concertId}>
              <ConcertCard concert={concert} />
            </li>
          ))}
        </ul>
      )}

      {totalPages > 1 ? <Pagination page={page} totalPages={totalPages} /> : null}
    </main>
  )
}

// Note: 목록 응답에는 서버 시각이 없어(ISE-99) 배지만 브라우저 시계로 정한다. 예매 가능 여부는 서버 시각이 있는
// 공연 상세에서 다시 정하므로 목록 배지가 조금 어긋나도 잘못 예매되지 않는다.
function ConcertCard({ concert }: { concert: ConcertSummary }) {
  const now = useNow(1000)
  const next =
    concert.nextStartsAt !== null && concert.nextOpenAt !== null
      ? { startsAt: concert.nextStartsAt, openAt: concert.nextOpenAt }
      : null

  return (
    <Card className="relative h-full flex-row items-start gap-4 px-5 transition-shadow duration-(--dur-fast) hover:shadow-md has-[a:focus-visible]:ring-2 has-[a:focus-visible]:ring-ring">
      <ConcertPoster concertId={concert.concertId} />
      <div className="grid min-w-0 gap-2">
        {/* 다음 회차가 없으면 회차가 모두 끝났는지 아직 등록 전인지 목록 응답으로는 알 수 없어 배지를 달지 않는다 */}
        {next ? (
          <div>
            <SaleStatusBadge status={saleStatusAt(next, now)} />
          </div>
        ) : null}
        <h2 className="type-heading line-clamp-2 text-gray-900">
          {/* 카드 전체가 눌리도록 링크의 누름 영역을 카드 크기로 넓힌다. 링크 이름은 제목만 남긴다 */}
          <Link
            to={concertPath(concert.concertId)}
            className="outline-none after:absolute after:inset-0 after:rounded-lg"
          >
            {concert.title}
          </Link>
        </h2>
        {next ? (
          <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 type-body-sm">
            <dt className="text-gray-500">공연</dt>
            <dd className="tabular-nums text-gray-900">{formatDateTime(next.startsAt)}</dd>
            <dt className="text-gray-500">오픈</dt>
            <dd className="tabular-nums text-gray-900">{formatDateTime(next.openAt)}</dd>
          </dl>
        ) : (
          <p className="type-body-sm text-gray-500">예정된 회차가 없어요</p>
        )}
      </div>
    </Card>
  )
}

function ConcertListSkeleton() {
  return (
    <div className="grid gap-4 @min-[48rem]:grid-cols-2" role="status">
      <span className="sr-only">공연 목록을 불러오고 있어요</span>
      {[0, 1, 2, 3].map((key) => (
        <Card key={key} className="flex-row gap-4 px-5">
          <Skeleton className="h-[84px] w-16 rounded-sm" />
          <div className="grid flex-1 content-start gap-2">
            <Skeleton className="h-5 w-16" />
            <Skeleton className="h-6 w-3/4" />
            <Skeleton className="h-4 w-1/2" />
          </div>
        </Card>
      ))}
    </div>
  )
}

function Pagination({ page, totalPages }: { page: number; totalPages: number }) {
  return (
    <nav aria-label="공연 목록 페이지" className="flex items-center justify-center gap-2">
      <PageLink page={page - 1} disabled={page <= 1}>
        이전
      </PageLink>
      <span className="type-body-sm tabular-nums text-gray-500">
        {page} / {totalPages}
      </span>
      <PageLink page={page + 1} disabled={page >= totalPages}>
        다음
      </PageLink>
    </nav>
  )
}

function PageLink({
  page,
  disabled,
  children,
}: {
  page: number
  disabled: boolean
  children: string
}) {
  if (disabled) {
    return (
      <Button variant="outline" size="sm" disabled>
        {children}
      </Button>
    )
  }
  return (
    <Button asChild variant="outline" size="sm">
      <Link to={page === 1 ? '?' : `?page=${page}`}>{children}</Link>
    </Button>
  )
}
