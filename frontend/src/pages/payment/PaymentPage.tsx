import { useQuery, useQueryClient } from '@tanstack/react-query'
import { CreditCard, Loader2 } from 'lucide-react'
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import { isApiError } from '@/api/apiError'
import { getReservation, reservationQueryKeys, type ReservationDetail } from '@/api/reservations'
import { paths, queuePath, reservationCompletePath, reservationPath } from '@/app/paths'
import { removeHeldSeats, type HeldSeat } from '@/auth/heldSeats'
import { HeaderSlot } from '@/components/layout/HeaderSlot'
import { ConcertHeader } from '@/components/ticketing/ConcertHeader'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { TicketPerforation } from '@/components/ticketing/TicketPerforation'
import { TimeChip } from '@/components/ticketing/TimeChip'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatWon } from '@/lib/format'
import { instantMillis } from '@/lib/instant'
import { readRouteId } from '@/lib/routeId'
import type { ServerClock } from '@/lib/serverClock'
import { readPaymentNavigation } from '@/pages/payment/paymentNavigation'
import { usePaymentFlow, type PaymentFlowState } from '@/pages/payment/usePaymentFlow'
import type { QueueConcert } from '@/pages/queue/queueNavigation'
import {
  readReservationHeading,
  reservationNavigationState,
  type ReservationHeading,
} from '@/pages/reservations/reservationNavigation'

// 결제 화면(ISE-116, samples/payment.html). 흐름은 usePaymentFlow가 정하고 이 화면은 단계별로 그리기만 한다.
// 승인 응답은 예매 확정이 아니라서, 예매 조회로 CONFIRMED를 본 뒤 예매 결과 화면(ISE-117)으로 바꿔 들어간다.

// 결제 토큰은 선점 만료보다 10초 먼저 끝난다(SA 7-3). 예매 상세에는 토큰 만료 시각이 없어 이 값으로 계산한다
const PAYMENT_TOKEN_MARGIN_MILLIS = 10_000

// 결제 화면만 쓰는 조회 키. 예매 상세 화면(['reservations', 'detail', id])과 나눠, 결제 중에 잡은 HELD 사진이
// 결과 화면에 먼저 보이지 않게 한다
const paymentReservationKey = (reservationId: number) =>
  ['reservations', 'payment', reservationId] as const

// 결제할 대상. 좌석 화면이 넘긴 값이 있으면 그것을, 없으면(새로고침이 아닌 새 탭·주소) 예매 조회로 만든다
interface PaymentSource {
  scheduleId: number
  reservationNo: string
  paymentToken: string
  paymentTokenExpiresAt: string
  holdExpiresAt: string
  amount: number
  seats: HeldSeat[]
  concert: QueueConcert | null
  // 서버 시각으로 보정한 시계. 좌석 화면에서 왔을 때만 있다
  clock: ServerClock | null
}

export function PaymentPage() {
  const reservationId = readRouteId(useParams().reservationId)
  if (reservationId === null) {
    return <MissingReservation />
  }
  return <PaymentScreen key={reservationId} reservationId={reservationId} />
}

function PaymentScreen({ reservationId }: { reservationId: number }) {
  const locationState: unknown = useLocation().state
  const navigation = readPaymentNavigation(locationState)
  // 예매 상세의 [결제하기]로 오면 공연 요약 대신 공연 제목 줄만 있다. 결과 화면에 그대로 넘긴다
  const passedHeading = readReservationHeading(locationState)
  // 예매 상태를 화면을 열 때마다 한 번 본다. 이미 확정이면 상세로, 결제할 수 없으면 안내한다.
  // Note: 예매 상세는 조회마다 결제 토큰을 새로 서명한다. 토큰 문자열이 멱등 본문에 들어가므로 열려 있는 동안
  // 다시 받지 않는다(staleTime). 떠나면 버린다(gcTime 0). 남겨 두면 결제를 끝낸 뒤 다시 열 때 옛 HELD로 결제 폼을 그렸다
  const reservation = useQuery({
    queryKey: paymentReservationKey(reservationId),
    queryFn: () => getReservation(reservationId),
    staleTime: Infinity,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  const clock = useMemo<ServerClock | null>(() => {
    const offset = navigation?.clockOffsetMillis
    return offset === null || offset === undefined ? null : { now: () => Date.now() + offset }
  }, [navigation?.clockOffsetMillis])

  let source: PaymentSource | null = null
  if (navigation !== null) {
    const { hold } = navigation
    source = {
      scheduleId: hold.scheduleId,
      reservationNo: hold.reservationNo,
      paymentToken: hold.paymentToken,
      paymentTokenExpiresAt: hold.paymentTokenExpiresAt,
      holdExpiresAt: hold.holdExpiresAt,
      amount: hold.totalAmount,
      seats: navigation.seats,
      concert: navigation.concert,
      clock,
    }
  } else if (reservation.data !== undefined) {
    source = sourceFromReservation(reservation.data)
  }

  const blocked = reservation.data ? blockedReason(reservation.data) : null

  if (blocked === 'confirmed') {
    return <ConfirmedRedirect reservationId={reservationId} />
  }
  if (source === null) {
    if (reservation.isPending) {
      return <PaymentSkeleton />
    }
    if (reservation.isError) {
      return isApiError(reservation.error, 'NOT_FOUND') ? (
        <MissingReservation />
      ) : (
        <main className="mx-auto w-full max-w-[560px]">
          <h1 className="sr-only">결제</h1>
          <ErrorNotice error={reservation.error} onRetry={() => void reservation.refetch()} />
        </main>
      )
    }
    return <NotPayable reservation={reservation.data} />
  }
  return (
    <PaymentForm
      reservationId={reservationId}
      source={source}
      passedHeading={passedHeading}
      // 좌석 화면 값으로 그렸어도 예매가 이미 끝났으면(만료·해제) 결제를 막는다
      notPayable={blocked !== null}
    />
  )
}

// 예매 조회로 결제 대상을 만든다. HELD이고 결제 토큰이 있을 때만이다(서버는 선점 만료 10초 전까지만 토큰을 준다)
function sourceFromReservation(reservation: ReservationDetail): PaymentSource | null {
  const { paymentToken, holdExpiresAt } = reservation
  if (reservation.status !== 'HELD' || paymentToken === null || holdExpiresAt === null) {
    return null
  }
  return {
    scheduleId: reservation.scheduleId,
    reservationNo: reservation.reservationNo,
    paymentToken,
    paymentTokenExpiresAt: new Date(
      instantMillis(holdExpiresAt) - PAYMENT_TOKEN_MARGIN_MILLIS,
    ).toISOString(),
    holdExpiresAt,
    amount: reservation.totalAmount,
    seats: reservation.seats.map(({ seatId, section, grade, price, row, number }) => ({
      seatId,
      section,
      grade,
      price,
      row,
      number,
    })),
    concert: null,
    clock: null,
  }
}

// 결제할 수 없는 예매 상태. HELD면 null
function blockedReason(reservation: ReservationDetail): 'confirmed' | 'ended' | null {
  if (reservation.status === 'CONFIRMED') {
    return 'confirmed'
  }
  return reservation.status === 'HELD' ? null : 'ended'
}

// 흐름 단계 중 사용자가 읽고 행동해야 하는 것. 이 단계가 되면 초점을 알림으로 옮긴다
const ATTENTION_STEPS = new Set<PaymentFlowState['step']>([
  'declined',
  'refunded',
  'manualCheck',
  'closed',
  'tokenExpired',
  'pgUnavailable',
  'slowConfirm',
  'notConfirmed',
  'failed',
])

// 결제 가능 시간을 보이는 단계. 서버에 결제가 생긴 뒤(결제 ID를 받은 확인 중, 확정 확인)와 끝난 단계에서는 숨긴다.
// 그때는 결제 가능 시간이 지나도 결제가 그대로 진행돼 시간 안내가 맞지 않는다
function showsTimer(state: PaymentFlowState): boolean {
  switch (state.step) {
    case 'ready':
    case 'paying':
    case 'pgUnavailable':
    case 'failed':
    case 'tokenExpired':
      return true
    case 'checking':
      return state.paymentId === null
    default:
      return false
  }
}

// 서버에 결제가 생겼거나 선점이 끝난 단계. 이때 저장해 둔 선점을 지워 좌석 화면이 좌석 확보 대화상자를 다시 띄우지
// 않게 한다. 결제사 장애·실패·결과 모름처럼 결제가 없을 수 있는 단계에서는 남겨, 좌석 화면으로 돌아가도 잡은 좌석으로
// 결제하러 올 수 있게 한다(셀프 리뷰: 누르자마자 지우면 살아 있는 선점을 두고 새 좌석을 또 잡았다)
function endsHold(state: PaymentFlowState): boolean {
  switch (state.step) {
    case 'confirming':
    case 'confirmed':
    case 'declined':
    case 'closed':
    case 'refunded':
    case 'notConfirmed':
    case 'manualCheck':
    case 'tokenExpired':
      return true
    case 'checking':
      return state.paymentId !== null
    default:
      return false
  }
}

function headingOf(concert: QueueConcert | null): ReservationHeading | null {
  return concert === null
    ? null
    : {
        title: concert.title,
        startsAt: concert.startsAt,
        venueName: concert.venueName,
        concertId: concert.concertId,
      }
}

function PaymentForm({
  reservationId,
  source,
  passedHeading,
  notPayable,
}: {
  reservationId: number
  source: PaymentSource
  passedHeading: ReservationHeading | null
  notPayable: boolean
}) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { state, submit } = usePaymentFlow({
    reservationId,
    paymentToken: source.paymentToken,
    amount: source.amount,
  })
  // 서버 시각으로 결제 가능 시간이 지났다. 브라우저 시계뿐이면(좌석 화면을 거치지 않음) 막지 않고 서버 응답(403)을 따른다.
  // 시계가 빠른 사용자가 시간이 남았는데 결제하지 못하는 일을 막기 위해서다(ISE-115 셀프 리뷰와 같은 문제)
  const [expiredByClock, setExpiredByClock] = useState(false)
  const alertRef = useRef<HTMLDivElement>(null)
  const completionStartedRef = useRef(false)
  const summaryHeadingRef = useRef<HTMLHeadingElement>(null)
  const tokenExpired = state.step === 'tokenExpired' || (expiredByClock && state.step === 'ready')

  // 읽고 행동해야 하는 단계가 되거나 시간이 지나 버튼이 막히면 알림으로, 버튼이 확인 중 표시로 바뀌면 요약 제목으로
  // 초점을 옮긴다. 누른 버튼이 사라지거나 막혀 초점이 body로 떨어지지 않게 한다
  useLayoutEffect(() => {
    if (ATTENTION_STEPS.has(state.step) || (expiredByClock && state.step === 'ready')) {
      alertRef.current?.focus()
    } else if (state.step === 'checking' || state.step === 'confirming') {
      summaryHeadingRef.current?.focus()
    }
  }, [state.step, expiredByClock])

  useEffect(() => {
    if (endsHold(state)) {
      removeHeldSeats(source.scheduleId)
    }
  }, [state, source.scheduleId])

  // 확정을 보면 결과 화면으로 바꿔 들어간다. 뒤로 가면 공연 상세다.
  // 결과 화면이 쓰는 예매 상세 캐시에 확정된 예매를 넣어 HELD가 먼저 보이지 않게 한다
  // Note: 결과 화면은 lazy라 이동이 끝나기 전까지 이 화면이 남아 다시 그려진다. 한 번만 이동하지 않으면
  // 다시 그려질 때마다 이동을 새로 시작해 화면 코드를 받는 이동이 계속 취소된다.
  useEffect(() => {
    if (state.step === 'confirmed' && !completionStartedRef.current) {
      completionStartedRef.current = true
      queryClient.setQueryData(reservationQueryKeys.detail(reservationId), state.reservation)
      // 내 예매 목록은 다음에 열 때 다시 받게 해 "결제 대기"가 남아 보이지 않게 한다
      void queryClient.invalidateQueries({ queryKey: reservationQueryKeys.mine })
      void navigate(reservationCompletePath(reservationId), {
        replace: true,
        state: reservationNavigationState(headingOf(source.concert) ?? passedHeading),
      })
    }
  }, [state, navigate, queryClient, reservationId, source.concert, passedHeading])

  function reselect() {
    removeHeldSeats(source.scheduleId)
    // 입장 토큰이 남았으면 대기열이 바로 좌석 화면으로 보낸다
    void navigate(queuePath(source.scheduleId), {
      replace: true,
      state: source.concert ? { concert: source.concert } : undefined,
    })
  }

  const showTimer = showsTimer(state) && !notPayable

  const timer = (onExpire?: () => void) => (
    <TimeChip
      label="결제 가능"
      expiresAt={source.paymentTokenExpiresAt}
      clock={source.clock ?? undefined}
      onExpire={onExpire}
    />
  )

  return (
    <main className="grid gap-5">
      {showTimer ? <HeaderSlot>{timer()}</HeaderSlot> : null}
      {showTimer ? (
        <div className="flex justify-end @min-[62rem]:hidden">
          {timer(source.clock ? () => setExpiredByClock(true) : undefined)}
        </div>
      ) : null}
      {source.concert !== null ? (
        <ConcertHeader concert={source.concert} />
      ) : (
        <h1 className="sr-only">결제</h1>
      )}
      <div className="grid items-start gap-5 @min-[60rem]:grid-cols-[minmax(0,1fr)_340px]">
        <section aria-labelledby="payment-title">
          <Card className="gap-5 rounded-[22px] px-4 py-5 @min-[40rem]:p-6">
            <div>
              <h2 id="payment-title" className="type-heading text-gray-900">
                결제
              </h2>
              <p className="type-body-sm text-gray-500">결제 수단을 확인하고 결제해 주세요.</p>
            </div>
            <div ref={alertRef} tabIndex={-1} className="grid gap-3 outline-none empty:hidden">
              {notPayable && state.step === 'ready' ? (
                <StepAlert
                  variant="danger"
                  title="결제할 수 없는 예매예요"
                  body="결제 가능 시간이 지났거나 좌석이 풀렸어요. 좌석을 다시 골라 주세요."
                  action={<ActionButton onClick={reselect}>좌석 다시 고르기</ActionButton>}
                />
              ) : (
                <FlowAlert state={state} tokenExpired={tokenExpired} onReselect={reselect} />
              )}
            </div>
            <fieldset className="grid gap-2">
              <legend className="mb-2 type-body-sm font-semibold text-gray-900">결제 수단</legend>
              <label className="grid cursor-pointer grid-cols-[auto_auto_1fr] items-center gap-x-3.5 gap-y-0.5 rounded-[16px] border border-primary-600 bg-primary-50 px-4 py-3.5 shadow-[inset_0_0_0_1px_var(--primary-600)]">
                <input
                  type="radio"
                  name="payment-method"
                  value="CARD"
                  defaultChecked
                  className="row-span-2 size-[18px] accent-primary-600"
                />
                <CreditCard aria-hidden="true" className="row-span-2 size-7 text-gray-900" />
                <span className="type-body font-semibold text-gray-900">신용·체크카드</span>
                <span className="type-caption text-gray-500">
                  시연용 결제라 카드 정보를 받지 않아요
                </span>
              </label>
            </fieldset>
            <dl className="grid grid-cols-[auto_1fr] gap-x-5 gap-y-2 type-body-sm">
              <dt className="text-gray-500">예매 번호</dt>
              <dd className="text-gray-900 tabular-nums">{source.reservationNo}</dd>
              <dt className="text-gray-500">좌석 유지</dt>
              <dd className="text-gray-900 tabular-nums">
                {formatClock(source.holdExpiresAt)}까지
              </dd>
            </dl>
          </Card>
        </section>
        <aside
          aria-labelledby="payment-summary-title"
          className="@min-[60rem]:sticky @min-[60rem]:top-[88px]"
        >
          {/* 티켓 모양: 절취선 위에 좌석별 금액, 아래에 합계와 결제 버튼을 둔다 */}
          <Card className="gap-0 overflow-visible rounded-[22px] p-0">
            <div className="grid gap-4 px-4 pt-5 pb-6 @min-[40rem]:px-6 @min-[40rem]:pt-6">
              <div className="flex items-baseline justify-between">
                <h2
                  ref={summaryHeadingRef}
                  id="payment-summary-title"
                  tabIndex={-1}
                  className="type-heading text-gray-900 outline-none"
                >
                  결제 금액
                </h2>
                <span className="type-body-sm text-gray-500 tabular-nums">
                  <b className="text-gray-900">{source.seats.length}</b>매
                </span>
              </div>
              <ul className="grid gap-2.5 type-body-sm">
                {source.seats.map((seat) => (
                  <li key={seat.seatId} className="flex items-baseline justify-between gap-3">
                    <span className="min-w-0 text-gray-700 tabular-nums">
                      {seat.section}구역 {seat.row}열 {seat.number}번
                      <span className="text-gray-500"> · {seat.grade}석</span>
                    </span>
                    <span className="shrink-0 text-gray-900 tabular-nums">
                      {formatWon(seat.price)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
            <TicketPerforation />
            <div className="grid gap-4 px-4 pt-5 pb-5 @min-[40rem]:px-6 @min-[40rem]:pb-6">
              <dl className="flex items-baseline justify-between">
                <dt className="type-body-sm text-gray-500">합계</dt>
                <dd className="text-[28px] leading-9 font-bold tracking-[-0.02em] text-gray-900 tabular-nums">
                  {formatWon(source.amount)}
                </dd>
              </dl>
              {/* 확인 중 표시를 끼워 넣으면 화면 낭독기가 읽지 않을 수 있어, 늘 있는 알림 영역의 글자만 바꾼다 */}
              <p role="status" className="sr-only">
                {statusText(state)}
              </p>
              <PayAction
                state={state}
                amount={source.amount}
                disabled={notPayable || tokenExpired}
                onPay={submit}
              />
              {showTimer && !tokenExpired ? (
                <p className="text-center type-caption text-gray-500">
                  결제 가능 시간이 지나면 잡아 둔 좌석이 풀려요.
                </p>
              ) : null}
            </div>
          </Card>
        </aside>
      </div>
    </main>
  )
}

// 요약 카드 아래의 결제 버튼 자리. 결과를 기다리는 동안에는 버튼 대신 확인 중 표시를 둔다
function PayAction({
  state,
  amount,
  disabled,
  onPay,
}: {
  state: PaymentFlowState
  amount: number
  disabled: boolean
  onPay: () => void
}) {
  switch (state.step) {
    case 'ready':
    case 'paying':
    case 'tokenExpired':
      return (
        <Button
          size="lg"
          className="w-full rounded-full"
          disabled={disabled || state.step === 'tokenExpired'}
          loading={state.step === 'paying'}
          onClick={onPay}
        >
          {state.step === 'paying' ? '결제하는 중' : `${formatWon(amount)} 결제하기`}
        </Button>
      )
    case 'pgUnavailable':
    case 'failed':
      return (
        <Button size="lg" className="w-full rounded-full" disabled={disabled} onClick={onPay}>
          다시 결제하기
        </Button>
      )
    case 'slowConfirm':
      // 승인을 받지 못한 채 끝났다. 같은 키로 다시 보내면 서버가 처음 결과를 돌려준다(결제가 없었으면 결제한다)
      return state.approved ? null : (
        <Button size="lg" variant="outline" className="w-full rounded-full" onClick={onPay}>
          결제 결과 확인하기
        </Button>
      )
    case 'checking':
      // 결제 ID를 받기 전(응답 없음·504)에는 요청이 서버에 닿았는지 모른다. 화면을 닫아도 된다고 하지 않는다
      return state.paymentId === null ? (
        <PendingBox
          tone="info"
          title="결제 요청을 보내고 있어요"
          body="연결이 잠시 불안정해요. 화면을 닫지 말고 기다려 주세요."
        />
      ) : (
        <PendingBox
          tone="info"
          title="결제를 확인하고 있어요"
          body="결제사 응답을 기다리는 중이에요. 화면을 닫아도 결제는 그대로 진행돼요."
        />
      )
    case 'confirming':
    case 'confirmed':
      return (
        <PendingBox
          tone="success"
          title="결제가 승인됐어요"
          body="예매를 확정하고 있어요. 잠시만 기다려 주세요."
        />
      )
    default:
      return null
  }
}

// 늘 있는 알림 영역(sr-only)에 넣을 글자. 바뀔 때마다 화면 낭독기가 읽는다
function statusText(state: PaymentFlowState): string {
  switch (state.step) {
    case 'paying':
      return '결제를 요청하고 있어요'
    case 'checking':
      return state.paymentId === null ? '결제 요청을 보내고 있어요' : '결제를 확인하고 있어요'
    case 'confirming':
      return '결제가 승인됐어요. 예매를 확정하고 있어요'
    default:
      return ''
  }
}

function PendingBox({
  tone,
  title,
  body,
}: {
  tone: 'info' | 'success'
  title: string
  body: string
}) {
  return (
    <div
      className={
        tone === 'info'
          ? 'grid grid-cols-[auto_1fr] items-start gap-3 rounded-md bg-info-50 p-4 text-info-600'
          : 'grid grid-cols-[auto_1fr] items-start gap-3 rounded-md bg-success-50 p-4 text-success-600'
      }
    >
      <Loader2 aria-hidden="true" className="mt-0.5 size-[18px] motion-safe:animate-spin" />
      <div>
        <p className="type-body font-semibold">{title}</p>
        <p className="type-body-sm">{body}</p>
      </div>
    </div>
  )
}

// 거절 사유(DeclineReason)별 첫 문장. 조회로 거절을 알면 사유가 없다(결제 조회 응답에 reasonCode가 없다)
const DECLINE_REASONS: Record<string, string> = {
  CARD_DECLINED: '카드사가 결제를 거절했어요.',
  INSUFFICIENT_LIMIT: '카드 한도가 부족해요.',
  TOKEN_EXPIRED: '결제 가능 시간이 지나 결제하지 못했어요.',
}

function FlowAlert({
  state,
  tokenExpired,
  onReselect,
}: {
  state: PaymentFlowState
  tokenExpired: boolean
  onReselect: () => void
}) {
  const reselect = <ActionButton onClick={onReselect}>좌석 다시 고르기</ActionButton>
  const myReservations = (
    <Button asChild variant="outline" size="sm">
      <Link to={paths.myReservations}>내 예매 보기</Link>
    </Button>
  )
  if (tokenExpired) {
    return (
      <StepAlert
        variant="danger"
        title="결제 가능 시간이 지났어요"
        body="잡아 둔 좌석은 곧 풀려요. 좌석을 다시 골라 주세요."
        action={reselect}
      />
    )
  }
  switch (state.step) {
    case 'declined':
      return (
        <StepAlert
          variant="danger"
          title="결제가 거절됐어요"
          body={`${(state.reasonCode && DECLINE_REASONS[state.reasonCode]) ?? ''} 잡아 둔 좌석은 풀렸으니 좌석을 다시 골라 주세요.`.trim()}
          action={reselect}
        />
      )
    case 'closed':
      return (
        <StepAlert
          variant="danger"
          title="이 예매의 결제는 이미 끝났어요"
          body="좌석을 다시 골라 새로 예매해 주세요."
          action={reselect}
        />
      )
    case 'pgUnavailable':
      return (
        <StepAlert
          variant="warning"
          title="결제사 연결이 원활하지 않아요"
          body="좌석은 남은 시간 동안 유지돼요. 잠시 뒤 다시 결제해 주세요."
        />
      )
    case 'manualCheck':
      return (
        <StepAlert
          variant="warning"
          title="결제 확인이 늦어지고 있어요"
          body="운영자가 확인한 뒤 예매 상태가 바뀌어요. 내 예매에서 확인해 주세요."
          action={myReservations}
        />
      )
    case 'refunded':
      return (
        <StepAlert
          variant="danger"
          title="예매가 확정되지 않았어요"
          body="결제 가능 시간이 지난 뒤 승인돼 좌석이 풀렸어요. 결제 금액은 환불돼요."
          action={myReservations}
        />
      )
    case 'notConfirmed':
      return (
        <StepAlert
          variant="danger"
          title="예매가 확정되지 않았어요"
          body="결제한 금액이 있으면 환불돼요. 내 예매에서 확인해 주세요."
          action={myReservations}
        />
      )
    case 'slowConfirm':
      return state.approved ? (
        <StepAlert
          variant="info"
          title="결제는 승인됐어요"
          body="예매 확정이 늦어지고 있어요. 잠시 뒤 내 예매에서 확인해 주세요."
          action={myReservations}
        />
      ) : (
        <StepAlert
          variant="warning"
          title="결제 결과를 아직 확인하지 못했어요"
          body="결제가 끝났는지 내 예매에서 확인해 주세요. 결제는 한 번만 진행돼요."
          action={myReservations}
        />
      )
    case 'failed':
      return <ErrorNotice error={state.error} />
    default:
      return null
  }
}

function StepAlert({
  variant,
  title,
  body,
  action,
}: {
  variant: 'info' | 'warning' | 'danger'
  title: string
  body: string
  action?: ReactNode
}) {
  return (
    <Alert variant={variant} action={action}>
      <p className="font-semibold">{title}</p>
      <p>{body}</p>
    </Alert>
  )
}

function ActionButton({ onClick, children }: { onClick: () => void; children: ReactNode }) {
  return (
    <Button variant="outline" size="sm" onClick={onClick}>
      {children}
    </Button>
  )
}

// 이미 확정된 예매로 결제 화면을 열면(결제 뒤 새로고침·뒤로 가기 등) 예매 상세로 바꿔 들어간다.
// 방금 결제를 끝낸 것인지 알 수 없어 결과 화면("완료")이 아니라 상세로 보낸다
function ConfirmedRedirect({ reservationId }: { reservationId: number }) {
  const navigate = useNavigate()
  useEffect(() => {
    void navigate(reservationPath(reservationId), { replace: true })
  }, [navigate, reservationId])
  return <PaymentSkeleton />
}

function NotPayable({ reservation }: { reservation: ReservationDetail | undefined }) {
  const navigate = useNavigate()
  return (
    <main className="mx-auto w-full max-w-[560px]">
      <h1 className="sr-only">결제</h1>
      <Alert
        variant="warning"
        action={
          reservation ? (
            <Button
              variant="outline"
              size="sm"
              onClick={() => void navigate(queuePath(reservation.scheduleId), { replace: true })}
            >
              좌석 다시 고르기
            </Button>
          ) : undefined
        }
      >
        <p className="font-semibold">결제할 수 없는 예매예요</p>
        <p>결제 가능 시간이 지났거나 좌석이 풀렸어요. 좌석을 다시 골라 주세요.</p>
      </Alert>
    </main>
  )
}

function MissingReservation() {
  return (
    <main className="mx-auto w-full max-w-[560px]">
      <h1 className="sr-only">결제</h1>
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

function PaymentSkeleton() {
  return (
    <main>
      <div
        role="status"
        className="grid items-start gap-5 @min-[60rem]:grid-cols-[minmax(0,1fr)_340px]"
      >
        <span className="sr-only">결제 정보를 불러오고 있어요</span>
        <Card className="gap-4 px-4 py-5 @min-[40rem]:p-6">
          <Skeleton className="h-7 w-24" />
          <Skeleton className="h-20 w-full" />
          <Skeleton className="h-12 w-2/3" />
        </Card>
        <Card className="gap-4 px-4 py-5 @min-[40rem]:p-6">
          <Skeleton className="h-7 w-28" />
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-[52px] w-full" />
        </Card>
      </div>
    </main>
  )
}

// "오후 8:14:50"처럼 한국 시각으로 보인다
function formatClock(iso: string): string {
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: 'Asia/Seoul',
    hour: 'numeric',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(instantMillis(iso)))
}
