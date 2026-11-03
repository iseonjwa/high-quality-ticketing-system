import { Check, Copy, ExternalLink, PanelRightClose, PanelRightOpen, Trash2, X } from 'lucide-react'
import {
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  useSyncExternalStore,
  type KeyboardEvent,
  type PointerEvent,
} from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import {
  DEMO_PANEL_DEFAULT_WIDTH,
  disableDemoMode,
  setDemoPanelCollapsed,
  setDemoPanelWidth,
  useDemoPanelCollapsed,
  useDemoPanelWidth,
} from '@/demo/demoMode'
import { requestLogDashboardUrl } from '@/demo/grafana'
import { clampPanelWidth, PANEL_MIN_WIDTH, PANEL_VISIBLE_MIN_VIEWPORT } from '@/demo/panelWidth'
import {
  clearRequestLog,
  REQUEST_LOG_LIMIT,
  useRequestLog,
  type RequestLogEntry,
} from '@/demo/requestLog'
import { cn } from '@/lib/utils'

const COLLAPSED_WIDTH = 44
const KEYBOARD_STEP = 16
// 앱 화면이 패널만큼 비켜 서고(AppLayout) 토스트가 남은 화면 가운데에 뜨도록(index.css) 쓰는 변수
const PANEL_WIDTH_VARIABLE = '--demo-panel-width'

const PANEL_LABEL = '요청 로그 (시연 모드)'

// Note: 링크마다 새 탭(_blank)을 열면 나란히 띄운 앱 창이 가려진다. 이름 붙인 창은 처음 한 번만 새 탭을 열고
// 다음부터 그 탭을 다시 쓴다. 발표자가 그 탭을 옆 창으로 옮겨 두면 앱 창은 그대로다.
// noopener를 붙이면 이름으로 다시 찾지 못해서 붙이지 않는다. 여는 곳은 팀이 띄운 Grafana(SSH 터널)다
const GRAFANA_WINDOW = 'iseonjwa-grafana'

interface DragState {
  startX: number
  startWidth: number
  width: number
}

// 시연 모드에서 화면 오른쪽을 차지하는 요청 로그. 왼쪽 경계를 끌거나 화살표 키로 너비를 바꾼다.
// 발표 때는 브라우저와 Grafana를 나란히 띄우므로 앱 화면과 겹치지 않게 옆에 붙인다(observability.md 5절).
// 휴대폰 너비에서는 보이지 않는다. 기록은 그대로 쌓여서 창을 넓히면 다시 보인다.
export function RequestLogPanel() {
  const entries = useRequestLog()
  const collapsed = useDemoPanelCollapsed()
  const storedWidth = useDemoPanelWidth()
  const viewportWidth = useViewportWidth()
  // 경계를 끄는 동안의 너비. 손을 떼면 저장값으로 돌아간다
  const [dragWidth, setDragWidth] = useState<number | null>(null)
  const panelId = useId()
  const toggleButtonRef = useRef<HTMLButtonElement>(null)
  const focusToggleAfterChange = useRef(false)

  const width = collapsed
    ? COLLAPSED_WIDTH
    : clampPanelWidth(dragWidth ?? storedWidth, viewportWidth)
  const maxWidth = clampPanelWidth(Number.POSITIVE_INFINITY, viewportWidth)
  const dragging = dragWidth !== null
  const hidden = viewportWidth < PANEL_VISIBLE_MIN_VIEWPORT

  // 패널 너비와 앱 화면 여백이 한 프레임이라도 어긋나 보이지 않게 그리기 전에 바꾼다
  useLayoutEffect(() => {
    if (hidden) {
      return
    }
    const root = document.documentElement
    root.style.setProperty(PANEL_WIDTH_VARIABLE, `${width}px`)
    return () => {
      root.style.removeProperty(PANEL_WIDTH_VARIABLE)
    }
  }, [width, hidden])

  // 끄는 동안 포인터가 경계 밖으로 나가도 커서와 글자 선택 막기를 유지한다
  useEffect(() => {
    if (!dragging) {
      return
    }
    const root = document.documentElement
    root.style.cursor = 'col-resize'
    root.style.userSelect = 'none'
    return () => {
      root.style.cursor = ''
      root.style.userSelect = ''
    }
  }, [dragging])

  // 접기·펼치기 버튼은 서로 다른 자리에 있어 누른 버튼이 사라진다. 초점을 새 버튼으로 옮긴다
  useEffect(() => {
    if (focusToggleAfterChange.current) {
      focusToggleAfterChange.current = false
      toggleButtonRef.current?.focus()
    }
  }, [collapsed])

  function changeCollapsed(next: boolean) {
    focusToggleAfterChange.current = true
    setDemoPanelCollapsed(next)
  }

  function turnOff() {
    disableDemoMode()
    toast('시연 모드를 껐어요. 주소 끝에 ?demo=on을 붙이면 다시 켜져요')
  }

  if (hidden) {
    return null
  }

  if (collapsed) {
    return (
      <aside
        aria-label={PANEL_LABEL}
        className="fixed inset-y-0 right-0 z-20 flex flex-col items-center gap-3 border-l border-gray-200 bg-gray-25 py-3.5"
        style={{ width }}
      >
        <Button
          ref={toggleButtonRef}
          variant="ghost"
          size="icon-sm"
          aria-label="요청 로그 펼치기"
          aria-expanded={false}
          onClick={() => changeCollapsed(false)}
        >
          <PanelRightOpen />
        </Button>
        <span className="type-caption text-gray-500 tabular-nums">{entries.length}</span>
        <span className="type-caption font-semibold text-gray-600 [writing-mode:vertical-rl]">
          요청 로그
        </span>
      </aside>
    )
  }

  return (
    <aside
      id={panelId}
      aria-label={PANEL_LABEL}
      className="fixed inset-y-0 right-0 z-20 flex flex-col border-l border-gray-200 bg-gray-25"
      style={{ width }}
    >
      <PanelResizeHandle
        panelId={panelId}
        width={width}
        maxWidth={maxWidth}
        viewportWidth={viewportWidth}
        dragging={dragging}
        onPreview={setDragWidth}
        onCollapse={() => changeCollapsed(true)}
      />

      {/* 앱 헤더와 높이를 맞춰 아래 경계선이 한 줄로 이어지게 한다 */}
      <div className="flex h-16 shrink-0 items-center gap-2 border-b border-gray-100 bg-gray-0 pr-2 pl-4">
        <h2 className="type-body-sm font-semibold text-gray-900">요청 로그</h2>
        <span className="rounded-sm bg-primary-50 px-1.5 type-caption text-primary-700">시연</span>
        <span className="type-caption text-gray-500 tabular-nums">
          {entries.length >= REQUEST_LOG_LIMIT
            ? `최근 ${REQUEST_LOG_LIMIT}건`
            : `${entries.length}건`}
        </span>
        <div className="ml-auto flex items-center">
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label="기록 비우기"
            title="기록 비우기"
            disabled={entries.length === 0}
            onClick={clearRequestLog}
          >
            <Trash2 />
          </Button>
          <Button
            ref={toggleButtonRef}
            variant="ghost"
            size="icon-sm"
            aria-label="요청 로그 접기"
            title="접기"
            aria-expanded={true}
            aria-controls={panelId}
            onClick={() => changeCollapsed(true)}
          >
            <PanelRightClose />
          </Button>
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label="시연 모드 끄기"
            title="시연 모드 끄기"
            onClick={turnOff}
          >
            <X />
          </Button>
        </div>
      </div>

      <p className="shrink-0 px-4 pt-3 pb-2 type-caption text-gray-500">
        traceId를 누르면 Grafana 탭에 그 요청의 로그가 열려요. 그 탭을 옆 창에 두면 거기서 바뀌어요
      </p>

      {entries.length === 0 ? (
        <p className="px-4 py-6 type-body-sm text-gray-500">아직 보낸 요청이 없어요</p>
      ) : (
        <ol className="min-h-0 flex-1 space-y-2 overflow-y-auto px-3 pb-3">
          {entries.map((entry, index) => (
            <RequestLogItem key={entry.id} entry={entry} latest={index === 0} />
          ))}
        </ol>
      )}
    </aside>
  )
}

interface PanelResizeHandleProps {
  panelId: string
  width: number
  maxWidth: number
  viewportWidth: number
  dragging: boolean
  // 끄는 동안의 너비를 알린다. 끝나면 null
  onPreview: (width: number | null) => void
  onCollapse: () => void
}

// 패널 왼쪽 경계(WAI-ARIA 창 분할 막대). 값은 패널 너비이고, 왼쪽 화살표가 경계를 왼쪽으로 옮겨 패널을 넓힌다
function PanelResizeHandle({
  panelId,
  width,
  maxWidth,
  viewportWidth,
  dragging,
  onPreview,
  onCollapse,
}: PanelResizeHandleProps) {
  const drag = useRef<DragState | null>(null)

  // 끄는 중에 경계가 사라지면(휴대폰 너비로 줄이거나 Enter로 접음) 손 뗌 이벤트를 받지 못한다.
  // 끌던 너비는 저장하지 않고 버려서 커서·글자 선택 막기와 임시 너비가 남지 않게 한다
  useEffect(() => {
    const state = drag
    return () => {
      if (state.current !== null) {
        state.current = null
        onPreview(null)
      }
    }
  }, [onPreview])

  function handlePointerDown(event: PointerEvent<HTMLDivElement>) {
    if (event.button !== 0) {
      return
    }
    event.preventDefault()
    event.currentTarget.setPointerCapture(event.pointerId)
    drag.current = { startX: event.clientX, startWidth: width, width }
    onPreview(width)
  }

  // 누른 자리에서 왼쪽으로 옮긴 만큼 넓힌다. 경계의 어느 점을 잡았든 잡은 자리가 포인터를 따라간다
  function handlePointerMove(event: PointerEvent<HTMLDivElement>) {
    const state = drag.current
    if (state === null) {
      return
    }
    state.width = clampPanelWidth(state.startWidth + state.startX - event.clientX, viewportWidth)
    onPreview(state.width)
  }

  // 끄는 동안은 저장하지 않고 손을 뗄 때 한 번 저장한다(sessionStorage 쓰기를 줄인다).
  // Note: 마지막 너비는 ref에서 읽는다. pointermove 갱신이 그려지기 전에 pointerup이 올 수 있다
  function finishDrag() {
    const state = drag.current
    if (state === null) {
      return
    }
    drag.current = null
    setDemoPanelWidth(state.width)
    onPreview(null)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Enter') {
      event.preventDefault()
      onCollapse()
      return
    }
    const next = {
      ArrowLeft: width + KEYBOARD_STEP,
      ArrowRight: width - KEYBOARD_STEP,
      Home: PANEL_MIN_WIDTH,
      End: maxWidth,
    }[event.key]
    if (next === undefined) {
      return
    }
    event.preventDefault()
    setDemoPanelWidth(clampPanelWidth(next, viewportWidth))
  }

  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label="요청 로그 너비"
      aria-controls={panelId}
      aria-valuenow={width}
      aria-valuemin={PANEL_MIN_WIDTH}
      aria-valuemax={maxWidth}
      tabIndex={0}
      title="끌어서 너비를 바꿔요. 두 번 누르면 기본 너비로 돌아가요"
      className="group absolute inset-y-0 left-0 z-10 w-3 -translate-x-1/2 cursor-col-resize touch-none outline-none"
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={finishDrag}
      onPointerCancel={finishDrag}
      onLostPointerCapture={finishDrag}
      onDoubleClick={() => setDemoPanelWidth(DEMO_PANEL_DEFAULT_WIDTH)}
      onKeyDown={handleKeyDown}
    >
      <span
        aria-hidden="true"
        className={cn(
          'absolute inset-y-0 left-1/2 w-0.5 -translate-x-1/2 transition-colors duration-(--dur-fast)',
          dragging
            ? 'bg-primary-500'
            : 'bg-transparent group-hover:bg-primary-300 group-focus-visible:bg-primary-500',
        )}
      />
    </div>
  )
}

function RequestLogItem({ entry, latest }: { entry: RequestLogEntry; latest: boolean }) {
  const dashboardUrl =
    entry.traceId === null ? null : requestLogDashboardUrl(entry.traceId, entry.startedAt)

  return (
    <li
      className={cn(
        'rounded-md border bg-gray-0 px-3 py-2.5 motion-safe:animate-in motion-safe:fade-in',
        latest ? 'border-primary-300' : 'border-gray-100',
      )}
    >
      <div className="flex items-center gap-2 type-caption">
        <time
          dateTime={new Date(entry.startedAt).toISOString()}
          className="text-gray-500 tabular-nums"
        >
          {formatClock(entry.startedAt)}
        </time>
        <span className="font-mono font-semibold text-gray-700">{entry.method}</span>
        <StatusChip status={entry.status} />
        <span className="ml-auto text-gray-500 tabular-nums">{entry.durationMs}ms</span>
      </div>
      <p className="mt-1 font-mono text-[13px] leading-5 break-all text-gray-900">{entry.path}</p>
      <div className="mt-1.5 flex min-h-8 items-center gap-1">
        {entry.traceId === null ? (
          <span className="type-caption text-gray-400">traceId 없음</span>
        ) : dashboardUrl === null ? (
          <code className="min-w-0 font-mono text-[12px] leading-4 break-all text-gray-700">
            {entry.traceId}
          </code>
        ) : (
          <a
            href={dashboardUrl}
            target={GRAFANA_WINDOW}
            className="inline-flex min-w-0 items-center gap-1 rounded-sm font-mono text-[12px] leading-4 text-primary-700 outline-none hover:underline focus-visible:ring-2 focus-visible:ring-ring"
          >
            <span className="break-all">{entry.traceId}</span>
            <ExternalLink aria-hidden="true" className="size-3 shrink-0" />
            <span className="sr-only">, Grafana 요청 로그 열기</span>
          </a>
        )}
        {entry.traceId !== null && <CopyButton value={entry.traceId} />}
      </div>
    </li>
  )
}

function StatusChip({ status }: { status: number }) {
  const tone =
    status === 0 || status >= 500
      ? 'bg-danger-50 text-danger-600'
      : status >= 400
        ? 'bg-warning-50 text-warning-700'
        : 'bg-success-50 text-success-600'
  return (
    <span className={cn('rounded-sm px-1.5 font-mono font-semibold tabular-nums', tone)}>
      {status === 0 ? '응답 없음' : status}
    </span>
  )
}

const COPIED_FEEDBACK_MS = 1500

// Note: 브라우저는 보안 출처(https·localhost)에서만 navigator.clipboard를 준다. 배포 화면을 http로 열면
// 복사 버튼을 숨기고 traceId 링크만 남긴다.
function CopyButton({ value }: { value: string }) {
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (!copied) {
      return
    }
    const timer = window.setTimeout(() => setCopied(false), COPIED_FEEDBACK_MS)
    return () => window.clearTimeout(timer)
  }, [copied])

  if (navigator.clipboard === undefined) {
    return null
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
    } catch {
      toast.error('traceId를 복사하지 못했어요')
    }
  }

  return (
    <Button
      variant="ghost"
      size="icon-sm"
      className="ml-auto size-8 text-gray-500"
      aria-label={copied ? 'traceId 복사함' : 'traceId 복사'}
      title="traceId 복사"
      onClick={() => void copy()}
    >
      {copied ? <Check className="text-success-600" /> : <Copy />}
    </Button>
  )
}

// Grafana도 같은 브라우저 시계로 시각을 보여 주므로 둘을 맞대 볼 수 있게 초까지 보인다
const clockFormat = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
})

function formatClock(epochMillis: number): string {
  return clockFormat.format(epochMillis)
}

// 스크롤 막대를 뺀 화면 너비. 창 크기가 바뀌면 패널 너비 상한도 다시 계산한다
function subscribeViewport(onChange: () => void): () => void {
  window.addEventListener('resize', onChange)
  return () => window.removeEventListener('resize', onChange)
}

function readViewportWidth(): number {
  return document.documentElement.clientWidth
}

function useViewportWidth(): number {
  return useSyncExternalStore(subscribeViewport, readViewportWidth)
}
