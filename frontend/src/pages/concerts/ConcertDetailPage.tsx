import { useQuery } from '@tanstack/react-query'
import { Armchair, ChevronLeft, CreditCard, PartyPopper, Users } from 'lucide-react'
import { useId, useMemo, useReducer, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { isApiError } from '@/api/apiError'
import {
  concertQueryKeys,
  getConcert,
  type ConcertDetail,
  type SaleStatus,
  type ScheduleSummary,
} from '@/api/concerts'
import { paths, queuePath } from '@/app/paths'
import { concertArt, WIDE_SIZE } from '@/components/ticketing/concertArt'
import { ConcertPoster } from '@/components/ticketing/ConcertPoster'
import { Countdown } from '@/components/ticketing/Countdown'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { SaleStatusBadge } from '@/components/ticketing/SaleStatusBadge'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { formatDate, formatDateTime, kstParts } from '@/lib/format'
import { instantMillis } from '@/lib/instant'
import { saleStatusAt } from '@/lib/saleStatus'
import { readRouteId } from '@/lib/routeId'
import { createServerClock, useNow, type ServerClock } from '@/lib/serverClock'
import { cn } from '@/lib/utils'
import { queueNavigationState } from '@/pages/queue/queueNavigation'

const CONTAINER = 'mx-auto w-full max-w-[1200px] px-4 @min-[40rem]:px-6'

export function ConcertDetailPage() {
  const concertId = readRouteId(useParams().concertId)
  const concert = useQuery({
    queryKey: concertQueryKeys.detail(concertId ?? 0),
    queryFn: () => getConcert(concertId ?? 0),
    enabled: concertId !== null,
    // 판매 상태와 시계는 화면이 다시 계산하므로 탭을 오갈 때마다 다시 받을 필요가 없다.
    // 오픈 직전에 탭으로 돌아오는 사용자가 한꺼번에 다시 조회하면 그 순간 부하만 늘린다.
    refetchOnWindowFocus: false,
  })
  // Note: 서버 시각 차이는 응답을 받은 순간 기준으로 한 번만 잰다(design-system.md 7절). 다시 조회하면
  // serverTime과 dataUpdatedAt이 함께 바뀌어 새 차이로 맞춰진다.
  const serverTime = concert.data?.serverTime
  const clock = useMemo(
    () => (serverTime ? createServerClock(serverTime, concert.dataUpdatedAt) : null),
    [serverTime, concert.dataUpdatedAt],
  )

  // Note: 받은 데이터가 있으면 그것을 먼저 그린다. 다시 조회하다 실패해도(연결 회복 뒤 재조회의 503 등)
  // 카운트다운과 [예매하기]를 오류 알림으로 바꾸지 않는다. 오류는 데이터가 없을 때만 보인다
  if (
    concertId === null ||
    (concert.data === undefined && isApiError(concert.error, 'NOT_FOUND'))
  ) {
    return (
      <PlainPage>
        <NotFoundNotice />
      </PlainPage>
    )
  }
  if (concert.data === undefined) {
    return (
      <PlainPage>
        {concert.isError ? (
          <ErrorNotice error={concert.error} onRetry={() => void concert.refetch()} />
        ) : (
          <ConcertDetailSkeleton />
        )}
      </PlainPage>
    )
  }

  const data = concert.data
  return (
    <main className="flex flex-1 flex-col">
      <ConcertHero concert={data} clock={clock} />
      <div
        className={cn(
          CONTAINER,
          'grid gap-10 py-6 @min-[48rem]:py-10 @min-[64rem]:grid-cols-[minmax(0,1fr)_380px] @min-[64rem]:items-start',
        )}
      >
        {/* 예매 패널이 첫 화면 안에 들어오게 한다(design-system.md 1절 "다음 행동이 먼저 보인다").
            좁은 화면에서는 배너 바로 아래에 먼저 두고, 넓은 화면에서는 배너 오른쪽(비워 둔 자리)으로 끌어올려
            겹치고 스크롤하면 위에 붙는다 */}
        <aside
          aria-label="예매"
          className="relative z-10 @min-[64rem]:sticky @min-[64rem]:top-24 @min-[64rem]:order-2 @min-[64rem]:-mt-[320px]"
        >
          <BookingPanel concert={data} clock={clock} />
        </aside>
        <div className="min-w-0 @min-[64rem]:order-1">
          {/* 제목 순서를 h1 → h2(공연 안내) → h3으로 둔다. 예매 패널의 h2가 앞에 와도 탭 내용이 그 아래로 읽히지 않게 */}
          <h2 className="sr-only">공연 안내</h2>
          <ConcertTabs concert={data} clock={clock} />
        </div>
      </div>
    </main>
  )
}

// 공연을 받기 전·못 받았을 때의 화면. 배너 없이 가운데 상자만 둔다
function PlainPage({ children }: { children: ReactNode }) {
  return (
    <main className={cn(CONTAINER, 'flex max-w-[880px] flex-col gap-5 py-8')}>
      <BackLink />
      {children}
    </main>
  )
}

function BackLink({ tone = 'dark' }: { tone?: 'dark' | 'light' }) {
  return (
    <Link
      to={paths.home}
      className={cn(
        'inline-flex w-fit items-center gap-1 rounded-sm type-body-sm outline-none focus-visible:ring-2',
        tone === 'dark'
          ? 'text-gray-500 hover:text-gray-900 focus-visible:ring-ring'
          : 'text-white/90 hover:text-white focus-visible:ring-white',
      )}
    >
      <ChevronLeft className="size-4" aria-hidden="true" />
      공연 목록
    </Link>
  )
}

function statusOf(schedule: ScheduleSummary, nowMillis: number): SaleStatus {
  // 서버가 CLOSED라고 하면 시각과 상관없이 따른다
  return schedule.saleStatus === 'CLOSED' ? 'CLOSED' : saleStatusAt(schedule, nowMillis)
}

// 공연 전체 상태: 예매 중인 회차가 하나라도 있으면 예매 중, 없으면 오픈 예정, 그것도 없으면 판매 종료
function overallStatus(schedules: ScheduleSummary[], nowMillis: number): SaleStatus | null {
  if (schedules.length === 0) {
    return null
  }
  const statuses = schedules.map((schedule) => statusOf(schedule, nowMillis))
  if (statuses.includes('ON_SALE')) {
    return 'ON_SALE'
  }
  return statuses.includes('BEFORE_OPEN') ? 'BEFORE_OPEN' : 'CLOSED'
}

// 배너: 공연 그림을 흐리게 깔고 포스터와 공연 요약을 얹는다
function ConcertHero({ concert, clock }: { concert: ConcertDetail; clock: ServerClock | null }) {
  const art = concertArt(concert.concertId)
  const { schedules } = concert
  const venues = [...new Set(schedules.map((schedule) => schedule.venueName))]
  const limits = [...new Set(schedules.map((schedule) => schedule.maxPerUser))]
  const first = schedules[0]
  const last = schedules[schedules.length - 1]

  return (
    <section className="relative overflow-hidden bg-gray-900 text-white">
      <img
        src={art.wide}
        alt=""
        width={WIDE_SIZE.width}
        height={WIDE_SIZE.height}
        fetchPriority="high"
        decoding="async"
        className="absolute inset-0 size-full scale-110 object-cover opacity-70 blur-2xl"
      />
      {/* 글자가 그림 위에서 읽히도록 위쪽부터 충분히 덮는다(밝은 그림에서 흰 글자 4.5:1) */}
      <div className="absolute inset-0 bg-linear-to-b from-gray-900/60 via-gray-900/80 to-gray-900" />
      <div
        className={cn(
          CONTAINER,
          'relative grid gap-5 py-6 @min-[48rem]:gap-6 @min-[48rem]:py-10 @min-[64rem]:pr-[436px]',
        )}
      >
        <BackLink tone="light" />
        <div className="grid grid-cols-[auto_minmax(0,1fr)] items-end gap-5 @min-[48rem]:gap-8">
          <ConcertPoster
            concertId={concert.concertId}
            title={concert.title}
            loading="eager"
            className="w-[104px] rounded-md shadow-[0_24px_60px_rgba(0,0,0,0.5)] ring-1 ring-white/10 @min-[40rem]:w-[150px] @min-[48rem]:w-[190px]"
          />
          <div className="grid min-w-0 gap-3 @min-[48rem]:gap-4">
            {clock ? <LiveSaleBadge schedules={schedules} clock={clock} /> : null}
            <h1 className="text-[clamp(22px,3.4cqw,40px)] leading-[1.2] font-extrabold tracking-[-0.03em] break-keep">
              {concert.title}
            </h1>
            {concert.description ? (
              <p className="line-clamp-2 max-w-[640px] type-body whitespace-pre-line text-white/85 @max-[40rem]:hidden">
                {concert.description}
              </p>
            ) : null}
            {/* 좁은 화면에서는 예매 패널을 첫 화면에 올리려고 요약을 숨긴다(같은 값이 회차 일정 표에 있다) */}
            {first && last ? (
              <dl className="grid max-w-[680px] grid-cols-[auto_1fr] gap-x-6 gap-y-2 type-body-sm @max-[40rem]:hidden @min-[48rem]:grid-cols-[auto_1fr_auto_1fr]">
                <HeroFact label="장소">
                  {venues.length === 1 ? venues[0] : `${venues[0]} 외 ${venues.length - 1}곳`}
                </HeroFact>
                <HeroFact label="기간">
                  {first === last
                    ? formatDate(first.startsAt)
                    : `${formatDate(first.startsAt)} ~ ${formatDate(last.startsAt)}`}
                </HeroFact>
                <HeroFact label="회차">{schedules.length}회</HeroFact>
                <HeroFact label="1인 최대">
                  {limits.length === 1 ? `${limits[0]}매` : '회차마다 달라요'}
                </HeroFact>
              </dl>
            ) : null}
          </div>
        </div>
      </div>
    </section>
  )
}

function HeroFact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <>
      <dt className="text-white/70">{label}</dt>
      <dd className="min-w-0 text-white/90 tabular-nums">{children}</dd>
    </>
  )
}

function LiveSaleBadge({ schedules, clock }: { schedules: ScheduleSummary[]; clock: ServerClock }) {
  const now = useNow(1000, clock)
  const status = overallStatus(schedules, now)
  return status ? (
    <div>
      <SaleStatusBadge status={status} />
    </div>
  ) : null
}

// 예매 패널: 고른 회차의 오픈 카운트다운이나 [예매하기]를 먼저 두고, 그 아래에서 회차를 바꾼다.
// Note: 판매 상태는 응답의 saleStatus를 그대로 쓰지 않고 서버 시각으로 보정한 지금으로 다시 정한다.
// 회차 배지는 1초마다 다시 그리고(ScheduleChooser), 고른 회차는 카운트다운 완료로 바로 다시 그린다(ChosenSchedule).
function BookingPanel({ concert, clock }: { concert: ConcertDetail; clock: ServerClock | null }) {
  const { schedules } = concert
  const groupName = useId()

  return (
    <Card className="gap-0 overflow-hidden p-0 shadow-md">
      <div className="grid gap-1 border-b border-gray-100 px-5 py-4">
        <h2 className="type-heading text-gray-900">회차 선택</h2>
        <p className="type-body-sm text-gray-500">원하는 회차를 고르고 예매해 주세요</p>
      </div>
      {schedules.length === 0 || clock === null ? (
        <p className="px-5 py-6 type-body-sm text-gray-500">등록된 회차가 없어요</p>
      ) : (
        <ScheduleChooser concert={concert} clock={clock} groupName={groupName} />
      )}
    </Card>
  )
}

// 처음 고를 회차: 예매 중인 첫 회차(공연이 가까운 순), 없으면 오픈이 가장 빠른 회차, 그것도 없으면 첫 회차
function defaultSchedule(schedules: ScheduleSummary[], nowMillis: number): ScheduleSummary {
  const onSale = schedules.find((schedule) => statusOf(schedule, nowMillis) === 'ON_SALE')
  if (onSale) {
    return onSale
  }
  const beforeOpen = schedules
    .filter((schedule) => statusOf(schedule, nowMillis) === 'BEFORE_OPEN')
    .sort((a, b) => instantMillis(a.openAt) - instantMillis(b.openAt))
  return beforeOpen[0] ?? schedules[0]
}

function ScheduleChooser({
  concert,
  clock,
  groupName,
}: {
  concert: ConcertDetail
  clock: ServerClock
  groupName: string
}) {
  const now = useNow(1000, clock)
  const { schedules } = concert
  // Note: 기본 회차는 처음 그릴 때 한 번만 정한다. 매초 다시 정하면 다른 회차가 열리는 순간 고른 회차가 바뀌어,
  // 보던 회차가 아닌 회차의 대기열로 들어간다(셀프 리뷰). 고른 회차가 응답에서 사라졌을 때만 다시 정한다
  const [chosenId, setChosenId] = useState(() => defaultSchedule(schedules, clock.now()).scheduleId)
  const chosen =
    schedules.find((schedule) => schedule.scheduleId === chosenId) ??
    defaultSchedule(schedules, now)

  return (
    <>
      <ChosenSchedule key={chosen.scheduleId} concert={concert} schedule={chosen} clock={clock} />
      <fieldset className="grid max-h-[276px] gap-2 overflow-y-auto p-4">
        <legend className="sr-only">회차</legend>
        {schedules.length > 1 ? (
          <p aria-hidden="true" className="type-caption text-gray-500">
            다른 회차를 고르려면 눌러 주세요
          </p>
        ) : null}
        {schedules.map((schedule) => {
          const parts = kstParts(schedule.startsAt)
          return (
            <label
              key={schedule.scheduleId}
              className="relative flex cursor-pointer items-center gap-3 rounded-md border border-gray-100 p-3 transition-colors duration-(--dur-fast) hover:border-gray-200 has-checked:border-primary-600 has-checked:bg-primary-50/60 has-focus-visible:ring-2 has-focus-visible:ring-ring"
            >
              <input
                type="radio"
                name={groupName}
                value={schedule.scheduleId}
                checked={schedule.scheduleId === chosen.scheduleId}
                onChange={() => setChosenId(schedule.scheduleId)}
                className="sr-only"
              />
              <span
                aria-hidden="true"
                className="grid w-12 shrink-0 justify-items-center rounded-sm bg-gray-50 py-1 type-caption text-gray-500"
              >
                <span className="text-[18px] leading-6 font-bold text-gray-900 tabular-nums">
                  {parts.day}
                </span>
                {parts.weekday}
              </span>
              <span className="grid min-w-0 flex-1 gap-0.5">
                <span className="type-body-sm font-semibold text-gray-900 tabular-nums">
                  {formatDateTime(schedule.startsAt)}
                </span>
                <span className="truncate type-caption text-gray-500">{schedule.venueName}</span>
              </span>
              <SaleStatusBadge status={statusOf(schedule, now)} />
            </label>
          )
        })}
      </fieldset>
    </>
  )
}

// Note: 고른 회차의 상태는 부모의 1초 갱신을 기다리지 않고 그릴 때마다 서버 시계로 다시 정한다. 카운트다운(250ms)이
// 0을 보이면 onComplete로 이 부분만 다시 그려, "00:00"이 보이는 순간 [예매하기]가 켜진다(design-system.md 7절).
// 부모 값을 받으면 최대 1초 동안 0인데 버튼이 꺼져 있었다(테스트 작업자가 찾음).
function ChosenSchedule({
  concert,
  schedule,
  clock,
}: {
  concert: Pick<ConcertDetail, 'concertId' | 'title'>
  schedule: ScheduleSummary
  clock: ServerClock
}) {
  const [, rerender] = useReducer((count: number) => count + 1, 0)
  const status = statusOf(schedule, clock.now())
  // 처음 그릴 때 오픈 전이었으면 보는 중에 열린 것이다. 카운트다운의 onComplete로 알리면, 부모 갱신이 먼저 와
  // 카운트다운이 사라질 때 불리지 않아 알림이 빠진다
  const [openedLater] = useState(() => status === 'BEFORE_OPEN')
  const openAtId = `open-at-${schedule.scheduleId}`

  return (
    <div className="grid gap-3 border-b border-gray-100 bg-gray-25 p-5">
      <p className="type-body-sm font-semibold text-gray-900 tabular-nums">
        {formatDateTime(schedule.startsAt)}
        <span className="font-normal text-gray-500"> · {schedule.venueName}</span>
      </p>
      {status === 'BEFORE_OPEN' ? (
        <>
          <div className="flex items-baseline justify-between gap-3">
            <span className="type-body-sm text-gray-500">오픈까지</span>
            <Countdown
              targetAt={schedule.openAt}
              clock={clock}
              className="type-title"
              onComplete={rerender}
            />
          </div>
          <Button disabled size="lg" className="w-full" aria-describedby={openAtId}>
            예매하기
          </Button>
          <p id={openAtId} className="text-center type-caption tabular-nums text-gray-500">
            {formatDateTime(schedule.openAt)} 오픈
          </p>
        </>
      ) : status === 'ON_SALE' ? (
        <Button asChild size="lg" className="w-full">
          <Link
            to={queuePath(schedule.scheduleId)}
            state={queueNavigationState({
              concertId: concert.concertId,
              title: concert.title,
              startsAt: schedule.startsAt,
              venueName: schedule.venueName,
            })}
          >
            예매하기
          </Link>
        </Button>
      ) : (
        <p className="py-2 text-center type-body-sm text-gray-500">판매가 끝났어요</p>
      )}
      <p className="text-center type-caption text-gray-500">
        {status === 'CLOSED'
          ? `1인 최대 ${schedule.maxPerUser}매`
          : status === 'BEFORE_OPEN'
            ? `1인 최대 ${schedule.maxPerUser}매 · 오픈하면 대기열을 거쳐 좌석을 골라요`
            : `1인 최대 ${schedule.maxPerUser}매 · 대기열을 거쳐 좌석을 골라요`}
      </p>
      {/* 카운트다운은 매초 읽지 않으므로(timer) 열린 순간만 알린다. 알림 영역은 처음부터 있어야 읽힌다 */}
      <p aria-live="polite" className="sr-only">
        {openedLater && status === 'ON_SALE' ? '예매가 열렸어요' : ''}
      </p>
    </div>
  )
}

function ConcertTabs({ concert, clock }: { concert: ConcertDetail; clock: ServerClock | null }) {
  const art = concertArt(concert.concertId)
  return (
    <Tabs defaultValue="info">
      <TabsList aria-label="공연 안내">
        <TabsTrigger value="info">공연 정보</TabsTrigger>
        <TabsTrigger value="guide">예매 안내</TabsTrigger>
      </TabsList>
      <TabsContent value="info" className="grid gap-10">
        <img
          src={art.wide}
          alt=""
          width={WIDE_SIZE.width}
          height={WIDE_SIZE.height}
          loading="lazy"
          decoding="async"
          className="aspect-[1600/667] w-full rounded-lg object-cover"
        />
        <section className="grid gap-3">
          <h3 className="type-heading text-gray-900">공연 소개</h3>
          {concert.description ? (
            <p className="type-body whitespace-pre-line text-gray-700">{concert.description}</p>
          ) : (
            <p className="type-body-sm text-gray-500">공연 소개가 아직 없어요</p>
          )}
        </section>
        {concert.schedules.length > 0 && clock ? (
          <ScheduleTable schedules={concert.schedules} clock={clock} />
        ) : null}
      </TabsContent>
      <TabsContent value="guide">
        <BookingGuide />
      </TabsContent>
    </Tabs>
  )
}

function ScheduleTable({ schedules, clock }: { schedules: ScheduleSummary[]; clock: ServerClock }) {
  const now = useNow(1000, clock)
  return (
    <section className="grid gap-3">
      <h3 className="type-heading text-gray-900">회차 일정</h3>
      <div className="overflow-x-auto rounded-md border border-gray-100 bg-gray-0">
        <table className="w-full min-w-[600px] type-body-sm whitespace-nowrap">
          <thead className="bg-gray-50 text-left text-gray-500">
            <tr>
              <th scope="col" className="px-4 py-3 font-medium">
                공연 일시
              </th>
              <th scope="col" className="px-4 py-3 font-medium">
                장소
              </th>
              <th scope="col" className="px-4 py-3 font-medium">
                티켓 오픈
              </th>
              <th scope="col" className="px-4 py-3 font-medium">
                1인 최대
              </th>
              <th scope="col" className="px-4 py-3 font-medium">
                상태
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100 text-gray-900">
            {schedules.map((schedule) => (
              <tr key={schedule.scheduleId}>
                <td className="px-4 py-3 tabular-nums">{formatDateTime(schedule.startsAt)}</td>
                <td className="px-4 py-3">{schedule.venueName}</td>
                <td className="px-4 py-3 tabular-nums">{formatDateTime(schedule.openAt)}</td>
                <td className="px-4 py-3 tabular-nums">{schedule.maxPerUser}매</td>
                <td className="px-4 py-3">
                  <SaleStatusBadge status={statusOf(schedule, now)} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}

const BOOKING_STEPS = [
  {
    icon: Users,
    title: '대기열 입장',
    body: '오픈 시각에 [예매하기]를 누르면 대기열에 들어가요. 들어온 순서대로 입장해요.',
  },
  {
    icon: Armchair,
    title: '좌석 선택',
    body: '입장하면 구역과 좌석을 골라요. 고른 좌석은 결제하는 동안 잡아 둬요.',
  },
  {
    icon: CreditCard,
    title: '결제',
    body: '결제 가능 시간 안에 결제해 주세요. 시간이 지나면 좌석이 풀려요.',
  },
  {
    icon: PartyPopper,
    title: '예매 완료',
    body: '결제가 끝나면 예매가 확정되고 내 예매에서 티켓 번호를 볼 수 있어요.',
  },
] as const

function BookingGuide() {
  return (
    <div className="grid gap-10">
      <section className="grid gap-4">
        <h3 className="type-heading text-gray-900">예매 순서</h3>
        <ol className="grid gap-3 @min-[40rem]:grid-cols-2">
          {BOOKING_STEPS.map((step, index) => (
            <li
              key={step.title}
              className="flex gap-4 rounded-md border border-gray-100 bg-gray-0 p-4"
            >
              <span
                aria-hidden="true"
                className="inline-flex size-10 shrink-0 items-center justify-center rounded-full bg-primary-50 text-primary-600"
              >
                <step.icon className="size-5" />
              </span>
              <div className="grid gap-1">
                <p className="type-body font-semibold text-gray-900">
                  <span className="mr-1.5 text-primary-600 tabular-nums">{index + 1}</span>
                  {step.title}
                </p>
                <p className="type-body-sm text-gray-500">{step.body}</p>
              </div>
            </li>
          ))}
        </ol>
      </section>
      <section className="grid gap-3">
        <h3 className="type-heading text-gray-900">알아 두세요</h3>
        <ul className="grid list-disc gap-2 pl-5 type-body-sm text-gray-700 marker:text-gray-400">
          <li>
            회차마다 한 사람이 예매할 수 있는 매수가 정해져 있어요. 이미 예매한 매수도 함께 세요.
          </li>
          <li>다른 사람이 먼저 잡은 좌석을 고르면 줄을 다시 서지 않고 좌석만 다시 고르면 돼요.</li>
          <li>
            결제 결과를 바로 알 수 없으면 "확인 중"으로 보이고, 결과가 나오면 화면이 바뀌어요.
          </li>
          <li>
            일부 좌석은 추첨으로 팔아요. 운영자가 안내한 응모 화면에서 응모하고, 당첨되면 자동결제
            카드로 결제돼요.
          </li>
        </ul>
      </section>
    </div>
  )
}

function NotFoundNotice() {
  return (
    <Alert
      variant="warning"
      action={
        <Button asChild variant="outline" size="sm">
          <Link to={paths.home}>목록으로</Link>
        </Button>
      }
    >
      찾는 공연이 없어요. 목록에서 다시 골라 주세요
    </Alert>
  )
}

function ConcertDetailSkeleton() {
  return (
    <div className="grid gap-5" role="status">
      <span className="sr-only">공연 정보를 불러오고 있어요</span>
      <div className="flex gap-5">
        <Skeleton className="aspect-[2/3] w-[150px] rounded-md" />
        <div className="grid flex-1 content-end gap-3">
          <Skeleton className="h-6 w-20" />
          <Skeleton className="h-9 w-2/3" />
          <Skeleton className="h-4 w-1/2" />
        </div>
      </div>
      <Skeleton className="h-48 w-full rounded-lg" />
    </div>
  )
}
