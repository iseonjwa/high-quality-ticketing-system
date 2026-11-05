import { useQuery } from '@tanstack/react-query'
import { Check, TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { isApiError } from '@/api/apiError'
import { getReservation, reservationQueryKeys, type ReservationDetail } from '@/api/reservations'
import { paths, paymentPath } from '@/app/paths'
import { readPaymentAttempt } from '@/auth/paymentAttempts'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { GradeBadge } from '@/components/ticketing/GradeBadge'
import { ReservationStatusBadge } from '@/components/ticketing/ReservationStatusBadge'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime, formatWon } from '@/lib/format'
import { instantMillis } from '@/lib/instant'
import { readRouteId } from '@/lib/routeId'
import { cn } from '@/lib/utils'
import {
  readReservationHeading,
  reservationNavigationState,
  type ReservationHeading,
} from '@/pages/reservations/reservationNavigation'

// 예매 상세(ISE-117, samples/reservations.html). 같은 화면이 두 경로로 열린다.
// - complete(`/reservations/{id}/complete`): 결제 화면이 확정을 본 뒤 바꿔 들어온 결과 화면. 예매 단계 "완료"
// - detail(`/reservations/{id}`): 내 예매 목록이나 주소로 연 상세
// 예매 상세 응답에는 공연 제목·일시가 없어 화면 이동 값(heading)으로 받고, 없으면 예매 번호를 제목으로 쓴다.

// 결제 토큰은 선점 만료보다 10초 먼저 끝난다(SA 7-3). 결제 가능 시각을 이 값으로 보인다
const PAYMENT_TOKEN_MARGIN_MILLIS = 10_000
// 결제 대기 동안 다시 받는 간격. 결제 확정(이벤트로 2초 안)이나 선점 만료를 화면에 반영한다
const HELD_POLL_MILLIS = 5_000
// 선점 만료 뒤 만료 스케줄러가 EXPIRED로 바꿀 때까지 더 기다리는 시간
const EXPIRY_GRACE_MILLIS = 30_000
// 확정은 끝난 상태라(취소 API 없음) 결제 화면이 넣은 값을 이 시간 동안 다시 받지 않고 쓴다
const CONFIRMED_STALE_MILLIS = 60_000

function shouldPollHeld(detail: ReservationDetail | undefined): boolean {
  if (detail?.status !== 'HELD') {
    return false
  }
  return detail.holdExpiresAt === null
    ? true
    : Date.now() < instantMillis(detail.holdExpiresAt) + EXPIRY_GRACE_MILLIS
}

export function ReservationPage({ variant }: { variant: 'detail' | 'complete' }) {
  const reservationId = readRouteId(useParams().reservationId)
  if (reservationId === null) {
    return <MissingReservation />
  }
  return <ReservationScreen key={reservationId} reservationId={reservationId} variant={variant} />
}

function ReservationScreen({
  reservationId,
  variant,
}: {
  reservationId: number
  variant: 'detail' | 'complete'
}) {
  const heading = readReservationHeading(useLocation().state)
  const reservation = useQuery({
    queryKey: reservationQueryKeys.detail(reservationId),
    queryFn: () => getReservation(reservationId),
    staleTime: (query) => (query.state.data?.status === 'CONFIRMED' ? CONFIRMED_STALE_MILLIS : 0),
    refetchInterval: (query) => (shouldPollHeld(query.state.data) ? HELD_POLL_MILLIS : false),
  })

  // Note: 다시 받기가 실패해도 이미 받은 예매는 그대로 보인다. TanStack Query는 다시 받기 실패에도 isError가 되는데,
  // isError로 갈라 오류 화면을 그리면 결제 직후 "예매가 확정됐어요"가 부하 중 429 한 번에 사라졌다(셀프 리뷰에서 재현)
  if (reservation.data === undefined) {
    if (reservation.isError) {
      return isApiError(reservation.error, 'NOT_FOUND') ? (
        <MissingReservation />
      ) : (
        <main className="mx-auto w-full max-w-[720px]">
          <h1 className="sr-only">예매 상세</h1>
          <ErrorNotice error={reservation.error} onRetry={() => void reservation.refetch()} />
        </main>
      )
    }
    return <ReservationSkeleton />
  }
  if (isApiError(reservation.error, 'NOT_FOUND')) {
    return <MissingReservation />
  }

  const detail = reservation.data
  return (
    <main className="mx-auto grid w-full max-w-[720px] gap-5">
      {reservation.isRefetchError ? (
        <ErrorNotice error={reservation.error} onRetry={() => void reservation.refetch()} />
      ) : null}
      {detail.status === 'CONFIRMED' ? (
        variant === 'complete' ? (
          <ConfirmedResult detail={detail} heading={heading} />
        ) : (
          <ConfirmedDetail detail={detail} heading={heading} />
        )
      ) : detail.status === 'HELD' ? (
        <HeldDetail detail={detail} heading={heading} />
      ) : (
        <EndedDetail detail={detail} heading={heading} />
      )}
    </main>
  )
}

// 결제 직후 결과: 확정 표시를 크게 두고 티켓 번호를 보인다
function ConfirmedResult({
  detail,
  heading,
}: {
  detail: ReservationDetail
  heading: ReservationHeading | null
}) {
  return (
    <>
      <ResultHead
        tone="success"
        title="예매가 확정됐어요"
        description={heading ? headingLine(heading) : `예매 번호 ${detail.reservationNo}`}
      />
      <TicketsCard detail={detail} heading={heading} showStatus />
      <Actions>
        <Button asChild variant="outline" size="lg">
          <Link to={paths.myReservations}>내 예매 목록</Link>
        </Button>
        <Button asChild size="lg">
          <Link to={paths.home}>다른 공연 보기</Link>
        </Button>
      </Actions>
    </>
  )
}

function ConfirmedDetail({
  detail,
  heading,
}: {
  detail: ReservationDetail
  heading: ReservationHeading | null
}) {
  return (
    <>
      <DetailHead detail={detail} heading={heading} />
      <TicketsCard detail={detail} heading={heading} showStatus={false} />
      <Actions>
        <Button asChild variant="outline" size="lg">
          <Link to={paths.myReservations}>내 예매 목록</Link>
        </Button>
      </Actions>
    </>
  )
}

// 결제 대기: 결제 가능 시간 안이면 결제 화면으로 보낸다. 결제 화면은 예매 조회로 결제 대상을 만든다
function HeldDetail({
  detail,
  heading,
}: {
  detail: ReservationDetail
  heading: ReservationHeading | null
}) {
  const payableUntil =
    detail.holdExpiresAt === null
      ? null
      : instantMillis(detail.holdExpiresAt) - PAYMENT_TOKEN_MARGIN_MILLIS
  return (
    <>
      <DetailHead detail={detail} heading={heading} />
      {readPaymentAttempt(detail.reservationId) !== null ? (
        // 이 브라우저에서 결제를 시작했다. 결제 화면이 저장한 토큰·키로 결과를 이어서 확인한다
        <Alert
          variant="info"
          action={
            <Button asChild variant="outline" size="sm">
              <Link
                to={paymentPath(detail.reservationId)}
                state={reservationNavigationState(heading)}
              >
                결제 결과 확인
              </Link>
            </Button>
          }
        >
          <p className="font-semibold">결제를 확인하고 있어요</p>
          <p>확정되면 이 화면이 바뀌어요.</p>
        </Alert>
      ) : detail.paymentToken !== null && payableUntil !== null ? (
        // Note: "아직 결제하지 않았어요"라고 하지 않는다. 결제가 승인됐는데 확정이 늦거나 운영자 확인을 기다리는 예매도
        // HELD이고, 예매 응답에는 결제 상태가 없다(셀프 리뷰)
        <Alert
          variant="warning"
          action={
            <Button asChild variant="outline" size="sm">
              <Link
                to={paymentPath(detail.reservationId)}
                state={reservationNavigationState(heading)}
              >
                결제하기
              </Link>
            </Button>
          }
        >
          <p className="font-semibold">결제가 아직 확정되지 않았어요</p>
          <p>
            {formatClock(payableUntil)}까지 확정되지 않으면 좌석이 풀려요. 이미 결제했다면 확정까지
            잠시 걸릴 수 있어요.
          </p>
        </Alert>
      ) : (
        <Alert variant="warning">
          <p className="font-semibold">결제 가능 시간이 지났어요</p>
          <p>잡아 둔 좌석은 곧 풀려요.</p>
        </Alert>
      )}
      <SeatsCard detail={detail} title="잡아 둔 좌석" showStatus={false} />
      <Actions>
        <Button asChild variant="outline" size="lg">
          <Link to={paths.myReservations}>내 예매 목록</Link>
        </Button>
      </Actions>
    </>
  )
}

const ENDED_COPY = {
  EXPIRED: {
    title: '예매가 확정되지 않았어요',
    description: '결제 가능 시간이 지나 잡아 둔 좌석이 풀렸어요. 결제한 금액이 있으면 환불돼요.',
  },
  RELEASED: {
    title: '좌석이 풀렸어요',
    description: '결제가 거절돼 잡아 둔 좌석을 돌려놓았어요.',
  },
  CANCELED: {
    title: '취소된 예매예요',
    description: '예매를 취소해 좌석을 돌려놓았어요.',
  },
} as const

function EndedDetail({
  detail,
  heading,
}: {
  detail: ReservationDetail
  heading: ReservationHeading | null
}) {
  const copy = ENDED_COPY[detail.status as keyof typeof ENDED_COPY]
  return (
    <>
      <ResultHead
        tone="danger"
        title={copy.title}
        description={heading ? `${headingLine(heading)} · ${copy.description}` : copy.description}
      />
      <SeatsCard detail={detail} title="풀린 좌석" showStatus />
      <Actions>
        <Button asChild variant="outline" size="lg">
          <Link to={paths.myReservations}>내 예매 목록</Link>
        </Button>
        <Button asChild size="lg">
          <Link to={paths.home}>공연 보러 가기</Link>
        </Button>
      </Actions>
    </>
  )
}

function ResultHead({
  tone,
  title,
  description,
}: {
  tone: 'success' | 'danger'
  title: string
  description: string
}) {
  return (
    <Card className="items-center gap-2 px-4 pt-8 pb-6 text-center @min-[40rem]:px-6">
      <span
        aria-hidden="true"
        className={cn(
          'grid size-14 place-items-center rounded-full',
          tone === 'success' ? 'bg-success-50 text-success-600' : 'bg-danger-50 text-danger-600',
        )}
      >
        {tone === 'success' ? (
          <Check className="size-7" strokeWidth={3} />
        ) : (
          <TriangleAlert className="size-7" />
        )}
      </span>
      <h1 className="mt-2 type-title text-gray-900">{title}</h1>
      <p className="type-body-sm text-gray-500 tabular-nums">{description}</p>
    </Card>
  )
}

function DetailHead({
  detail,
  heading,
}: {
  detail: ReservationDetail
  heading: ReservationHeading | null
}) {
  return (
    <Card className="gap-1 px-4 py-5 @min-[40rem]:p-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <h1 className="type-title text-gray-900 @max-[40rem]:text-[20px] @max-[40rem]:leading-[28px]">
          {heading ? heading.title : `예매 ${detail.reservationNo}`}
        </h1>
        <ReservationStatusBadge status={detail.status} />
      </div>
      <p className="type-body-sm text-gray-500 tabular-nums">
        {heading ? `${headingMeta(heading)} · ` : ''}예매 번호 {detail.reservationNo}
      </p>
    </Card>
  )
}

// 확정 예매의 좌석별 티켓. 좌석마다 실제 모바일 티켓처럼 어두운 카드에 좌석·티켓 번호를 크게 둔다
// showStatus: 머리에 상태 배지가 없을 때(결과 화면)만 카드에 둔다
function TicketsCard({
  detail,
  heading,
  showStatus,
}: {
  detail: ReservationDetail
  heading: ReservationHeading | null
  showStatus: boolean
}) {
  const ticketBySeat = new Map(detail.tickets.map((ticket) => [ticket.seatId, ticket.ticketNo]))
  const total = detail.seats.length
  return (
    <section aria-labelledby="tickets-title" className="grid gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="tickets-title" className="type-heading text-gray-900">
          티켓 {total}매
        </h2>
        {showStatus ? <ReservationStatusBadge status={detail.status} /> : null}
      </div>
      <ul className="grid gap-4 @min-[48rem]:grid-cols-2">
        {detail.seats.map((seat, index) => {
          const ticketNo = ticketBySeat.get(seat.seatId)
          return (
            <li
              key={seat.seatId}
              className="relative overflow-hidden rounded-lg bg-gray-900 text-white shadow-md"
            >
              <span
                aria-hidden="true"
                className="pointer-events-none absolute -top-16 -right-12 size-44 rounded-full bg-primary-600/45 blur-3xl"
              />
              <div className="relative grid gap-3 p-5">
                <div className="flex items-center justify-between gap-3">
                  <span className="type-caption tracking-[0.18em] text-white/70 uppercase tabular-nums">
                    Ticket {index + 1}/{total}
                  </span>
                  <GradeBadge grade={seat.grade} />
                </div>
                {heading ? (
                  <div className="grid gap-0.5">
                    <p className="line-clamp-1 type-body font-semibold">{heading.title}</p>
                    <p className="type-caption text-white/60 tabular-nums">
                      {headingMeta(heading)}
                    </p>
                  </div>
                ) : null}
                <p className="type-title tabular-nums">
                  {seat.section}구역{' '}
                  <b className="font-extrabold">
                    {seat.row}열 {seat.number}번
                  </b>
                </p>
              </div>
              {/* 절취선: 점선과 양옆 반원 홈 */}
              <div aria-hidden="true" className="relative h-4">
                <span className="absolute inset-x-5 top-1/2 border-t-2 border-dashed border-white/15" />
                <span className="absolute top-0 -left-2 size-4 rounded-full bg-background" />
                <span className="absolute top-0 -right-2 size-4 rounded-full bg-background" />
              </div>
              <div className="relative flex items-end justify-between gap-4 px-5 pt-3 pb-5">
                <div className="grid min-w-0 gap-1 type-body-sm">
                  {ticketNo ? (
                    <span className="type-caption text-white/60 tabular-nums">
                      티켓 번호{' '}
                      <b className="block type-body-sm font-semibold break-all text-white">
                        {ticketNo}
                      </b>
                    </span>
                  ) : null}
                  <span className="tabular-nums">{formatWon(seat.price)}</span>
                </div>
                {ticketNo ? <TicketBarcode value={ticketNo} /> : null}
              </div>
            </li>
          )
        })}
      </ul>
      <Card className="px-4 py-4 @min-[40rem]:px-6">
        <Facts
          bordered={false}
          rows={[
            ['예매 번호', detail.reservationNo],
            ['결제 금액', formatWon(detail.totalAmount)],
          ]}
        />
      </Card>
    </section>
  )
}

// 티켓 번호로 그리는 꾸밈 바코드. 읽을 수 있는 코드가 아니라 모양만 낸다(티켓 번호는 글자로 따로 보인다)
function TicketBarcode({ value }: { value: string }) {
  const bars: { x: number; width: number }[] = []
  let x = 0
  for (const char of value) {
    const code = char.charCodeAt(0)
    for (const shift of [0, 2, 4]) {
      const width = ((code >> shift) % 3) + 1
      bars.push({ x, width })
      x += width + (((code >> (shift + 1)) % 2) + 1)
    }
  }
  return (
    <svg
      aria-hidden="true"
      viewBox={`0 0 ${x} 40`}
      preserveAspectRatio="none"
      className="h-10 w-24 shrink-0 text-white/85"
    >
      {bars.map((bar) => (
        <rect key={bar.x} x={bar.x} y="0" width={bar.width} height="40" fill="currentColor" />
      ))}
    </svg>
  )
}

function SeatsCard({
  detail,
  title,
  showStatus,
}: {
  detail: ReservationDetail
  title: string
  showStatus: boolean
}) {
  return (
    <section aria-labelledby="seats-title">
      <Card className="gap-4 px-4 py-5 @min-[40rem]:p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="seats-title" className="type-heading text-gray-900">
            {title}
          </h2>
          {showStatus ? <ReservationStatusBadge status={detail.status} /> : null}
        </div>
        <ul className="grid gap-2">
          {detail.seats.map((seat) => (
            <li
              key={seat.seatId}
              className="grid grid-cols-[auto_1fr_auto] items-center gap-3 rounded-md bg-gray-50 px-4 py-3 type-body-sm"
            >
              <GradeBadge grade={seat.grade} />
              <span className="tabular-nums">
                {seat.section}구역{' '}
                <b className="font-semibold text-gray-900">
                  {seat.row}열 {seat.number}번
                </b>
              </span>
              <span className="tabular-nums">{formatWon(seat.price)}</span>
            </li>
          ))}
        </ul>
        <Facts
          rows={[
            ['예매 번호', detail.reservationNo],
            ['합계', formatWon(detail.totalAmount)],
          ]}
        />
      </Card>
    </section>
  )
}

function Facts({ rows, bordered = true }: { rows: [string, string][]; bordered?: boolean }) {
  return (
    <dl
      className={cn(
        'grid grid-cols-[auto_1fr] gap-x-5 gap-y-2 type-body-sm',
        bordered && 'border-t border-gray-100 pt-4',
      )}
    >
      {rows.map(([term, value]) => (
        <div key={term} className="contents">
          <dt className="text-gray-500">{term}</dt>
          <dd className="text-gray-900 tabular-nums">{value}</dd>
        </div>
      ))}
    </dl>
  )
}

function Actions({ children }: { children: ReactNode }) {
  return (
    <div className="grid gap-2 @min-[40rem]:grid-flow-col @min-[40rem]:auto-cols-fr">
      {children}
    </div>
  )
}

function MissingReservation() {
  return (
    <main className="mx-auto w-full max-w-[560px]">
      <h1 className="sr-only">예매 상세</h1>
      <Alert
        variant="warning"
        action={
          <Button asChild variant="outline" size="sm">
            <Link to={paths.myReservations}>내 예매 보기</Link>
          </Button>
        }
      >
        찾는 예매가 없어요. 내 예매에서 다시 골라 주세요
      </Alert>
    </main>
  )
}

function ReservationSkeleton() {
  return (
    <main className="mx-auto w-full max-w-[720px]">
      <div role="status" className="grid gap-5">
        <span className="sr-only">예매를 불러오고 있어요</span>
        <Skeleton className="h-36 w-full" />
        <Skeleton className="h-56 w-full" />
      </div>
    </main>
  )
}

// 제목 아래 줄. 제목을 따로 보일 때 쓴다
function headingMeta(heading: ReservationHeading): string {
  const when = formatDateTime(heading.startsAt)
  return heading.venueName ? `${when} · ${heading.venueName}` : when
}

// 제목을 따로 보이지 않는 결과 머리(확정·끝난 예매)에 쓰는 한 줄
function headingLine(heading: ReservationHeading): string {
  const when = formatDateTime(heading.startsAt)
  return heading.venueName
    ? `${heading.title} · ${when} · ${heading.venueName}`
    : `${heading.title} · ${when}`
}

// "오후 8:14:50"처럼 한국 시각으로 보인다
function formatClock(millis: number): string {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    hour: 'numeric',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(millis))
}
