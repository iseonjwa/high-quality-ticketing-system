import { useQuery } from '@tanstack/react-query'
import { Check, TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { isApiError } from '@/api/apiError'
import { getReservation, reservationQueryKeys, type ReservationDetail } from '@/api/reservations'
import { paths, paymentPath } from '@/app/paths'
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
  type ReservationHeading,
} from '@/pages/reservations/reservationNavigation'

// 예매 상세(ISE-117, samples/reservations.html). 같은 화면이 두 경로로 열린다.
// - complete(`/reservations/{id}/complete`): 결제 화면이 확정을 본 뒤 바꿔 들어온 결과 화면. 예매 단계 "완료"
// - detail(`/reservations/{id}`): 내 예매 목록이나 주소로 연 상세
// 예매 상세 응답에는 공연 제목·일시가 없어 화면 이동 값(heading)으로 받고, 없으면 예매 번호를 제목으로 쓴다.

// 결제 토큰은 선점 만료보다 10초 먼저 끝난다(SA 7-3). 결제 가능 시각을 이 값으로 보인다
const PAYMENT_TOKEN_MARGIN_MILLIS = 10_000

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
  })

  if (reservation.isPending) {
    return <ReservationSkeleton />
  }
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

  const detail = reservation.data
  switch (detail.status) {
    case 'CONFIRMED':
      return variant === 'complete' ? (
        <ConfirmedResult detail={detail} heading={heading} />
      ) : (
        <ConfirmedDetail detail={detail} heading={heading} />
      )
    case 'HELD':
      return <HeldDetail detail={detail} heading={heading} />
    default:
      return <EndedDetail detail={detail} heading={heading} />
  }
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
    <main className="mx-auto grid w-full max-w-[720px] gap-5">
      <ResultHead
        tone="success"
        title="예매가 확정됐어요"
        description={heading ? headingLine(heading) : `예매 번호 ${detail.reservationNo}`}
      />
      <TicketsCard detail={detail} showStatus />
      <Actions>
        <Button asChild variant="outline" size="lg">
          <Link to={paths.myReservations}>내 예매 목록</Link>
        </Button>
        <Button asChild size="lg">
          <Link to={paths.home}>다른 공연 보기</Link>
        </Button>
      </Actions>
    </main>
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
    <main className="mx-auto grid w-full max-w-[720px] gap-5">
      <DetailHead detail={detail} heading={heading} />
      <TicketsCard detail={detail} showStatus={false} />
      <Actions>
        <Button asChild variant="outline" size="lg">
          <Link to={paths.myReservations}>내 예매 목록</Link>
        </Button>
      </Actions>
    </main>
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
    <main className="mx-auto grid w-full max-w-[720px] gap-5">
      <DetailHead detail={detail} heading={heading} />
      {detail.paymentToken !== null && payableUntil !== null ? (
        <Alert
          variant="warning"
          action={
            <Button asChild variant="outline" size="sm">
              <Link to={paymentPath(detail.reservationId)}>결제하기</Link>
            </Button>
          }
        >
          <p className="font-semibold">아직 결제하지 않았어요</p>
          <p>{formatClock(payableUntil)}까지 결제하지 않으면 좌석이 풀려요.</p>
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
    </main>
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
    <main className="mx-auto grid w-full max-w-[720px] gap-5">
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
    </main>
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

// 확정 예매의 좌석별 티켓 번호
// showStatus: 머리에 상태 배지가 없을 때(결과 화면)만 카드에 둔다
function TicketsCard({ detail, showStatus }: { detail: ReservationDetail; showStatus: boolean }) {
  const ticketBySeat = new Map(detail.tickets.map((ticket) => [ticket.seatId, ticket.ticketNo]))
  return (
    <section aria-labelledby="tickets-title">
      <Card className="gap-4 px-4 py-5 @min-[40rem]:p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="tickets-title" className="type-heading text-gray-900">
            티켓 {detail.seats.length}매
          </h2>
          {showStatus ? <ReservationStatusBadge status={detail.status} /> : null}
        </div>
        <ul className="grid gap-2">
          {detail.seats.map((seat) => {
            const ticketNo = ticketBySeat.get(seat.seatId)
            return (
              <li
                key={seat.seatId}
                className="grid grid-cols-[auto_1fr_auto] items-center gap-x-3 gap-y-1 rounded-md bg-gray-50 px-4 py-3 type-body-sm"
              >
                <GradeBadge grade={seat.grade} />
                <span className="tabular-nums">
                  {seat.section}구역{' '}
                  <b className="font-semibold text-gray-900">
                    {seat.row}열 {seat.number}번
                  </b>
                </span>
                <span className="tabular-nums">{formatWon(seat.price)}</span>
                {ticketNo ? (
                  <span className="col-start-2 col-end-4 type-caption text-gray-500 tabular-nums">
                    티켓 번호 <b className="font-semibold text-gray-900">{ticketNo}</b>
                  </span>
                ) : null}
              </li>
            )
          })}
        </ul>
        <Facts
          rows={[
            ['예매 번호', detail.reservationNo],
            ['결제 금액', formatWon(detail.totalAmount)],
          ]}
        />
      </Card>
    </section>
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

function Facts({ rows }: { rows: [string, string][] }) {
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-5 gap-y-2 border-t border-gray-100 pt-4 type-body-sm">
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
