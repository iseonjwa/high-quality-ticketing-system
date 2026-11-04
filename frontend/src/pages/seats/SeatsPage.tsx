import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import { flushSync } from 'react-dom'
import { Link, Navigate, useLocation, useNavigate, useParams } from 'react-router'
import { toast } from 'sonner'
import { isApiError, isOutcomeUnknown } from '@/api/apiError'
import { concertQueryKeys, getConcert, getScheduleSeats, type ScheduleSeats } from '@/api/concerts'
import { describeError } from '@/api/errorMessages'
import type { HeldSeatHold } from '@/api/reservations'
import { concertPath, paths, paymentPath, queuePath, reservationPath } from '@/app/paths'
import { readEntryToken, removeEntryToken, type StoredEntryToken } from '@/auth/entryTokens'
import { HeaderSlot } from '@/components/layout/HeaderSlot'
import { ConcertPoster } from '@/components/ticketing/ConcertPoster'
import { EmptyState } from '@/components/ticketing/EmptyState'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { GradeBadge } from '@/components/ticketing/GradeBadge'
import { QuantitySelector } from '@/components/ticketing/QuantitySelector'
import { SeatLegend } from '@/components/ticketing/SeatLegend'
import { SeatSectionGrid } from '@/components/ticketing/SeatSectionGrid'
import { TimeChip } from '@/components/ticketing/TimeChip'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime, formatWon } from '@/lib/format'
import { readRouteId } from '@/lib/routeId'
import { createServerClock, type ServerClock } from '@/lib/serverClock'
import { paymentNavigationState } from '@/pages/payment/paymentNavigation'
import { readQueueConcert, type QueueConcert } from '@/pages/queue/queueNavigation'
import { HeldDialog } from '@/pages/seats/HeldDialog'
import {
  findSeat,
  firstSectionWithSeats,
  lostSeatIds,
  remainingSeats,
  seatLabel,
  totalPrice,
  type SelectedSeat,
} from '@/pages/seats/seatSections'
import { SelectionSummary } from '@/pages/seats/SelectionSummary'
import { useHoldRequest } from '@/pages/seats/useHoldRequest'
import { ZonePicker } from '@/pages/seats/ZonePicker'

// 좌석 화면(ISE-115, samples/seat-sections.html). 매수를 고르고, 구역을 골라 그 구역 좌석만 그린다.
// 공연장이 크면(로컬 KSPO DOME 15,000석) 한 화면에 다 그리지 않는다. 고른 좌석은 구역을 옮겨도 요약에 남는다.

// 회차 응답에 1인 최대 매수가 없을 때(공연 요약 없이 주소로 들어옴) 쓰는 서버 기본값(SA 결정 #35, Schedule 기본 4)
const DEFAULT_MAX_PER_USER = 4
const DEFAULT_QUANTITY = 2

const seatQueryKey = (scheduleId: number) => ['seats', scheduleId] as const

type SeatNotice =
  // 409. lost는 다시 조회해 보니 고를 수 없게 된 좌석 이름이다. 비었으면 겹친 좌석이 그사이 풀렸다
  | { kind: 'conflict'; lost: string[] }
  // 다시 보내도 결과를 끝내 모른다. 같은 좌석으로 다시 누르면 같은 멱등키로 결과를 확인한다
  | { kind: 'unknown' }
  | { kind: 'error'; error: unknown }

export function SeatsPage() {
  const scheduleId = readRouteId(useParams().scheduleId)
  if (scheduleId === null) {
    return (
      <main className="mx-auto w-full max-w-[560px]">
        <h1 className="sr-only">좌석 선택</h1>
        <Alert
          variant="warning"
          action={
            <Button asChild variant="outline" size="sm">
              <Link to={paths.home}>목록으로</Link>
            </Button>
          }
        >
          찾는 회차가 없어요. 목록에서 다시 골라 주세요
        </Alert>
      </main>
    )
  }
  return <SeatsEntry key={scheduleId} scheduleId={scheduleId} />
}

// 저장한 입장 토큰이 없으면(새 탭, 주소로 들어옴) 요청 없이 대기열로 보낸다. 대기열이 줄을 세워 토큰을 받아 온다.
// Note: 서버가 토큰을 거절한 403은 여기서 대기열로 보내지 않고 안내만 한다. 자동으로 보내면 토큰이 계속 거절될 때
// (Gateway 설정 오류 등) 대기열과 좌석 화면을 끝없이 오간다.
function SeatsEntry({ scheduleId }: { scheduleId: number }) {
  const locationState: unknown = useLocation().state
  const [entry] = useState(() => readEntryToken(scheduleId))
  if (entry === null) {
    return <Navigate to={queuePath(scheduleId)} replace state={locationState} />
  }
  return <SeatsScreen scheduleId={scheduleId} entry={entry} locationState={locationState} />
}

function SeatsScreen({
  scheduleId,
  entry,
  locationState,
}: {
  scheduleId: number
  entry: StoredEntryToken
  locationState: unknown
}) {
  const navigate = useNavigate()
  const concert = readQueueConcert(locationState)
  // 응답이 830KB 안팎(15,000석)이라 창에 돌아올 때마다 다시 받지 않는다. 409 뒤와 [다시 시도]에서만 다시 받는다
  const seats = useQuery({
    queryKey: seatQueryKey(scheduleId),
    queryFn: () => getScheduleSeats(scheduleId),
    refetchOnWindowFocus: false,
  })
  // 1인 최대 매수는 공연 상세에만 있다. 공연 상세에서 왔으면 캐시에 있어 요청이 다시 나가지 않는다
  const concertDetail = useQuery({
    queryKey: concertQueryKeys.detail(concert?.concertId ?? 0),
    queryFn: () => getConcert(concert!.concertId),
    enabled: concert !== null,
  })
  const maxPerUser = concertDetail.data?.schedules.find(
    (schedule) => schedule.scheduleId === scheduleId,
  )?.maxPerUser

  const clock = useMemo(
    () => (seats.data ? createServerClock(seats.data.serverTime, seats.dataUpdatedAt) : undefined),
    [seats.data, seats.dataUpdatedAt],
  )
  const [entryExpired, setEntryExpired] = useState(false)
  // 409 뒤 다시 받기에서 서버가 토큰을 거절해도 입장 만료와 같다. [다시 시도]로는 풀리지 않는다
  const tokenRejected = isApiError(seats.error, 'ENTRY_TOKEN_INVALID')
  useAdmittedTabTitle()

  function backToQueue() {
    // 서버 시각으로 끝난 토큰이다. 브라우저 시계가 늦으면 대기열이 아직 유효하다고 보고 좌석으로 되돌려 보낸다
    removeEntryToken(scheduleId, entry.token)
    void navigate(queuePath(scheduleId), { replace: true, state: locationState })
  }

  const entryChip = (onExpire?: () => void) => (
    <TimeChip label="입장 유효" expiresAt={entry.expiresAt} clock={clock} onExpire={onExpire} />
  )

  return (
    <main className="grid gap-5">
      <HeaderSlot>{entryChip()}</HeaderSlot>
      {/* 앱 화면이 56rem보다 좁으면 헤더 자리가 숨으니 본문 맨 위에 둔다. 만료 처리는 늘 그려지는 이쪽이 맡는다 */}
      <div className="flex justify-end @min-[56rem]:hidden">
        {entryChip(() => setEntryExpired(true))}
      </div>
      {concert !== null ? (
        <ConcertCard concert={concert} />
      ) : (
        <h1 className="sr-only">좌석 선택</h1>
      )}
      {seats.data ? (
        <SeatSelection
          scheduleId={scheduleId}
          data={seats.data}
          refetchSeats={() => seats.refetch()}
          refetchError={seats.isError && !tokenRejected ? seats.error : null}
          maxPerUser={maxPerUser}
          concert={concert}
          clock={clock}
          entryExpired={entryExpired || tokenRejected}
          onEntryExpired={() => setEntryExpired(true)}
          onBackToQueue={backToQueue}
        />
      ) : seats.isError ? (
        tokenRejected ? (
          <EntryExpiredAlert onBackToQueue={backToQueue} />
        ) : (
          <ErrorNotice error={seats.error} onRetry={() => void seats.refetch()} />
        )
      ) : (
        <SeatsSkeleton />
      )}
    </main>
  )
}

function SeatSelection({
  scheduleId,
  data,
  refetchSeats,
  refetchError,
  maxPerUser,
  concert,
  clock,
  entryExpired,
  onEntryExpired,
  onBackToQueue,
}: {
  scheduleId: number
  data: ScheduleSeats
  refetchSeats: () => Promise<{ data?: ScheduleSeats; isError: boolean }>
  refetchError: unknown
  maxPerUser: number | undefined
  concert: QueueConcert | null
  clock: ServerClock | undefined
  entryExpired: boolean
  onEntryExpired: () => void
  onBackToQueue: () => void
}) {
  const navigate = useNavigate()
  const { sections } = data
  const hold = useHoldRequest(scheduleId)

  const [quantityChoice, setQuantityChoice] = useState(DEFAULT_QUANTITY)
  // 공연 상세를 늦게 받아 한도가 줄면 매수와 고른 좌석도 그만큼 줄여 보인다
  const max = maxPerUser ?? DEFAULT_MAX_PER_USER
  const quantity = Math.min(quantityChoice, max)
  const [selectedIds, setSelectedIds] = useState<number[]>([])
  const selectedSeatIds = selectedIds.slice(0, quantity)
  const [sectionChoice, setSectionChoice] = useState<string | null>(null)
  const [justTaken, setJustTaken] = useState<number[]>([])
  const [notice, setNotice] = useState<SeatNotice | null>(null)
  const [held, setHeld] = useState<HeldSeatHold | null>(null)
  // 알림 자리. 409·결과 모름·실패를 알릴 때 초점을 옮긴다. [선택 완료]가 비활성으로 바뀌어 초점이 body로 떨어지고,
  // 끼워 넣은 role=alert는 화면 낭독기가 읽지 않을 수 있어서 초점으로 내용을 읽힌다
  const noticeRef = useRef<HTMLDivElement>(null)

  const current =
    sections.find((section) => section.section === sectionChoice) ??
    sections.find((section) => section.section === firstSectionWithSeats(sections))

  if (current === undefined) {
    return (
      <EmptyState
        title="이 회차에는 아직 좌석이 없어요"
        action={
          <Button asChild variant="outline">
            <Link to={concert ? concertPath(concert.concertId) : paths.home}>
              {concert ? '공연 상세로' : '공연 목록으로'}
            </Link>
          </Button>
        }
      />
    )
  }

  const selectedSeats: SelectedSeat[] = selectedSeatIds.flatMap((seatId) => {
    const seat = findSeat(sections, seatId)
    return seat ? [{ ...seat, seatId }] : []
  })
  const pickedBySection = new Map<string, number>()
  for (const seat of selectedSeats) {
    pickedBySection.set(seat.section, (pickedBySection.get(seat.section) ?? 0) + 1)
  }
  const soldOut = sections.every((section) => remainingSeats(section) === 0)
  const locked = entryExpired || held !== null || hold.busy

  function changeQuantity(next: number) {
    setQuantityChoice(next)
    setSelectedIds((previous) => previous.slice(0, next))
  }

  function toggleSeat(seatId: number) {
    if (locked) {
      return
    }
    if (selectedSeatIds.includes(seatId)) {
      setSelectedIds(selectedSeatIds.filter((id) => id !== seatId))
    } else if (selectedSeatIds.length >= quantity) {
      toast('먼저 고른 좌석을 해제해 주세요')
    } else {
      setSelectedIds([...selectedSeatIds, seatId])
    }
  }

  // 상태를 바꾼 화면을 먼저 그린 뒤 알림 자리로 초점을 옮긴다
  function announce(update: () => void) {
    flushSync(update)
    noticeRef.current?.focus()
  }

  async function submit() {
    const requested = selectedSeatIds
    setNotice(null)
    setJustTaken([])
    const outcome = await hold.submit(requested)
    switch (outcome.kind) {
      case 'held':
        if (outcome.hold.status === 'CONFIRMED') {
          // 같은 키 재요청인데 이미 결제가 끝났다. 이 화면에서는 거의 일어나지 않는다
          toast('이미 결제까지 끝난 예매예요')
          void navigate(reservationPath(outcome.hold.reservationId), { replace: true })
          return
        }
        setHeld(outcome.hold)
        return
      case 'conflict':
        await recoverFromConflict(requested)
        return
      case 'entryExpired':
        announce(onEntryExpired)
        return
      case 'failed':
        announce(() =>
          setNotice(
            isOutcomeUnknown(outcome.error)
              ? { kind: 'unknown' }
              : { kind: 'error', error: outcome.error },
          ),
        )
        return
      case 'aborted':
        return
    }
  }

  // 409는 어느 좌석이 겹쳤는지 알려 주지 않는다. 좌석을 다시 받아 고를 수 없게 된 좌석만 풀고 나머지는 둔다
  async function recoverFromConflict(requested: number[]) {
    const result = await refetchSeats()
    if (result.isError || result.data === undefined) {
      announce(() => setNotice({ kind: 'conflict', lost: [] }))
      return
    }
    const fresh = result.data.sections
    const lost = lostSeatIds(requested, fresh)
    const firstLost = lost.length > 0 ? findSeat(fresh, lost[0]) : undefined
    announce(() => {
      setSelectedIds(requested.filter((seatId) => !lost.includes(seatId)))
      setJustTaken(lost)
      if (firstLost) {
        setSectionChoice(firstLost.section)
      }
      setNotice({
        kind: 'conflict',
        lost: lost.flatMap((seatId) => {
          const seat = findSeat(fresh, seatId) ?? findSeat(sections, seatId)
          return seat ? [seatLabel(seat)] : []
        }),
      })
    })
  }

  function goToPayment(hold: HeldSeatHold) {
    const heldSeats = hold.seatIds.flatMap((seatId) => {
      const seat = findSeat(sections, seatId)
      return seat ? [{ seatId, ...seat }] : []
    })
    // 결제 화면에서 뒤로 가면 공연 상세로 간다. 좌석 화면으로 돌아오면 잡은 좌석을 두고 또 고르게 된다
    void navigate(paymentPath(hold.reservationId), {
      replace: true,
      state: paymentNavigationState({
        hold,
        seats: heldSeats.map(({ seatId, section, grade, price, row, number }) => ({
          seatId,
          section,
          grade,
          price,
          row,
          number,
        })),
        concert,
      }),
    })
  }

  function reselectAfterPaymentExpired() {
    setHeld(null)
    setSelectedIds([])
    setNotice(null)
    void refetchSeats()
  }

  return (
    <div className="grid items-start gap-5 @min-[60rem]:grid-cols-[minmax(0,1fr)_340px]">
      <section aria-labelledby="seat-map-title">
        <Card className="gap-0 px-4 py-5 @min-[40rem]:p-6">
          <div className="mb-5 flex flex-col gap-4 @min-[40rem]:flex-row @min-[40rem]:items-end @min-[40rem]:justify-between">
            <div>
              <h2 id="seat-map-title" className="type-heading text-gray-900">
                좌석 선택
              </h2>
              <p className="type-body-sm text-gray-500">
                매수를 고르고, 구역을 눌러 그 구역의 좌석을 골라 주세요.
              </p>
            </div>
            <div className="grid gap-1 @min-[40rem]:justify-items-end">
              <QuantitySelector
                value={quantity}
                max={max}
                onChange={changeQuantity}
                disabled={locked}
              />
              {maxPerUser !== undefined ? (
                <p className="type-caption text-gray-500">회차당 최대 {maxPerUser}매</p>
              ) : null}
            </div>
          </div>
          <div
            ref={noticeRef}
            tabIndex={-1}
            className="grid gap-3 outline-none empty:hidden [&:not(:empty)]:mb-4"
          >
            {entryExpired ? <EntryExpiredAlert onBackToQueue={onBackToQueue} /> : null}
            {refetchError !== null ? (
              <ErrorNotice error={refetchError} onRetry={() => void refetchSeats()} />
            ) : null}
            {notice !== null ? (
              <SeatNoticeAlert notice={notice} onClose={() => setNotice(null)} />
            ) : null}
            {soldOut ? (
              <Alert
                variant="info"
                action={
                  concert ? (
                    <Button asChild variant="outline" size="sm">
                      <Link to={concertPath(concert.concertId)}>다른 회차 보기</Link>
                    </Button>
                  ) : undefined
                }
              >
                남은 좌석이 없어요
              </Alert>
            ) : null}
          </div>
          <ZonePicker
            sections={sections}
            current={current.section}
            pickedBySection={pickedBySection}
            onSelect={setSectionChoice}
          />
          <div className="mt-6 mb-3 flex flex-wrap items-baseline justify-between gap-3 border-t border-gray-100 pt-5">
            <h3 className="flex items-center gap-2 type-heading text-gray-900">
              <GradeBadge grade={current.grade} />
              {current.section}구역
              <span className="type-body-sm font-medium text-gray-500 tabular-nums">
                {formatWon(current.price)}
              </span>
            </h3>
            <span className="type-body-sm text-gray-500 tabular-nums">
              남은 {remainingSeats(current).toLocaleString('ko-KR')}석
            </span>
          </div>
          <div
            role="group"
            aria-label={`${current.section}구역 좌석`}
            data-slot="seat-map"
            data-locked={entryExpired ? '' : undefined}
            className="overflow-x-auto p-1"
          >
            <div className="mx-auto w-max">
              <SeatSectionGrid
                key={current.section}
                section={current}
                selectedSeatIds={selectedSeatIds}
                justTakenSeatIds={justTaken}
                locked={entryExpired || held !== null}
                onToggle={toggleSeat}
              />
            </div>
          </div>
          <SeatLegend
            grades={[{ grade: current.grade, price: current.price }]}
            showLottery={false}
          />
        </Card>
      </section>
      <SelectionSummary
        seats={selectedSeats}
        quantity={quantity}
        total={totalPrice(selectedSeatIds, sections)}
        request={hold.state}
        locked={entryExpired || held !== null}
        onRemove={(seatId) => setSelectedIds(selectedSeatIds.filter((id) => id !== seatId))}
        onClear={() => {
          setSelectedIds([])
          setNotice(null)
        }}
        onSubmit={() => void submit()}
      />
      {held !== null ? (
        <HeldDialog
          hold={held}
          seats={held.seatIds.flatMap((seatId) => {
            const seat = findSeat(sections, seatId)
            return seat ? [{ ...seat, seatId }] : []
          })}
          clock={clock}
          onPay={() => goToPayment(held)}
          onReselect={reselectAfterPaymentExpired}
        />
      ) : null}
    </div>
  )
}

function SeatNoticeAlert({ notice, onClose }: { notice: SeatNotice; onClose: () => void }) {
  switch (notice.kind) {
    case 'conflict':
      return (
        <Alert variant="warning" onClose={onClose}>
          <p className="font-semibold">이미 선택된 좌석입니다</p>
          <p>
            {notice.lost.length > 0
              ? `${notice.lost.join(', ')}이 방금 다른 분께 선택됐어요. 남은 좌석은 그대로 두었어요.`
              : '다른 분이 먼저 고른 좌석이 있었어요. 좌석을 새로 불러왔으니 다시 선택 완료를 눌러 주세요.'}
          </p>
        </Alert>
      )
    case 'unknown':
      return (
        <Alert variant="warning" onClose={onClose}>
          <p className="font-semibold">좌석을 잡았는지 확인하지 못했어요</p>
          <p>같은 좌석으로 다시 선택 완료를 누르면 결과를 확인해요.</p>
        </Alert>
      )
    case 'error':
      if (isApiError(notice.error, 'PURCHASE_LIMIT_EXCEEDED')) {
        return (
          <Alert
            variant="warning"
            onClose={onClose}
            action={
              <Button asChild variant="outline" size="sm">
                <Link to={paths.myReservations}>내 예매 보기</Link>
              </Button>
            }
          >
            {describeError(notice.error).message}
          </Alert>
        )
      }
      return <ErrorNotice error={notice.error} onClose={onClose} />
  }
}

function EntryExpiredAlert({ onBackToQueue }: { onBackToQueue: () => void }) {
  return (
    <Alert
      variant="danger"
      action={
        <Button variant="outline" size="sm" onClick={onBackToQueue}>
          대기열로 돌아가기
        </Button>
      }
    >
      <p className="font-semibold">입장 시간이 끝났어요</p>
      <p>다시 줄을 서서 입장해 주세요.</p>
    </Alert>
  )
}

function ConcertCard({ concert }: { concert: QueueConcert }) {
  return (
    <Card className="flex-row items-center gap-4 px-4 py-4 @min-[40rem]:gap-5 @min-[40rem]:px-5 @min-[40rem]:py-5">
      <ConcertPoster concertId={concert.concertId} className="h-[72px] w-14" />
      <div className="min-w-0">
        <h1 className="type-title text-gray-900 @max-[40rem]:text-[20px] @max-[40rem]:leading-[28px]">
          {concert.title}
        </h1>
        <p className="mt-1 type-body-sm text-gray-500 tabular-nums">
          {formatDateTime(concert.startsAt)} · {concert.venueName}
        </p>
      </div>
    </Card>
  )
}

function SeatsSkeleton() {
  return (
    <div
      role="status"
      className="grid items-start gap-5 @min-[60rem]:grid-cols-[minmax(0,1fr)_340px]"
    >
      <span className="sr-only">좌석을 불러오고 있어요</span>
      <Card className="gap-4 px-4 py-5 @min-[40rem]:p-6">
        <Skeleton className="h-7 w-32" />
        <div className="grid grid-cols-4 gap-2 @min-[40rem]:grid-cols-5">
          {Array.from({ length: 10 }, (_, index) => (
            <Skeleton key={index} className="h-[60px]" />
          ))}
        </div>
        <Skeleton className="h-64 w-full" />
      </Card>
      <Card className="gap-4 px-4 py-5 @min-[40rem]:p-6">
        <Skeleton className="h-7 w-28" />
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-[52px] w-full" />
      </Card>
    </div>
  )
}

// 다른 탭을 보는 사이에 차례가 돼 좌석 화면이 열리면 탭 제목으로 알린다. 탭으로 돌아오면 원래 제목으로 되돌린다.
// 입장 시간(10분)이 이때부터 줄어든다(ISE-114 "나중에 고칠 것")
function useAdmittedTabTitle() {
  useEffect(() => {
    if (document.visibilityState !== 'hidden') {
      return
    }
    const original = document.title
    document.title = '입장했어요 · 좌석을 골라 주세요'
    function restore() {
      if (document.visibilityState === 'visible') {
        document.title = original
        document.removeEventListener('visibilitychange', restore)
      }
    }
    document.addEventListener('visibilitychange', restore)
    return () => {
      document.removeEventListener('visibilitychange', restore)
      document.title = original
    }
  }, [])
}
