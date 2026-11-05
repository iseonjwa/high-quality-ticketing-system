import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Ticket } from 'lucide-react'
import { useId, useMemo, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router'
import { concertQueryKeys, getConcerts } from '@/api/concerts'
import { concertPath, paths } from '@/app/paths'
import { ConcertPoster } from '@/components/ticketing/ConcertPoster'
import { EmptyState } from '@/components/ticketing/EmptyState'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { SaleStatusBadge } from '@/components/ticketing/SaleStatusBadge'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { formatShortDateTime, kstDaysUntil, kstParts } from '@/lib/format'
import { useNow } from '@/lib/serverClock'
import { cn } from '@/lib/utils'
import { HomeHero } from '@/pages/concerts/HomeHero'
import { groupHomeConcerts, type HomeConcert } from '@/pages/concerts/homeConcerts'

const PAGE_SIZE = 20

// 주소의 page는 사람이 읽는 1부터, API는 0부터 센다
// 서버의 page는 int라 그 범위를 넘는 값은 첫 페이지로 본다
const MAX_PAGE = 2 ** 31 - 1

// 홈의 묶음(예매 중·오픈 예정)을 다시 나누는 간격. 배지가 오픈 시각에서 조금 늦게 바뀌어도 예매 가능 여부는
// 공연 상세가 서버 시각으로 정하므로 1초마다 목록 전체를 다시 그리지 않는다
const REGROUP_MILLIS = 10_000

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
  const now = useNow(REGROUP_MILLIS)
  const sections = useMemo(() => (data ? groupHomeConcerts(data.content, now) : null), [data, now])

  return (
    <main className="flex flex-1 flex-col">
      <h1 className="sr-only">공연</h1>
      {/* 대표 배너는 첫 페이지에만 둔다. 받는 동안은 같은 높이의 자리를 잡아 아래 내용이 밀리지 않게 한다 */}
      {page === 1 ? (
        sections === null ? (
          concerts.isError ? null : (
            <div aria-hidden="true" className="h-[clamp(440px,46cqw,540px)] bg-gray-900" />
          )
        ) : (
          <HomeHero items={sections.featured} nowMillis={now} />
        )
      ) : null}

      <div className="mx-auto grid w-full max-w-[1200px] gap-14 px-4 py-12 @min-[40rem]:px-6">
        {/* 받은 데이터가 있으면 먼저 그린다. 다시 조회하다 실패해도 목록을 오류 알림으로 바꾸지 않는다 */}
        {data === undefined || sections === null ? (
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
          <div
            aria-busy={concerts.isPlaceholderData || undefined}
            className={cn(
              'grid gap-14 transition-opacity',
              concerts.isPlaceholderData && 'opacity-60',
            )}
          >
            {sections.openingSoon.length > 0 ? (
              <HomeSection
                title="티켓 오픈 예정"
                description="오픈 시각이 되면 공연 상세에서 예매하기가 열려요"
                count={sections.openingSoon.length}
              >
                <ol className="grid gap-3 @min-[48rem]:grid-cols-2">
                  {sections.openingSoon.map((item) => (
                    <li key={item.concert.concertId}>
                      <OpenCalendarCard item={item} nowMillis={now} />
                    </li>
                  ))}
                </ol>
              </HomeSection>
            ) : null}

            {sections.onSale.length > 0 ? (
              <HomeSection
                title="지금 예매 중"
                description="공연이 가까운 순서예요"
                count={sections.onSale.length}
              >
                <ul className="grid grid-cols-2 gap-x-4 gap-y-8 @min-[40rem]:grid-cols-3 @min-[64rem]:grid-cols-5 @min-[64rem]:gap-x-6">
                  {sections.onSale.map((item) => (
                    <li key={item.concert.concertId}>
                      <PosterCard item={item} />
                    </li>
                  ))}
                </ul>
              </HomeSection>
            ) : null}

            <LotteryPromo />

            {sections.others.length > 0 ? (
              <HomeSection
                title="다른 공연"
                description="예정된 회차가 없거나 판매가 끝난 공연이에요"
                count={sections.others.length}
              >
                <ul className="grid gap-3 @min-[40rem]:grid-cols-2 @min-[64rem]:grid-cols-3">
                  {sections.others.map((item) => (
                    <li key={item.concert.concertId}>
                      <CompactConcertCard item={item} />
                    </li>
                  ))}
                </ul>
              </HomeSection>
            ) : null}
          </div>
        )}

        {totalPages > 1 ? <Pagination page={page} totalPages={totalPages} /> : null}
      </div>
    </main>
  )
}

function HomeSection({
  title,
  description,
  count,
  children,
}: {
  title: string
  description: string
  count: number
  children: ReactNode
}) {
  const titleId = useId()
  return (
    <section aria-labelledby={titleId} className="grid gap-5">
      <div className="grid gap-1">
        <h2 id={titleId} className="flex items-center gap-2 type-title text-gray-900">
          {title}
          <span className="inline-flex h-6 min-w-6 items-center justify-center rounded-pill bg-gray-100 px-2 type-caption text-gray-600 tabular-nums">
            {count}
          </span>
        </h2>
        <p className="type-body-sm text-gray-500">{description}</p>
      </div>
      {children}
    </section>
  )
}

// 카드 전체가 눌리도록 제목 링크의 누름 영역을 카드 크기로 넓힌다. 링크 이름은 제목만 남긴다
function CardTitleLink({ concertId, children }: { concertId: number; children: string }) {
  return (
    <Link
      to={concertPath(concertId)}
      className="outline-none after:absolute after:inset-0 after:rounded-lg"
    >
      {children}
    </Link>
  )
}

// 오픈 달력: 오픈 날짜를 왼쪽 달력 칸에 크게 둔다
function OpenCalendarCard({ item, nowMillis }: { item: HomeConcert; nowMillis: number }) {
  const { concert, next } = item
  if (next === null) {
    return null
  }
  const open = kstParts(next.openAt)
  const days = kstDaysUntil(next.openAt, nowMillis)
  return (
    <article className="relative flex items-center gap-4 rounded-lg border border-gray-100 bg-gray-0 p-3 pr-5 transition-shadow duration-(--dur-fast) hover:shadow-md has-[a:focus-visible]:ring-2 has-[a:focus-visible]:ring-ring">
      <div
        aria-hidden="true"
        className="grid w-16 shrink-0 justify-items-center rounded-md bg-gray-900 py-2 text-white"
      >
        <span className="type-caption text-white/70">{Number(open.month)}월</span>
        <span className="text-[26px] leading-8 font-extrabold tabular-nums">{open.day}</span>
        <span className="type-caption text-white/70">{open.weekday}요일</span>
      </div>
      <ConcertPoster concertId={concert.concertId} className="w-14 rounded-[4px]" />
      <div className="grid min-w-0 gap-1">
        <p className="type-body-sm font-semibold text-primary-600 tabular-nums">
          {open.hour}:{open.minute} 티켓 오픈
          <span className="ml-2 text-gray-500">{days <= 0 ? '오늘' : `D-${days}`}</span>
        </p>
        <h3 className="line-clamp-2 type-body font-semibold text-gray-900">
          <CardTitleLink concertId={concert.concertId}>{concert.title}</CardTitleLink>
        </h3>
        <p className="type-caption text-gray-500 tabular-nums">
          공연 {formatShortDateTime(next.startsAt)}
        </p>
      </div>
    </article>
  )
}

// 포스터가 큰 카드. 예매 중인 공연을 격자로 보인다
function PosterCard({ item }: { item: HomeConcert }) {
  const { concert, next, status } = item
  return (
    <article className="group relative grid gap-3 rounded-lg has-[a:focus-visible]:ring-2 has-[a:focus-visible]:ring-ring has-[a:focus-visible]:ring-offset-4">
      <div className="relative rounded-md shadow-sm transition duration-(--dur-base) ease-(--ease-out) group-hover:-translate-y-1 group-hover:shadow-lg motion-reduce:transition-none motion-reduce:group-hover:translate-y-0">
        <ConcertPoster
          concertId={concert.concertId}
          title={concert.title}
          className="w-full rounded-md"
        />
        {status ? (
          <span className="absolute top-2 left-2">
            <SaleStatusBadge status={status} />
          </span>
        ) : null}
      </div>
      <div className="grid gap-1">
        <h3 className="line-clamp-2 type-body font-semibold text-gray-900">
          <CardTitleLink concertId={concert.concertId}>{concert.title}</CardTitleLink>
        </h3>
        {next ? (
          <p className="type-body-sm text-gray-500 tabular-nums">
            {formatShortDateTime(next.startsAt)} 공연
          </p>
        ) : null}
      </div>
    </article>
  )
}

function CompactConcertCard({ item }: { item: HomeConcert }) {
  const { concert, status } = item
  return (
    <article className="relative flex items-center gap-4 rounded-lg border border-gray-100 bg-gray-0 p-3 transition-shadow duration-(--dur-fast) hover:shadow-md has-[a:focus-visible]:ring-2 has-[a:focus-visible]:ring-ring">
      <ConcertPoster concertId={concert.concertId} className="w-12 rounded-[4px] opacity-80" />
      <div className="grid min-w-0 gap-1">
        <h3 className="line-clamp-2 type-body-sm font-semibold text-gray-900">
          <CardTitleLink concertId={concert.concertId}>{concert.title}</CardTitleLink>
        </h3>
        <p className="type-caption text-gray-500">
          {status === 'CLOSED' ? '판매가 끝났어요' : '예정된 회차가 없어요'}
        </p>
      </div>
    </article>
  )
}

// 추첨 예매 안내 띠. 추첨은 운영자가 회차마다 열고, 당첨되면 자동결제 카드로 결제된다(ISE-121·122)
function LotteryPromo() {
  const titleId = useId()
  return (
    <section
      aria-labelledby={titleId}
      className="relative overflow-hidden rounded-lg bg-linear-120 from-primary-700 via-primary-600 to-[#7a3ff2] px-6 py-8 text-white @min-[48rem]:px-10 @min-[48rem]:py-10"
    >
      <div aria-hidden="true" className="pointer-events-none absolute inset-y-0 right-0 w-1/2">
        <Ticket className="absolute -top-6 right-10 size-40 rotate-12 text-white/10" />
        <span className="absolute -right-10 -bottom-16 size-56 rounded-full border-[18px] border-white/10" />
      </div>
      <div className="relative grid gap-5 @min-[48rem]:grid-cols-[1fr_auto] @min-[48rem]:items-center">
        <div className="grid gap-2">
          <p className="type-body-sm font-semibold text-primary-100">추첨 예매</p>
          <h2 id={titleId} className="type-title break-keep">
            인기 회차는 추첨으로 공정하게 나눠요
          </h2>
          <p className="max-w-[560px] type-body-sm text-white/85">
            응모 기간에 응모하면 추첨 시각에 당첨자를 정해요. 당첨되면 등록한 자동결제 카드로 바로
            결제되니 카드를 미리 등록해 두세요.
          </p>
        </div>
        <Button
          asChild
          variant="outline"
          className="border-white bg-white text-primary-700 hover:bg-primary-50"
        >
          <Link to={paths.me}>자동결제 카드 등록하기</Link>
        </Button>
      </div>
    </section>
  )
}

function ConcertListSkeleton() {
  return (
    <div className="grid gap-5" role="status">
      <span className="sr-only">공연 목록을 불러오고 있어요</span>
      <Skeleton className="h-8 w-48" />
      <div className="grid grid-cols-2 gap-x-4 gap-y-8 @min-[40rem]:grid-cols-3 @min-[64rem]:grid-cols-5">
        {[0, 1, 2, 3, 4].map((key) => (
          <div key={key} className="grid gap-3">
            <Skeleton className="aspect-[2/3] w-full rounded-md" />
            <Skeleton className="h-5 w-3/4" />
            <Skeleton className="h-4 w-1/2" />
          </div>
        ))}
      </div>
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
