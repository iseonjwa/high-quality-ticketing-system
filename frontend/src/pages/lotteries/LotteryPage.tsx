import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, Minus, TriangleAlert } from 'lucide-react'
import {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { isApiError, isOutcomeUnknown } from '@/api/apiError'
import { authQueryKeys, getMe } from '@/api/auth'
import { describeError } from '@/api/errorMessages'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import {
  cancelEntry,
  enterLottery,
  getLottery,
  getMyEntry,
  lotteryQueryKeys,
  type Lottery,
  type LotteryEntry,
} from '@/api/lotteries'
import { paths, reservationPath } from '@/app/paths'
import { toReturnPath, type AuthNavigationState } from '@/auth/navigation'
import { useBillingKeyRecord } from '@/auth/billingKeyRecord'
import { useIsSignedIn } from '@/auth/session'
import { BillingKeyCard } from '@/components/ticketing/BillingKeyCard'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { QuantitySelector } from '@/components/ticketing/QuantitySelector'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
} from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'
import { instantMillis } from '@/lib/instant'
import { readRouteId } from '@/lib/routeId'
import { useNow } from '@/lib/serverClock'
import { cn } from '@/lib/utils'

// 추첨 응모 화면(ISE-121). 추첨 정보는 공개이고, 응모·결과·자동결제 카드는 로그인 뒤에 보인다.
// Note: 공연·회차에서 추첨 번호를 찾는 API가 없어, 운영자가 추첨을 설정하며 받은 이 화면 주소로만 들어온다.

// 응모 폼의 "카드 등록하러 가기"가 초점을 옮길 자동결제 카드 영역
const BILLING_KEY_CARD_ID = 'billing-key'
// 추첨 시각이 지나 결과가 바뀌는 동안(추첨·배정·자동결제·승계) 다시 받는 간격. 서버 스케줄러는 1~10초 주기다
const RESULT_POLL_MILLIS = 10_000
// 화면마다 간격에 더하는 무작위 값의 최대. 시계가 맞는 모든 화면이 추첨 시각에 함께 다시 받기 시작해 10초마다
// 한꺼번에 몰리지 않게 흩뜨린다(재시도 지터, 대기열 응답 간격과 같은 생각)
const RESULT_POLL_JITTER_MILLIS = 3_000
// 추첨 뒤 이만큼 지나면 다시 받기를 멈춘다. 승계 마감(추첨 + 30분)과 당첨 예매의 선점(30분, 한 번 30분 연장)을 덮는다
const RESULT_POLL_LIMIT_MILLIS = 90 * 60_000

function randomPollMillis(): number {
  return RESULT_POLL_MILLIS + Math.floor(Math.random() * RESULT_POLL_JITTER_MILLIS)
}

export function LotteryPage() {
  const lotteryId = readRouteId(useParams().lotteryId)
  if (lotteryId === null) {
    return <MissingLottery />
  }
  return <LotteryScreen key={lotteryId} lotteryId={lotteryId} />
}

function LotteryScreen({ lotteryId }: { lotteryId: number }) {
  const signedIn = useIsSignedIn()
  // 응모 시작·마감·추첨 시각을 넘는 순간 단계와 다시 받기 여부를 다시 정한다
  const now = useNow(1000)
  // Note: 화면을 열 때 한 번 정한다. refetchInterval에서 매번 무작위로 정하면 1초마다 다시 그릴 때 타이머가 새로 시작돼
  // 영영 다시 받지 않는다
  const [pollMillis] = useState(randomPollMillis)
  const lottery = useQuery({
    queryKey: lotteryQueryKeys.info(lotteryId),
    queryFn: () => getLottery(lotteryId),
    // 추첨 시각이 지났는데 아직 추첨 전 상태면 추첨 완료로 바뀔 때까지 다시 받는다
    refetchInterval: (query) => {
      const data = query.state.data
      return data && phaseOf(data, Date.now()) === 'drawing' && !pollExpired(data, Date.now())
        ? pollMillis
        : false
    },
  })

  if (lottery.isPending) {
    return <LotterySkeleton />
  }
  if (lottery.data === undefined) {
    return isApiError(lottery.error, 'NOT_FOUND') ? (
      <MissingLottery />
    ) : (
      <main className="mx-auto w-full max-w-[720px]">
        <h1 className="sr-only">추첨 응모</h1>
        <ErrorNotice error={lottery.error} onRetry={() => void lottery.refetch()} />
      </main>
    )
  }
  return (
    <main className="mx-auto grid w-full max-w-[720px] gap-5">
      <LotteryInfo lottery={lottery.data} phase={phaseOf(lottery.data, now)} />
      {signedIn ? (
        <MyEntrySection lottery={lottery.data} now={now} pollMillis={pollMillis} />
      ) : (
        <SignInPrompt phase={phaseOf(lottery.data, now)} />
      )}
    </main>
  )
}

// beforeEntry 응모 시작 전, entry 응모 중, afterEntry 마감 뒤 추첨 전, drawing 추첨 시각이 지났지만 아직 추첨 전 상태,
// drawn 추첨 완료(배정·승계 중), done 종료
type Phase = 'beforeEntry' | 'entry' | 'afterEntry' | 'drawing' | 'drawn' | 'done'

// Note: 추첨 정보 응답에 서버 시각이 없어 기간은 브라우저 시계로 나눈다. 응모·취소 가능 여부는 서버가 정한다
// (LOTTERY_NOT_OPEN). 응모 시작 전에도 상태는 OPEN이다.
function phaseOf(lottery: Lottery, now: number): Phase {
  if (lottery.status === 'DONE') {
    return 'done'
  }
  if (lottery.status === 'DRAWN') {
    return 'drawn'
  }
  if (lottery.status === 'OPEN' && now < instantMillis(lottery.entryStartAt)) {
    return 'beforeEntry'
  }
  if (lottery.status === 'OPEN' && now < instantMillis(lottery.entryEndAt)) {
    return 'entry'
  }
  return now < instantMillis(lottery.drawAt) ? 'afterEntry' : 'drawing'
}

function pollExpired(lottery: Lottery, now: number): boolean {
  return now >= instantMillis(lottery.drawAt) + RESULT_POLL_LIMIT_MILLIS
}

const PHASE_BADGES: Record<Phase, { variant: 'lottery' | 'upcoming' | 'soldOut'; label: string }> =
  {
    beforeEntry: { variant: 'upcoming', label: '응모 시작 전' },
    entry: { variant: 'lottery', label: '응모 중' },
    afterEntry: { variant: 'soldOut', label: '응모 마감' },
    drawing: { variant: 'soldOut', label: '추첨 중' },
    drawn: { variant: 'soldOut', label: '추첨 완료' },
    done: { variant: 'soldOut', label: '종료' },
  }

function LotteryInfo({ lottery, phase }: { lottery: Lottery; phase: Phase }) {
  const badge = PHASE_BADGES[phase]
  const { rules } = lottery
  return (
    <section aria-labelledby="lottery-title">
      <Card className="gap-4 px-4 py-5 @min-[40rem]:p-6">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <h1 id="lottery-title" className="type-title text-gray-900">
            추첨 응모
          </h1>
          <Badge variant={badge.variant}>{badge.label}</Badge>
        </div>
        <p className="-mt-2 type-body-sm text-gray-500 tabular-nums">
          추첨 번호 {lottery.lotteryId} · 회차 {lottery.scheduleId} · 추첨석 {lottery.seatCount}석
        </p>
        <dl className="grid grid-cols-[auto_1fr] gap-x-5 gap-y-2 type-body-sm">
          <dt className="text-gray-500">응모 기간</dt>
          <dd className="text-gray-900 tabular-nums">
            {formatDateTime(lottery.entryStartAt)} ~ {formatDateTime(lottery.entryEndAt)}
          </dd>
          <dt className="text-gray-500">추첨 시각</dt>
          <dd className="text-gray-900 tabular-nums">{formatDateTime(lottery.drawAt)}</dd>
          <dt className="text-gray-500">한 번에</dt>
          <dd className="text-gray-900">
            {rules.minPerEntry === rules.maxPerEntry
              ? `${rules.maxPerEntry}매`
              : `${rules.minPerEntry}~${rules.maxPerEntry}매`}
          </dd>
        </dl>
        <ul className="grid gap-2 type-body-sm text-gray-700">
          {rules.billingKeyRequired ? <Rule>당첨되면 등록한 카드로 바로 자동결제돼요.</Rule> : null}
          {rules.reentryAllowed ? null : (
            <Rule>응모는 한 번만 할 수 있고, 취소한 뒤에는 다시 응모할 수 없어요.</Rule>
          )}
          {rules.adjacentSeatsGuaranteed ? null : <Rule>연석은 보장하지 않아요.</Rule>}
        </ul>
      </Card>
    </section>
  )
}

function Rule({ children }: { children: ReactNode }) {
  return (
    <li className="flex gap-2 before:text-gray-400 before:content-['•']">
      <span>{children}</span>
    </li>
  )
}

// 응모·취소에 성공하면 누른 버튼이 사라져 초점이 body로 떨어진다. 응모·취소(결과를 모를 때 다시 받은 결과 포함)
// 직후 처음 새로 그린 카드만 한 번, 초점을 잃었을 때 제목으로 초점을 옮긴다. 신호는 ACTED_FOCUS_WINDOW_MILLIS 안에만
// 쓴다. 다시 받기로 결과가 바뀔 때(응모함 → 당첨)는 초점을 건드리지 않는다
const ACTED_FOCUS_WINDOW_MILLIS = 10_000

interface ActionFocus {
  markActed: () => void
  // 응모·취소 뒤 아직 쓰지 않은 신호가 있으면 true를 주고 지운다
  takeActed: () => boolean
}
const ActionFocusContext = createContext<ActionFocus>({
  markActed: () => {},
  takeActed: () => false,
})

function useTitleFocus() {
  const { takeActed } = useContext(ActionFocusContext)
  const ref = useRef<HTMLHeadingElement>(null)
  // 카드가 새로 그려질 때 한 번만 본다. 1초마다 다시 그려도 초점을 다시 옮기지 않는다
  useEffect(() => {
    const acted = takeActed()
    const active = document.activeElement
    if (acted && (active === null || active === document.body)) {
      ref.current?.focus()
    }
  }, [takeActed])
  return ref
}

function MyEntrySection({
  lottery,
  now,
  pollMillis,
}: {
  lottery: Lottery
  now: number
  pollMillis: number
}) {
  const me = useQuery({ queryKey: authQueryKeys.me, queryFn: getMe })
  const entry = useQuery({
    queryKey: lotteryQueryKeys.myEntry(lottery.lotteryId),
    queryFn: () => getMyEntry(lottery.lotteryId),
    refetchInterval: (query) =>
      shouldPollResult(lottery, query.state.data ?? null, Date.now()) ? pollMillis : false,
  })
  const phase = phaseOf(lottery, now)
  const actedAtRef = useRef<number | null>(null)
  const actionFocus = useMemo<ActionFocus>(
    () => ({
      markActed: () => {
        actedAtRef.current = Date.now()
      },
      takeActed: () => {
        const actedAt = actedAtRef.current
        actedAtRef.current = null
        return actedAt !== null && Date.now() - actedAt <= ACTED_FOCUS_WINDOW_MILLIS
      },
    }),
    [],
  )

  let content: ReactNode
  if (entry.isPending) {
    content = <Skeleton className="h-48 w-full" />
  } else if (entry.data === undefined) {
    content = <ErrorNotice error={entry.error} onRetry={() => void entry.refetch()} />
  } else {
    content = (
      <>
        {/* 한 번 받은 결과는 다시 받기가 실패해도 그대로 둔다(예매 결과 화면과 같은 규칙) */}
        {entry.isError ? (
          <ErrorNotice error={entry.error} onRetry={() => void entry.refetch()} />
        ) : null}
        {entry.data === null ? (
          <EntryForm lottery={lottery} phase={phase} userId={me.data?.userId} />
        ) : (
          <EntryResult lottery={lottery} entry={entry.data} phase={phase} />
        )}
      </>
    )
  }

  return (
    <ActionFocusContext value={actionFocus}>
      {content}
      {me.data ? <BillingKeyCard userId={me.data.userId} anchorId={BILLING_KEY_CARD_ID} /> : null}
    </ActionFocusContext>
  )
}

// 추첨 시각이 지나 결과가 바뀔 수 있는 동안만 내 응모를 다시 받는다
function shouldPollResult(lottery: Lottery, entry: LotteryEntry | null, now: number): boolean {
  if (entry === null || pollExpired(lottery, now)) {
    return false
  }
  switch (entry.status) {
    case 'ENTERED':
      return now >= instantMillis(lottery.drawAt)
    // 당첨은 자동결제 결과(PAID·FAILED)를, 예비는 승계(WON)나 승계 마감(LOST)을 기다린다
    case 'WON':
    case 'WAITLISTED':
      return true
    default:
      return false
  }
}

function EntryForm({
  lottery,
  phase,
  userId,
}: {
  lottery: Lottery
  phase: Phase
  userId: number | undefined
}) {
  const queryClient = useQueryClient()
  const { markActed } = useContext(ActionFocusContext)
  const { minPerEntry, maxPerEntry } = lottery.rules
  const [quantity, setQuantity] = useState(minPerEntry)
  // 응모 한 번(매수)에 키 하나. 결과를 모르면 같은 키로 다시 보내 서버가 그 응모의 지금 상태를 주게 한다.
  // Note: 서버의 응모 멱등키는 사용자별이 아니라 전역이고, 같은 키에 다른 매수를 보내면 409다. 그래서 결과를 모르는
  // 동안은 매수를 바꾸지 못하게 한다.
  const keyRef = useRef<{ quantity: number; key: string } | null>(null)
  const enter = useMutation({
    mutationFn: (count: number) => {
      if (keyRef.current?.quantity !== count) {
        keyRef.current = { quantity: count, key: newIdempotencyKey() }
      }
      return enterLottery(lottery.lotteryId, count, keyRef.current.key)
    },
    onSuccess: (entry) => {
      keyRef.current = null
      markActed()
      queryClient.setQueryData(lotteryQueryKeys.myEntry(lottery.lotteryId), entry)
    },
    onError: (error) => {
      if (isOutcomeUnknown(error)) {
        // 응모 조회는 키가 필요 없다. 서버에 응모가 있으면 결과 카드로 바뀐다. 없으면(404) 폼이 남고 같은 키로 다시 보낸다
        markActed()
        void queryClient.invalidateQueries({
          queryKey: lotteryQueryKeys.myEntry(lottery.lotteryId),
        })
        return
      }
      keyRef.current = null
      if (isApiError(error, 'LOTTERY_ALREADY_ENTERED')) {
        // 다른 탭·기기에서 이미 응모했다. 내 응모를 다시 받아 결과를 보인다
        markActed()
        void queryClient.invalidateQueries({
          queryKey: lotteryQueryKeys.myEntry(lottery.lotteryId),
        })
      }
      if (isApiError(error, 'LOTTERY_NOT_OPEN')) {
        void queryClient.invalidateQueries({ queryKey: lotteryQueryKeys.info(lottery.lotteryId) })
      }
    },
  })
  const unknown = enter.isError && isOutcomeUnknown(enter.error)

  // 결과를 모르는 응모는 마감이 지나도 확인할 수 있게 폼을 남긴다. 서버는 같은 키를 기간 검사보다 먼저 확인해
  // 응모가 있었으면 그 상태를 201로 준다. 응모가 없었으면 409(응모 기간 아님)가 와 "응모하지 않았어요"로 바뀐다
  if (phase !== 'beforeEntry' && phase !== 'entry' && !unknown) {
    return (
      <EntryCard title="응모하지 않았어요">
        <p className="type-body-sm text-gray-500">응모 기간이 끝났어요.</p>
      </EntryCard>
    )
  }
  return (
    <EntryCard title="응모하기">
      {enter.isError ? <EntryError error={enter.error} /> : null}
      {phase === 'beforeEntry' ? (
        <Alert variant="info">{formatDateTime(lottery.entryStartAt)}부터 응모할 수 있어요.</Alert>
      ) : null}
      {userId === undefined ? null : <BillingKeyReminder userId={userId} />}
      {maxPerEntry > minPerEntry ? (
        <div className="flex flex-wrap items-center justify-between gap-4">
          <span className="type-body-sm text-gray-700">응모 매수</span>
          <QuantitySelector
            value={quantity}
            max={maxPerEntry}
            onChange={setQuantity}
            disabled={enter.isPending || unknown}
          />
        </div>
      ) : null}
      <Button
        size="lg"
        className="w-full"
        loading={enter.isPending}
        disabled={phase === 'beforeEntry'}
        onClick={() => enter.mutate(quantity)}
      >
        {unknown ? '응모 결과 확인하기' : `${quantity}매 응모하기`}
      </Button>
    </EntryCard>
  )
}

// 이 브라우저에 자동결제 카드 기록이 없을 때만 보인다. 다른 브라우저에서 등록했을 수 있어 "확인해 주세요"로 쓴다
function BillingKeyReminder({ userId }: { userId: number }) {
  const record = useBillingKeyRecord(userId)
  if (record) {
    return null
  }
  const moveToCard = () => {
    const card = document.getElementById(BILLING_KEY_CARD_ID)
    card?.scrollIntoView({ block: 'center' })
    card?.focus({ preventScroll: true })
  }
  return (
    <Alert
      variant="warning"
      action={
        <Button variant="outline" size="sm" onClick={moveToCard}>
          카드 등록하러 가기
        </Button>
      }
    >
      <p className="font-semibold">자동결제 카드를 확인해 주세요</p>
      <p>당첨되면 등록한 카드로 결제돼요. 카드가 없으면 당첨이 다음 순번으로 넘어가요.</p>
    </Alert>
  )
}

function EntryError({ error }: { error: unknown }) {
  if (isOutcomeUnknown(error)) {
    return (
      <Alert variant="warning">
        <p className="font-semibold">응모됐는지 확인하지 못했어요</p>
        <p>다시 누르면 같은 응모로 결과를 확인해요.</p>
      </Alert>
    )
  }
  if (isApiError(error, 'LOTTERY_NOT_OPEN')) {
    return <Alert variant="info">{describeError(error).message}</Alert>
  }
  return <ErrorNotice error={error} />
}

// 서버 failReason별 안내. 결제 거절 사유, 선점 만료, 배정 실패(NO_SEAT·ASSIGN_ERROR·배정 때 받은 오류 코드)가 온다
const FAIL_REASONS: Record<string, { title: string; description: string }> = {
  BILLING_KEY_MISSING: {
    title: '자동결제가 되지 않았어요',
    description: '등록된 자동결제 카드가 없어 당첨이 다음 순번으로 넘어갔어요.',
  },
  CARD_DECLINED: {
    title: '자동결제가 되지 않았어요',
    description: '카드사가 결제를 거절해 당첨이 다음 순번으로 넘어갔어요.',
  },
  INSUFFICIENT_LIMIT: {
    title: '자동결제가 되지 않았어요',
    description: '카드 한도가 부족해 당첨이 다음 순번으로 넘어갔어요.',
  },
  HOLD_EXPIRED: {
    title: '자동결제가 되지 않았어요',
    description:
      '결제 결과를 제때 받지 못해 당첨이 다음 순번으로 넘어갔어요. 결제된 금액이 있으면 환불돼요.',
  },
  NO_SEAT: {
    title: '좌석을 배정하지 못했어요',
    description: '승계 마감까지 배정할 추첨석이 남지 않았어요.',
  },
  PURCHASE_LIMIT_EXCEEDED: {
    title: '좌석을 배정하지 못했어요',
    description: '이 회차에서 살 수 있는 매수를 이미 채워 당첨이 다음 순번으로 넘어갔어요.',
  },
}

const DEFAULT_FAIL = {
  title: '당첨이 다음 순번으로 넘어갔어요',
  description: '좌석 배정이나 자동결제가 되지 않았어요.',
}

function EntryResult({
  lottery,
  entry,
  phase,
}: {
  lottery: Lottery
  entry: LotteryEntry
  phase: Phase
}) {
  switch (entry.status) {
    case 'ENTERED':
      return <EnteredCard lottery={lottery} entry={entry} phase={phase} />
    case 'CANCELED':
      return (
        <EntryCard title="응모를 취소했어요" badge={<Badge variant="soldOut">응모 취소</Badge>}>
          <p className="type-body-sm text-gray-500">취소한 추첨에는 다시 응모할 수 없어요.</p>
        </EntryCard>
      )
    // Note: 당첨 예매는 자동결제가 끝날 때까지 결제 대기(HELD)이고 예매 화면에 [결제하기]가 보인다. 직접 결제하면
    // 자동결제와 겹치므로 결제가 끝난(PAID) 뒤에만 예매로 보낸다.
    case 'WON':
      return (
        <ResultCard
          tone="success"
          title="당첨됐어요"
          description="등록한 카드로 자동결제하고 있어요. 결제가 끝나면 이 화면에서 예매를 볼 수 있어요."
        />
      )
    case 'PAID':
      return (
        <ResultCard
          tone="success"
          title="당첨되고 결제까지 끝났어요"
          description="예매에서 좌석별 티켓 번호를 확인해 주세요."
          action={
            <Button asChild>
              <Link
                to={
                  entry.reservationId === null
                    ? paths.myReservations
                    : reservationPath(entry.reservationId)
                }
              >
                예매 보기
              </Link>
            </Button>
          }
        />
      )
    case 'WAITLISTED':
      return (
        <ResultCard
          tone="warning"
          title="예비자예요"
          rank={entry.drawRank}
          description="앞 순위 당첨자의 결제가 되지 않으면 추첨 순위대로 당첨이 넘어와요. 승계는 추첨 30분 뒤에 끝나요."
        />
      )
    case 'LOST':
      return (
        <ResultCard
          tone="neutral"
          title="아쉽게도 당첨되지 않았어요"
          description="일반 예매 오픈 때 다시 도전해 보세요."
        />
      )
    case 'FAILED': {
      const fail = (entry.failReason && FAIL_REASONS[entry.failReason]) || DEFAULT_FAIL
      return <ResultCard tone="neutral" title={fail.title} description={fail.description} />
    }
  }
}

function EnteredCard({
  lottery,
  entry,
  phase,
}: {
  lottery: Lottery
  entry: LotteryEntry
  phase: Phase
}) {
  const queryClient = useQueryClient()
  const { markActed } = useContext(ActionFocusContext)
  const [confirmOpen, setConfirmOpen] = useState(false)
  const cancelTriggerRef = useRef<HTMLButtonElement>(null)
  const cancel = useMutation({
    mutationFn: () => cancelEntry(lottery.lotteryId),
    onSuccess: (canceled) => {
      markActed()
      queryClient.setQueryData(lotteryQueryKeys.myEntry(lottery.lotteryId), canceled)
      setConfirmOpen(false)
    },
    onError: (error) => {
      if (isOutcomeUnknown(error)) {
        // 내 응모를 다시 받는다. 취소됐으면 이 카드가 "응모를 취소했어요"로 바뀐다. 다시 눌러도 된다(이미 취소면 200)
        markActed()
        void queryClient.invalidateQueries({
          queryKey: lotteryQueryKeys.myEntry(lottery.lotteryId),
        })
        return
      }
      if (isApiError(error, 'LOTTERY_NOT_OPEN')) {
        // 마감이 지났거나 응모 상태가 바뀌었다. 둘 다 다시 받아 지금 상태를 보인다
        void queryClient.invalidateQueries({ queryKey: lotteryQueryKeys.info(lottery.lotteryId) })
        void queryClient.invalidateQueries({
          queryKey: lotteryQueryKeys.myEntry(lottery.lotteryId),
        })
      }
    },
  })
  const waitingResult = phase === 'drawing' || phase === 'drawn'

  return (
    <EntryCard title="응모했어요" badge={<Badge variant="onSale">응모 완료</Badge>}>
      <dl className="grid grid-cols-[auto_1fr] gap-x-5 gap-y-2 type-body-sm">
        <dt className="text-gray-500">응모 매수</dt>
        <dd className="text-gray-900">{entry.quantity}매</dd>
        <dt className="text-gray-500">결과 발표</dt>
        <dd className="text-gray-900 tabular-nums">{formatDateTime(lottery.drawAt)} 이후</dd>
      </dl>
      {waitingResult ? (
        <Alert variant="info">추첨하고 있어요. 결과가 나오면 이 화면이 바뀌어요.</Alert>
      ) : null}
      {phase === 'entry' ? (
        <>
          <p className="type-caption text-gray-500">
            응모 마감 전까지 취소할 수 있어요. 취소하면 다시 응모할 수 없어요.
          </p>
          <div>
            <Button ref={cancelTriggerRef} variant="outline" onClick={() => setConfirmOpen(true)}>
              응모 취소
            </Button>
          </div>
        </>
      ) : null}
      <Dialog
        open={confirmOpen}
        onOpenChange={(open) => {
          if (!cancel.isPending) {
            setConfirmOpen(open)
            cancel.reset()
          }
        }}
      >
        <DialogContent
          showCloseButton={!cancel.isPending}
          onCloseAutoFocus={(event) => {
            // 트리거 없이 연 대화상자라 Radix가 초점을 돌려줄 곳을 모른다. 마감이 지나 [응모 취소]가 사라졌으면
            // 카드 제목으로 간다. 취소에 성공하면 이 카드가 사라지고 새 카드 제목이 초점을 받는다
            event.preventDefault()
            const trigger = cancelTriggerRef.current
            if (trigger?.isConnected) {
              trigger.focus()
            } else {
              document.getElementById('entry-title')?.focus()
            }
          }}
        >
          <DialogTitle className="type-heading text-gray-900">응모를 취소할까요?</DialogTitle>
          <DialogDescription className="type-body-sm text-gray-500">
            취소하면 이 추첨에 다시 응모할 수 없어요.
          </DialogDescription>
          {cancel.isError ? (
            isOutcomeUnknown(cancel.error) ? (
              <Alert variant="warning">
                <p className="font-semibold">취소됐는지 확인하지 못했어요</p>
                <p>다시 누르면 결과를 확인해요.</p>
              </Alert>
            ) : isApiError(cancel.error, 'LOTTERY_NOT_OPEN') ? (
              <Alert variant="info">응모 마감이 지나 취소할 수 없어요.</Alert>
            ) : (
              <ErrorNotice error={cancel.error} />
            )
          ) : null}
          <DialogFooter>
            <Button
              variant="outline"
              disabled={cancel.isPending}
              onClick={() => {
                setConfirmOpen(false)
                cancel.reset()
              }}
            >
              돌아가기
            </Button>
            <Button
              variant="destructive"
              loading={cancel.isPending}
              onClick={() => cancel.mutate()}
            >
              응모 취소
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </EntryCard>
  )
}

function EntryCard({
  title,
  badge,
  children,
}: {
  title: string
  badge?: ReactNode
  children: ReactNode
}) {
  const titleRef = useTitleFocus()
  return (
    <section aria-labelledby="entry-title">
      <Card className="gap-4 px-4 py-5 @min-[40rem]:p-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2
            ref={titleRef}
            id="entry-title"
            tabIndex={-1}
            className="type-heading text-gray-900 outline-none"
          >
            {title}
          </h2>
          {badge}
        </div>
        {children}
      </Card>
    </section>
  )
}

function ResultCard({
  tone,
  title,
  description,
  rank,
  action,
}: {
  tone: 'success' | 'warning' | 'neutral'
  title: string
  description: string
  rank?: number | null
  action?: ReactNode
}) {
  const Icon = tone === 'success' ? Check : tone === 'warning' ? TriangleAlert : Minus
  const titleRef = useTitleFocus()
  return (
    <section aria-labelledby="entry-result-title">
      <Card className="items-center gap-2 px-4 pt-8 pb-6 text-center @min-[40rem]:px-6">
        <span
          aria-hidden="true"
          className={cn(
            'grid size-14 place-items-center rounded-full',
            tone === 'success' && 'bg-success-50 text-success-600',
            tone === 'warning' && 'bg-warning-50 text-warning-700',
            tone === 'neutral' && 'bg-gray-50 text-gray-500',
          )}
        >
          <Icon className="size-7" strokeWidth={tone === 'success' ? 3 : 2} />
        </span>
        <h2
          ref={titleRef}
          id="entry-result-title"
          tabIndex={-1}
          className="mt-2 type-title text-gray-900 outline-none"
        >
          {title}
        </h2>
        {/* Note: 서버 drawRank는 예비 몇 번째가 아니라 전체 응모 중 추첨 순위다(당첨자 수보다 크고 건너뛴 번호가 있다) */}
        {rank ? (
          <p className="grid justify-items-center">
            <span className="type-caption text-gray-500">추첨 순위</span>
            <span className="text-[40px] leading-[48px] font-extrabold text-gray-900 tabular-nums">
              {rank}
              <span className="type-heading text-gray-500">위</span>
            </span>
          </p>
        ) : null}
        <p className="type-body-sm text-gray-500">{description}</p>
        {action ? <div className="mt-2">{action}</div> : null}
      </Card>
    </section>
  )
}

function SignInPrompt({ phase }: { phase: Phase }) {
  const location = useLocation()
  const state: AuthNavigationState = { from: toReturnPath(location) }
  const canEnter = phase === 'beforeEntry' || phase === 'entry'
  return (
    <EntryCard title={canEnter ? '응모하기' : '응모 결과'}>
      <p className="type-body-sm text-gray-500">
        {canEnter
          ? '로그인하면 응모하고 결과를 볼 수 있어요.'
          : '로그인하면 내 응모 결과를 볼 수 있어요.'}
      </p>
      <Button asChild size="lg" className="w-full">
        <Link to={paths.login} state={state}>
          {canEnter ? '로그인하고 응모하기' : '로그인하고 결과 보기'}
        </Link>
      </Button>
    </EntryCard>
  )
}

function MissingLottery() {
  return (
    <main className="mx-auto w-full max-w-[560px]">
      <h1 className="sr-only">추첨 응모</h1>
      <Alert
        variant="warning"
        action={
          <Button asChild variant="outline" size="sm">
            <Link to={paths.home}>공연 목록으로</Link>
          </Button>
        }
      >
        찾는 추첨이 없어요. 받은 링크를 다시 확인해 주세요
      </Alert>
    </main>
  )
}

function LotterySkeleton() {
  return (
    <main className="mx-auto w-full max-w-[720px]">
      <div role="status" className="grid gap-5">
        <span className="sr-only">추첨 정보를 불러오고 있어요</span>
        <Skeleton className="h-64 w-full" />
        <Skeleton className="h-48 w-full" />
      </div>
    </main>
  )
}
