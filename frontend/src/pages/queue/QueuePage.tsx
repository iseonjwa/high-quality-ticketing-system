import { Check, Loader2 } from 'lucide-react'
import { useEffect, useLayoutEffect, useRef, type ReactNode } from 'react'
import { flushSync } from 'react-dom'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import { isApiError } from '@/api/apiError'
import { describeError, type ErrorTone } from '@/api/errorMessages'
import { concertPath, paths, seatsPath } from '@/app/paths'
import { ConcertPoster } from '@/components/ticketing/ConcertPoster'
import { QueuePosition } from '@/components/ticketing/QueuePosition'
import { TicketPerforation } from '@/components/ticketing/TicketPerforation'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { formatDateTime } from '@/lib/format'
import { readRouteId } from '@/lib/routeId'
import { cn } from '@/lib/utils'
import type { QueueAdmissionState } from '@/pages/queue/queueAdmission'
import { readQueueConcert, type QueueConcert } from '@/pages/queue/queueNavigation'
import { useQueueAdmission } from '@/pages/queue/useQueueAdmission'

// 대기열 화면(ISE-114, samples/queue.html). 흐름은 useQueueAdmission이 정하고 이 화면은 단계별로 그리기만 한다.
export function QueuePage() {
  const scheduleId = readRouteId(useParams().scheduleId)
  if (scheduleId === null) {
    return (
      <main className="mx-auto w-full max-w-[560px]">
        <h1 className="sr-only">대기열</h1>
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
  // 회차가 바뀌면 흐름을 처음부터 다시 돈다
  return <QueueScreen key={scheduleId} scheduleId={scheduleId} />
}

function QueueScreen({ scheduleId }: { scheduleId: number }) {
  const admission = useQueueAdmission(scheduleId)
  const locationState: unknown = useLocation().state
  const concert = readQueueConcert(locationState)
  const navigate = useNavigate()
  const cardRef = useRef<HTMLDivElement>(null)

  // [다시 줄 서기]·[다시 시도]는 누르면 사라진다. 초점이 페이지 맨 위로 가지 않게 카드로 옮긴다.
  // 새 내용(줄을 서고 있어요)을 먼저 그린 뒤 옮겨야 화면 낭독기가 방금 본 안내를 다시 읽지 않는다
  function rejoin() {
    flushSync(() => admission.rejoin())
    cardRef.current?.focus()
  }

  // 좌석 화면으로 바꿔 들어간다. 좌석 화면에서 뒤로 가면 대기열이 아니라 공연 상세로 간다.
  // 공연 요약도 함께 넘긴다(좌석 API에도 공연 제목이 없다)
  useEffect(() => {
    if (admission.step === 'admitted') {
      void navigate(seatsPath(scheduleId), { replace: true, state: locationState })
    }
  }, [admission.step, navigate, scheduleId, locationState])

  return (
    <main className="mx-auto grid w-full max-w-[560px] gap-4">
      <h1 className="sr-only">대기열</h1>
      {concert !== null ? <ConcertLine concert={concert} /> : null}
      <Card
        ref={cardRef}
        tabIndex={-1}
        className="gap-6 overflow-visible rounded-[24px] px-5 py-6 text-center outline-none @min-[40rem]:p-8"
      >
        <ConnectionStatus unstable={admission.connectionError !== null} />
        <QueueBody admission={admission} concert={concert} onRejoin={rejoin} />
      </Card>
    </main>
  )
}

function ConcertLine({ concert }: { concert: QueueConcert }) {
  return (
    <div className="flex items-center gap-3 px-1">
      <ConcertPoster concertId={concert.concertId} className="h-[52px] w-10" />
      <div className="min-w-0">
        <p className="type-heading text-gray-900">{concert.title}</p>
        <p className="mt-0.5 type-body-sm text-gray-500 tabular-nums">
          {formatDateTime(concert.startsAt)} · {concert.venueName}
        </p>
      </div>
    </div>
  )
}

function QueueBody({
  admission,
  concert,
  onRejoin,
}: {
  admission: QueueAdmissionState
  concert: QueueConcert | null
  onRejoin: () => void
}) {
  switch (admission.step) {
    case 'joining':
      return (
        <StateMessage tone="pending" title="줄을 서고 있어요" description="잠시만 기다려 주세요." />
      )
    case 'waiting':
      // 토큰 요청이 409 QUEUE_NOT_READY로 순번 조회로 돌아오면 아직 받은 순번이 없을 수 있다
      return admission.waiting === null ? (
        <StateMessage
          tone="pending"
          title="순번을 확인하고 있어요"
          description="잠시만 기다려 주세요."
        />
      ) : admission.waiting.estimatedWaitSeconds <= 1 ? (
        // 다음 입장 묶음 안(예상 대기 1초 이하)이면 순번 숫자 대신 준비 화면을 보인다. 혼자 들어와도 대기 1번을 받아
        // 다음 조회(최소 3초)까지 대기열이 보이던 것을 덜 어색하게 한다. 그 시간 자체는 서버 쪽 개선으로 줄인다(ISE-210)
        <StateMessage
          tone="pending"
          title="곧 입장해요"
          description="좌석 선택 화면을 준비하고 있어요."
        />
      ) : (
        <>
          <div className="grid gap-1 text-left">
            <h2 className="type-title text-gray-900">줄을 서고 있어요</h2>
            <p className="type-body text-gray-500">차례가 되면 좌석 선택 화면으로 바로 넘어가요.</p>
          </div>
          <TicketPerforation className="-mx-5 @min-[40rem]:-mx-8" />
          <QueuePosition
            position={admission.waiting.position}
            estimatedWaitSeconds={admission.waiting.estimatedWaitSeconds}
            initialPosition={admission.waiting.initialPosition}
          />
          {admission.connectionError === null ? <StatusPill /> : null}
          <p className="rounded-md bg-gray-50 px-3.5 py-3 text-left type-body-sm text-gray-600">
            새로고침하거나 창을 닫았다 다시 열어도 자리는 그대로예요.
          </p>
        </>
      )
    case 'admitting':
    case 'admitted':
      return (
        <>
          <StateMessage
            tone="success"
            title="차례가 됐어요"
            description="좌석 선택 화면으로 이동하고 있어요."
          />
          <div aria-hidden="true" className="h-2 rounded-pill bg-primary-600" />
        </>
      )
    case 'expired':
      return (
        <>
          <StateMessage
            tone="warning"
            title="입장 시간이 지나 줄에서 빠졌어요"
            description="다시 줄을 서면 맨 뒤에서 시작해요."
            focus
          />
          <Actions concert={concert}>
            <Button size="lg" className="w-full rounded-full" onClick={onRejoin}>
              다시 줄 서기
            </Button>
          </Actions>
        </>
      )
    case 'notOpen':
      return (
        <>
          <StateMessage
            tone="info"
            title="아직 오픈 전이에요"
            description="오픈 시각이 되면 공연 화면에서 [예매하기]를 다시 눌러 주세요."
            focus
          />
          <Actions concert={concert} primaryBack />
        </>
      )
    case 'failed':
      return <FailedMessage error={admission.error} concert={concert} onRetry={onRejoin} />
  }
}

// 안내 문구는 "원인. 다음 행동" 두 문장이다(design-system.md 1절 문구 규칙). 앞 문장을 제목으로, 뒤를 설명으로 나눈다
function FailedMessage({
  error,
  concert,
  onRetry,
}: {
  error: unknown
  concert: QueueConcert | null
  onRetry: () => void
}) {
  const notice = describeError(error)
  const [title, ...rest] = notice.message.split('. ')
  // 없는 회차는 다시 보내도 같다. 안내대로 목록으로 돌아가는 것을 주 행동으로 둔다
  const missing = isApiError(error, 'NOT_FOUND')
  return (
    <>
      <StateMessage
        tone={notice.tone === 'field' ? 'danger' : notice.tone}
        title={title}
        description={rest.length > 0 ? `${rest.join('. ')}.` : undefined}
        focus
      >
        {notice.traceId !== null ? (
          <p className="type-caption text-gray-500 tabular-nums">문의 번호 {notice.traceId}</p>
        ) : null}
      </StateMessage>
      {missing ? (
        <Actions concert={null} primaryBack />
      ) : (
        <Actions concert={concert}>
          <Button size="lg" className="w-full rounded-full" onClick={onRetry}>
            다시 시도
          </Button>
        </Actions>
      )}
    </>
  )
}

type StateTone = ErrorTone | 'success' | 'pending'

const ICON_TONE: Record<Exclude<StateTone, 'field'>, string> = {
  pending: 'bg-primary-50 text-primary-600',
  success: 'bg-success-50 text-success-600',
  info: 'bg-info-50 text-info-600',
  warning: 'bg-warning-50 text-warning-700',
  danger: 'bg-danger-50 text-danger-600',
}

// 원 아이콘 + 제목 + 설명. 사용자가 무언가 눌러야 하는 단계(focus)면 제목으로 초점을 옮겨 바뀐 내용을 읽게 한다
function StateMessage({
  tone,
  title,
  description,
  focus = false,
  children,
}: {
  tone: Exclude<StateTone, 'field'>
  title: string
  description?: string
  focus?: boolean
  children?: ReactNode
}) {
  const titleRef = useRef<HTMLHeadingElement>(null)
  // Note: 그린 직후 같은 흐름에서 옮긴다(useLayoutEffect). useEffect는 화면에 그린 뒤 따로 돌아서, 제목이 보이는데
  // 초점은 아직 이전 자리인 순간이 생긴다(느린 CI에서 테스트가 그 순간을 잡아 실패했다)
  useLayoutEffect(() => {
    if (focus) {
      titleRef.current?.focus()
    }
  }, [focus])

  const waiting = tone === 'pending' || tone === 'success'
  return (
    <div role={waiting ? 'status' : undefined} className="grid justify-items-center gap-3">
      <span
        aria-hidden="true"
        className={cn(
          'grid size-14 place-items-center rounded-full text-[26px] font-bold',
          ICON_TONE[tone],
        )}
      >
        {tone === 'pending' ? (
          <Loader2 className="size-[26px] motion-safe:animate-spin" strokeWidth={2.5} />
        ) : tone === 'success' ? (
          <Check className="size-[26px]" strokeWidth={3} />
        ) : tone === 'info' ? (
          'i'
        ) : (
          '!'
        )}
      </span>
      <h2
        ref={titleRef}
        tabIndex={focus ? -1 : undefined}
        className="type-title text-gray-900 outline-none"
      >
        {title}
      </h2>
      {description !== undefined ? (
        <p className="max-w-[400px] type-body text-gray-500">{description}</p>
      ) : null}
      {children}
    </div>
  )
}

// 첫 버튼(다시 줄 서기·다시 시도)이 주 행동이고, 공연으로 돌아가는 길은 보조로 둔다. 오픈 전이면 돌아가기가 주 행동이다
function Actions({
  concert,
  primaryBack = false,
  children,
}: {
  concert: QueueConcert | null
  primaryBack?: boolean
  children?: ReactNode
}) {
  const back =
    concert !== null
      ? { to: concertPath(concert.concertId), label: '공연 정보로 돌아가기' }
      : { to: paths.home, label: '공연 목록으로' }
  return (
    <div className="mt-2 grid gap-2">
      {children}
      <Button
        asChild
        variant={primaryBack ? 'default' : 'ghost'}
        size={primaryBack ? 'lg' : 'default'}
        className="w-full rounded-full"
      >
        <Link to={back.to}>{back.label}</Link>
      </Button>
    </div>
  )
}

// 자동 재시도 중임을 알리는 영역. 처음부터 화면에 두고 글자만 바꾼다. 내용을 담은 채 새로 붙는 알림 영역은
// 화면 낭독기마다 읽는 여부가 달라서다. 안정적일 때는 비워 두고 보이지 않게 한다
function ConnectionStatus({ unstable }: { unstable: boolean }) {
  return (
    <div role="status" className={unstable ? 'grid' : 'sr-only'}>
      {unstable ? <StatusPill unstable /> : null}
    </div>
  )
}

// 지금 자동으로 확인하고 있는지 보인다. 연결이 불안정하면(자동 재시도 중) 경고 색으로 바꾼다.
// 사용자가 할 일이 없어서 알림 상자를 띄우지 않고 이 줄만 바꾼다(samples/queue.html)
function StatusPill({ unstable = false }: { unstable?: boolean }) {
  return (
    <p
      className={cn(
        'inline-flex items-center gap-2 self-center justify-self-center rounded-pill px-3 py-1 text-left text-[13px] leading-5',
        unstable ? 'bg-warning-50 text-warning-700' : 'bg-gray-50 text-gray-500',
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          'size-2 shrink-0 rounded-full motion-safe:animate-pulse',
          unstable ? 'bg-warning-500' : 'bg-success-600',
        )}
      />
      {unstable ? '연결이 잠시 불안정해요. 곧 다시 시도할게요' : '순번을 자동으로 확인하고 있어요'}
    </p>
  )
}
