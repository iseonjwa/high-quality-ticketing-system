import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Ticket } from 'lucide-react'
import { Fragment, useId, useMemo, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router'
import { concertQueryKeys, getConcerts } from '@/api/concerts'
import { concertPath, paths } from '@/app/paths'
import { concertArt, POSTER_SIZE } from '@/components/ticketing/concertArt'
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
            <div aria-hidden="true" className="h-[clamp(440px,46cqw,540px)] bg-ink-900" />
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
                <ol className="grid gap-x-6 gap-y-8 @min-[48rem]:grid-cols-2 @min-[72rem]:grid-cols-3">
                  {sections.openingSoon.map((item) => (
                    <li key={item.concert.concertId}>
                      <OpeningTicket item={item} nowMillis={now} />
                    </li>
                  ))}
                </ol>
              </HomeSection>
            ) : null}

            <SampleAdBand />

            {sections.onSale.length > 0 ? (
              <HomeSection
                title="지금 예매 중"
                description="공연이 가까운 순서예요"
                count={sections.onSale.length}
              >
                <ul className="grid grid-cols-2 gap-x-3 gap-y-5 @min-[40rem]:grid-cols-3 @min-[40rem]:gap-x-5 @min-[64rem]:grid-cols-4">
                  {sections.onSale.map((item, position) => (
                    <Fragment key={item.concert.concertId}>
                      {position === adSlot(sections.onSale.length) ? (
                        <li className="flex">
                          <SampleAdCard />
                        </li>
                      ) : null}
                      <li className="flex">
                        <SaleTicketCard item={item} />
                      </li>
                    </Fragment>
                  ))}
                  {adSlot(sections.onSale.length) === sections.onSale.length ? (
                    <li className="flex">
                      <SampleAdCard />
                    </li>
                  ) : null}
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

// 오픈 예정 티켓: 오픈 날짜를 위에 크게 두고, 카드 오른쪽 떼는 칸(스텁)에 남은 날을 둔다
function OpeningTicket({ item, nowMillis }: { item: HomeConcert; nowMillis: number }) {
  const { concert, next } = item
  if (next === null) {
    return null
  }
  const open = kstParts(next.openAt)
  const days = kstDaysUntil(next.openAt, nowMillis)
  return (
    <div className="grid gap-3">
      <p className="flex items-baseline gap-2.5">
        <span className="text-[28px] leading-8 font-bold tracking-[-0.03em] text-gray-900 tabular-nums">
          {open.month}.{open.day}
        </span>
        <span className="type-body-sm font-semibold text-gray-600 tabular-nums">
          {open.weekday} {open.hour}:{open.minute} 티켓 오픈
        </span>
      </p>
      <article className="relative flex rounded-[18px] border border-gray-100 bg-gray-0 shadow-sm transition-shadow duration-(--dur-fast) hover:shadow-md has-[a:focus-visible]:ring-2 has-[a:focus-visible]:ring-ring">
        <div className="flex min-w-0 flex-1 items-center gap-3.5 p-3">
          <ConcertPoster concertId={concert.concertId} className="w-[60px] rounded-[8px]" />
          <div className="grid min-w-0 gap-1">
            <h3 className="line-clamp-2 type-body font-semibold text-gray-900">
              <CardTitleLink concertId={concert.concertId}>{concert.title}</CardTitleLink>
            </h3>
            <p className="type-caption text-gray-600 tabular-nums">
              {formatShortDateTime(next.startsAt)} 공연
            </p>
          </div>
        </div>
        <div className="ticket-stub flex w-[76px] shrink-0 items-center justify-center">
          <span className="text-[20px] font-bold text-danger-600 tabular-nums">
            {days <= 0 ? '오늘' : `D-${days}`}
          </span>
        </div>
      </article>
    </div>
  )
}

// 예매 중 티켓 카드. 포스터를 정사각형으로 잘라 위에 두고, 절취선 아래에 제목과 일시를 둔다(시안 B, 가로:세로 약 3:4)
function SaleTicketCard({ item }: { item: HomeConcert }) {
  const { concert, next, status } = item
  const art = concertArt(concert.concertId)
  return (
    <article className="group relative flex w-full flex-col rounded-[18px] border border-gray-100 bg-gray-0 shadow-sm transition duration-(--dur-base) ease-(--ease-out) hover:-translate-y-0.5 hover:shadow-md has-[a:focus-visible]:ring-2 has-[a:focus-visible]:ring-ring motion-reduce:transition-none motion-reduce:hover:translate-y-0">
      <div className="relative p-2.5 pb-3">
        {/* Note: width·height 속성은 받는 동안 자리를 잡는 데 쓰고, 정사각형 비율은 h-auto + aspect-square로 정한다.
            h-auto가 없으면 height 속성(720)이 CSS 높이로 남아 aspect-ratio가 무시된다 */}
        <img
          src={art.poster}
          alt=""
          width={POSTER_SIZE.width}
          height={POSTER_SIZE.height}
          loading="lazy"
          decoding="async"
          className="aspect-square h-auto w-full rounded-[12px] object-cover object-[center_40%]"
        />
        {status ? (
          <span className="absolute top-5 left-5">
            <SaleStatusBadge status={status} />
          </span>
        ) : null}
      </div>
      <span aria-hidden="true" className="ticket-perf" />
      <div className="grid gap-1 px-4 pt-3 pb-3.5">
        <h3 className="line-clamp-2 type-body font-semibold text-gray-900">
          <CardTitleLink concertId={concert.concertId}>{concert.title}</CardTitleLink>
        </h3>
        {next ? (
          <p className="type-body-sm text-gray-600 tabular-nums">
            {formatShortDateTime(next.startsAt)} 공연
          </p>
        ) : null}
      </div>
    </article>
  )
}

// 목록 안 광고 카드를 넣을 자리. 셋째 공연 다음에 두고, 공연이 그보다 적으면 맨 끝에 둔다
function adSlot(count: number): number {
  return Math.min(3, count)
}

// 샘플 광고(design-system.md 11절). 광고 소재는 광고주가 만든 그림이라는 전제로 모드와 상관없이 같은 색이다.
// 외부 광고 스크립트를 쓰지 않고, 자리 높이를 미리 잡아 받는 동안 화면이 밀리지 않게 한다. 실제 광고주가 없어 링크를 두지 않는다
// Note: 화면 폭 전체로 깔되 음수 여백으로 넓힌다. width로 넓히면 목록 격자의 칸 너비가 같이 넓어져 다른 띠가 넘친다
function SampleAdBand() {
  return (
    <aside
      aria-label="광고"
      className="relative mx-[calc(50%-50cqw)] overflow-hidden bg-[#0f1d26] text-white"
    >
      <div aria-hidden="true" className="pointer-events-none absolute inset-0">
        <span className="absolute top-[-40px] right-[8%] size-[220px] rounded-full border border-[#7ee0d4]/30" />
        <span className="absolute right-[22%] bottom-[-60px] size-[140px] rounded-full border border-[#7ee0d4]/25" />
        <span className="absolute top-[60px] right-[16%] size-[26px] rounded-full bg-[#7ee0d4]/30" />
      </div>
      <div className="relative mx-auto flex min-h-[200px] max-w-[1200px] flex-wrap items-center gap-x-10 gap-y-5 px-4 py-8 @min-[40rem]:px-6">
        <div
          aria-hidden="true"
          className="relative h-[130px] w-[80px] shrink-0 rounded-[16px] bg-[#7ee0d4]"
        >
          <span className="absolute inset-x-0 top-5 h-[11px] bg-[#0f1d26]/85" />
          <span className="absolute inset-x-0 bottom-4 text-center text-[9px] font-extrabold tracking-[0.02em] text-[#0f1d26]">
            BAMGYEOL
          </span>
        </div>
        <div className="grid flex-1 basis-[320px] gap-2">
          <p className="flex items-center gap-2">
            <AdLabel />
            <span className="type-body-sm font-semibold text-[#7ee0d4]">밤결 스파클링</span>
          </p>
          <p className="text-[clamp(24px,3cqw,32px)] leading-tight font-extrabold tracking-[-0.04em]">
            앵콜까지, 청량하게
          </p>
          <p className="type-body-sm text-white/80">
            공연장 매점과 편의점에서 만나요 · 샘플 광고예요. 실제 상품이 아니에요
          </p>
        </div>
      </div>
    </aside>
  )
}

// 목록 안 광고 카드. 이웃한 티켓 카드와 같은 칸을 쓰고 같은 높이로 늘어난다
function SampleAdCard() {
  return (
    <aside
      aria-label="광고"
      className="flex w-full flex-col justify-between gap-4 rounded-[18px] bg-[#1d2030] p-5 text-[#f4f5f8]"
    >
      <span className="self-end">
        <AdLabel />
      </span>
      <svg width="120" height="80" viewBox="0 0 120 80" aria-hidden="true" className="max-w-full">
        <rect x="6" y="10" width="104" height="52" rx="12" fill="#ffd43b" />
        <rect x="16" y="20" width="22" height="16" rx="3" fill="#1d2030" />
        <rect x="44" y="20" width="22" height="16" rx="3" fill="#1d2030" />
        <rect x="72" y="20" width="22" height="16" rx="3" fill="#1d2030" />
        <rect x="6" y="44" width="104" height="5" fill="#f59f00" />
        <circle cx="30" cy="64" r="9" fill="#0c0d13" stroke="#ffd43b" strokeWidth="3" />
        <circle cx="88" cy="64" r="9" fill="#0c0d13" stroke="#ffd43b" strokeWidth="3" />
      </svg>
      <div className="grid gap-1">
        <p className="type-body-sm font-semibold text-[#ffd43b]">라이드온 셔틀</p>
        <p className="text-[18px] leading-6 font-bold tracking-[-0.02em]">
          공연 끝나고 집까지, 셔틀로
        </p>
        <p className="type-caption text-[#b9bdc9]">출구 앞 출발 · 미리 좌석 예약 · 샘플 광고</p>
      </div>
    </aside>
  )
}

function AdLabel() {
  return (
    <span className="rounded-[6px] border border-white/40 px-1.5 py-0.5 text-[11px] leading-4 font-semibold text-white/85">
      광고
    </span>
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
      className="relative overflow-hidden rounded-lg bg-linear-120 from-brand-700 via-brand-600 to-[#7a3ff2] px-6 py-8 text-white @min-[48rem]:px-10 @min-[48rem]:py-10"
    >
      <div aria-hidden="true" className="pointer-events-none absolute inset-y-0 right-0 w-1/2">
        <Ticket className="absolute -top-6 right-10 size-40 rotate-12 text-white/10" />
        <span className="absolute -right-10 -bottom-16 size-56 rounded-full border-[18px] border-white/10" />
      </div>
      <div className="relative grid gap-5 @min-[48rem]:grid-cols-[1fr_auto] @min-[48rem]:items-center">
        <div className="grid gap-2">
          <p className="type-body-sm font-semibold text-white/80">추첨 예매</p>
          <h2 id={titleId} className="type-title break-keep">
            일부 좌석은 추첨으로 공정하게 나눠요
          </h2>
          <p className="max-w-[560px] type-body-sm text-white/85">
            운영자가 연 추첨에 응모 기간 동안 응모하면 추첨 시각에 당첨자를 정해요. 당첨되면 등록한
            자동결제 카드로 바로 결제되니 카드를 미리 등록해 두세요.
          </p>
        </div>
        <Button
          asChild
          variant="outline"
          className="border-white bg-white text-brand-700 hover:bg-white/90"
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
