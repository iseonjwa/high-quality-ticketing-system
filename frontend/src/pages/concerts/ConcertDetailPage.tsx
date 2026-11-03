import { useQuery } from '@tanstack/react-query'
import { ChevronLeft } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { isApiError } from '@/api/apiError'
import { concertQueryKeys, getConcert, type ScheduleSummary } from '@/api/concerts'
import { paths, queuePath } from '@/app/paths'
import { ConcertPoster } from '@/components/ticketing/ConcertPoster'
import { Countdown } from '@/components/ticketing/Countdown'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { SaleStatusBadge } from '@/components/ticketing/SaleStatusBadge'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'
import { saleStatusAt } from '@/lib/saleStatus'
import { createServerClock, type ServerClock } from '@/lib/serverClock'

// `0x10`, `1e3`처럼 Number가 받아 주는 다른 표기와 안전 범위를 넘는 수는 없는 공연으로 본다
function readConcertId(value: string | undefined): number | null {
  if (value === undefined || !/^\d+$/.test(value)) {
    return null
  }
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

export function ConcertDetailPage() {
  const concertId = readConcertId(useParams().concertId)
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

  return (
    <main className="mx-auto flex w-full max-w-[880px] flex-col gap-5">
      <Link
        to={paths.home}
        className="inline-flex w-fit items-center gap-1 rounded-sm type-body-sm text-gray-500 outline-none hover:text-gray-900 focus-visible:ring-2 focus-visible:ring-ring"
      >
        <ChevronLeft className="size-4" aria-hidden="true" />
        공연 목록
      </Link>

      {/* Note: 받은 데이터가 있으면 그것을 먼저 그린다. 다시 조회하다 실패해도(연결 회복 뒤 재조회의 503 등)
          카운트다운과 [예매하기]를 오류 알림으로 바꾸지 않는다. 오류는 데이터가 없을 때만 보인다 */}
      {concertId === null ||
      (concert.data === undefined && isApiError(concert.error, 'NOT_FOUND')) ? (
        <NotFoundNotice />
      ) : concert.data === undefined ? (
        concert.isError ? (
          <ErrorNotice error={concert.error} onRetry={() => void concert.refetch()} />
        ) : (
          <ConcertDetailSkeleton />
        )
      ) : (
        <>
          <Card className="flex-row items-start gap-5 px-5">
            <ConcertPoster concertId={concert.data.concertId} className="h-24 w-[72px]" />
            <div className="grid min-w-0 gap-2">
              <h1 className="type-title text-gray-900">{concert.data.title}</h1>
              {concert.data.description ? (
                <p className="type-body-sm whitespace-pre-line text-gray-500">
                  {concert.data.description}
                </p>
              ) : null}
            </div>
          </Card>

          <section aria-labelledby="schedules-title" className="grid gap-3">
            <h2 id="schedules-title" className="type-heading text-gray-900">
              회차
            </h2>
            {concert.data.schedules.length === 0 || clock === null ? (
              <p className="type-body-sm text-gray-500">등록된 회차가 없어요</p>
            ) : (
              <ul className="grid gap-3">
                {concert.data.schedules.map((schedule) => (
                  <li key={schedule.scheduleId}>
                    <ScheduleCard schedule={schedule} clock={clock} />
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </main>
  )
}

// Note: 판매 상태는 응답의 saleStatus를 그대로 쓰지 않고 서버 시각으로 보정한 지금으로 다시 정한다.
// 오픈 전이면 카운트다운이 0이 되는 순간 onComplete로 다시 그려 [예매하기]를 켠다. 같은 0을 보는 카운트다운이
// 다시 그리게 해서 "00:00"이 보이는 순간과 버튼이 켜지는 순간이 같다(남은 시간 올림, design-system.md 7절).
// 서버가 CLOSED라고 하면 시각과 상관없이 따른다.
function ScheduleCard({ schedule, clock }: { schedule: ScheduleSummary; clock: ServerClock }) {
  // 보는 중에 오픈했는지. 카드를 다시 그리고, 화면 낭독기에 열렸다고 알린다
  const [openedWhileViewing, setOpenedWhileViewing] = useState(false)
  const status = schedule.saleStatus === 'CLOSED' ? 'CLOSED' : saleStatusAt(schedule, clock.now())
  const openAtId = `open-at-${schedule.scheduleId}`

  return (
    <Card className="gap-4 px-5 @min-[40rem]:flex-row @min-[40rem]:items-center @min-[40rem]:justify-between">
      <div className="grid min-w-0 gap-2">
        <div>
          <SaleStatusBadge status={status} />
        </div>
        <p className="type-heading tabular-nums text-gray-900">
          {formatDateTime(schedule.startsAt)}
        </p>
        <p className="type-body-sm text-gray-500">
          {schedule.venueName} · 1인 최대 {schedule.maxPerUser}매
        </p>
      </div>

      <div className="grid shrink-0 gap-2 @min-[40rem]:min-w-[200px] @min-[40rem]:justify-items-end @min-[40rem]:text-right">
        {status === 'BEFORE_OPEN' ? (
          <>
            <span className="type-caption text-gray-500">오픈까지</span>
            <Countdown
              targetAt={schedule.openAt}
              clock={clock}
              className="type-title"
              onComplete={() => setOpenedWhileViewing(true)}
            />
            <Button disabled className="w-full @min-[40rem]:w-auto" aria-describedby={openAtId}>
              예매하기
            </Button>
            <span id={openAtId} className="type-caption tabular-nums text-gray-500">
              {formatDateTime(schedule.openAt)} 오픈
            </span>
          </>
        ) : status === 'ON_SALE' ? (
          <Button asChild className="w-full @min-[40rem]:w-auto">
            <Link to={queuePath(schedule.scheduleId)}>예매하기</Link>
          </Button>
        ) : (
          <span className="type-body-sm text-gray-500">판매가 끝났어요</span>
        )}
        {/* 카운트다운은 매초 읽지 않으므로(timer) 열린 순간만 알린다. 알림 영역은 처음부터 있어야 읽힌다 */}
        <p aria-live="polite" className="sr-only">
          {openedWhileViewing && status === 'ON_SALE' ? '예매가 열렸어요' : ''}
        </p>
      </div>
    </Card>
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
      <Card className="flex-row gap-5 px-5">
        <Skeleton className="h-24 w-[72px] rounded-sm" />
        <div className="grid flex-1 content-start gap-2">
          <Skeleton className="h-8 w-2/3" />
          <Skeleton className="h-4 w-1/2" />
        </div>
      </Card>
      <Skeleton className="h-6 w-16" />
      <Skeleton className="h-32 w-full rounded-lg" />
    </div>
  )
}
