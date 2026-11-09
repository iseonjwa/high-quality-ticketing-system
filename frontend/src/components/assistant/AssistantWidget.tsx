import { ArrowUp, MessageCircleQuestion, Square, X } from 'lucide-react'
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
  type ReactNode,
  type RefObject,
} from 'react'
import { Link, useLocation } from 'react-router'
import { isApiError } from '@/api/apiError'
import { QUESTION_MAX_LENGTH, type AssistantSource } from '@/api/assistant'
import { useBookingStep } from '@/app/bookingStep'
import { paths } from '@/app/paths'
import { isAuthPath, toReturnPath, type AuthNavigationState } from '@/auth/navigation'
import { useIsSignedIn } from '@/auth/session'
import {
  AssistantWidgetContext,
  useAssistantWidget,
  type AssistantWidgetControls,
} from '@/components/assistant/assistantWidgetContext'
import {
  failureTitle,
  useAssistantChat,
  type AssistantChat,
  type ChatTurn,
} from '@/components/assistant/useAssistantChat'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

// 예매 문의(SA F8, ISE-126). 화면 오른쪽 아래 버튼으로 여닫는 위젯이다(2026-11-09 사용자가 /help 페이지 대신 고름,
// docs/ai-judgments/junhyun/2026-11-09-assistant-floating-widget.md). 규칙은 docs/design/assistant/README.md 7절이다.
// 대화는 위젯이 살아 있는 동안(이 탭) 화면을 옮겨도 남고, 저장하지 않는다.

const EXAMPLE_QUESTIONS = [
  '예매 오픈은 언제 하나요?',
  '한 사람이 몇 장까지 살 수 있나요?',
  '결제가 늦게 되면 환불되나요?',
  '대기열 순번은 어떻게 정해지나요?',
]

const FALLBACK_NOTICE = {
  LLM_DISABLED: 'AI 답변이 꺼져 있어 관련 안내만 보여 드려요.',
  LLM_UNAVAILABLE: 'AI 답변이 잠시 어려워 관련 안내만 보여 드려요.',
} as const

// 시연 모드 요청 로그 패널(오른쪽 고정)만큼 비켜 둔다
const CORNER = 'right-[calc(var(--demo-panel-width,0px)+1.25rem)] bottom-5'

// 위젯 안쪽 상태. 레이아웃 바깥(경로 요소)의 Provider가 들고, 레이아웃 안의 AssistantWidget이 그린다
interface WidgetState {
  isOpen: boolean
  open: () => void
  close: () => void
  chat: AssistantChat
  signedIn: boolean
}

const WidgetStateContext = createContext<WidgetState | null>(null)

// 대화와 열림 상태를 들고 위젯을 두는 화면인지 정한다. 예매 흐름(대기열~완료)은 지금 단계에 집중하게, 로그인·가입과
// 운영자 화면은 쓰임이 달라 두지 않는다(7절). 레이아웃의 헤더 버튼과 레이아웃 안의 위젯이 같은 상태를 쓰도록
// 레이아웃 바깥(경로 요소)에서 든다
export function AssistantWidgetProvider({ children }: { children: ReactNode }) {
  const bookingStep = useBookingStep()
  const location = useLocation()
  const available =
    bookingStep === null &&
    !isAuthPath(location.pathname) &&
    !location.pathname.startsWith(paths.admin)
  const [isOpen, setOpen] = useState(false)
  const chat = useAssistantChat()
  const signedIn = useIsSignedIn()
  // 닫은 뒤 초점을 돌려줄 곳(연 버튼). 화면을 옮겨 사라졌으면 여는 버튼으로 간다
  const returnFocusRef = useRef<HTMLElement | null>(null)
  const restoreFocusRef = useRef(false)

  // trigger: 누른 버튼. Safari·Firefox(mac)는 마우스로 누른 버튼에 초점을 주지 않아 activeElement로는 알 수 없다
  const open = useCallback((trigger?: HTMLElement | null) => {
    // 이미 열려 있으면 창 안으로 초점만 옮긴다. 돌려줄 곳은 처음 연 버튼 그대로 둔다
    const panel = document.querySelector<HTMLElement>('[data-slot="assistant-panel"]')
    if (panel !== null) {
      focusPanelStart(panel)
      return
    }
    returnFocusRef.current =
      trigger ?? (document.activeElement instanceof HTMLElement ? document.activeElement : null)
    setOpen(true)
  }, [])

  const close = useCallback(() => {
    restoreFocusRef.current = true
    setOpen(false)
  }, [])

  // 위젯을 두지 않는 화면으로 가면 패널을 닫는다. 받던 답은 끊는다(아래 effect)
  if (!available && isOpen) {
    setOpen(false)
  }

  useEffect(() => {
    if (!isOpen && restoreFocusRef.current) {
      restoreFocusRef.current = false
      const target = returnFocusRef.current
      ;(target?.isConnected
        ? target
        : document.querySelector<HTMLElement>('[data-slot="assistant-launcher"]')
      )?.focus()
    }
  }, [isOpen])

  const { stop, reset } = chat
  useEffect(() => {
    if (!available) {
      stop()
    }
  }, [available, stop])

  // 로그아웃하면 앞 사용자의 대화를 지운다
  useEffect(() => {
    if (!signedIn) {
      reset()
    }
  }, [signedIn, reset])

  const controls = useMemo<AssistantWidgetControls>(() => ({ available, open }), [available, open])
  const state = useMemo<WidgetState>(
    () => ({ isOpen, open, close, chat, signedIn }),
    [isOpen, open, close, chat, signedIn],
  )

  return (
    <AssistantWidgetContext value={controls}>
      <WidgetStateContext value={state}>{children}</WidgetStateContext>
    </AssistantWidgetContext>
  )
}

// 오른쪽 아래 버튼과 대화 창. 레이아웃의 @container 안에 두어 좁은 앱 화면(40rem 미만)에서 화면 전체를 덮는다
export function AssistantWidget() {
  const state = useContext(WidgetStateContext)
  const { available } = useAssistantWidget()
  if (state === null || !available) {
    return null
  }
  return state.isOpen ? (
    <AssistantPanel chat={state.chat} signedIn={state.signedIn} onClose={state.close} />
  ) : (
    <Launcher busy={state.chat.streaming} onClick={state.open} />
  )
}

// 창 안 첫 입력 자리(질문 칸, 로그인 전이면 닫기 버튼)
function focusPanelStart(panel: HTMLElement) {
  ;(
    panel.querySelector<HTMLElement>('textarea') ?? panel.querySelector<HTMLElement>('button')
  )?.focus()
}

function Launcher({ busy, onClick }: { busy: boolean; onClick: (trigger: HTMLElement) => void }) {
  return (
    <button
      data-slot="assistant-launcher"
      type="button"
      onClick={(event) => onClick(event.currentTarget)}
      aria-haspopup="dialog"
      // 헤더·사이트 아래의 [예매 문의]와 구분되게 이름을 따로 준다
      aria-label="예매 문의 열기"
      className={cn(
        'fixed z-40 inline-flex h-12 items-center gap-2 rounded-full bg-gray-900 pr-5 pl-4 text-gray-0 shadow-lg outline-none transition-colors duration-(--dur-fast) hover:bg-gray-800 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring',
        '@max-[40rem]:size-12 @max-[40rem]:justify-center @max-[40rem]:p-0',
        CORNER,
      )}
    >
      <MessageCircleQuestion aria-hidden="true" className="size-5 shrink-0" />
      <span className="type-body-sm font-semibold @max-[40rem]:sr-only">예매 문의</span>
      {/* 닫아 둔 동안에도 답을 받는 중이면 점으로 알린다(꾸밈, 다시 열면 답이 보인다) */}
      {busy ? (
        <span
          aria-hidden="true"
          className="absolute top-1 right-1 size-2.5 rounded-full bg-primary-500 ring-2 ring-gray-900 motion-safe:animate-pulse"
        />
      ) : null}
    </button>
  )
}

function AssistantPanel({
  chat,
  signedIn,
  onClose,
}: {
  chat: AssistantChat
  signedIn: boolean
  onClose: () => void
}) {
  const titleId = useId()
  const panelRef = useRef<HTMLElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const scrollRef = useRef<HTMLDivElement>(null)
  const fullScreen = useCoversScreen(panelRef)
  // 사용자가 위로 올려 읽는 중이면 새 글자가 와도 끌어내리지 않는다
  const stickToBottomRef = useRef(true)

  useEffect(() => {
    ;(textareaRef.current ?? closeRef.current)?.focus()
  }, [])

  useEffect(() => {
    const element = scrollRef.current
    if (element && stickToBottomRef.current) {
      element.scrollTop = element.scrollHeight
    }
  }, [chat.turns])

  const ask = (question: string) => {
    stickToBottomRef.current = true
    chat.ask(question)
  }

  return (
    // Note: 넓은 화면에서는 페이지를 함께 쓰는 비모달 창이라 초점을 가두지 않는다. 좁은 앱 화면(40rem 미만)에서는 앱 화면
    // 전체를 덮으므로 그동안만 모달로 둔다(aria-modal, 초점 가두기, 뒤 페이지 스크롤 잠금). 가려진 페이지로 초점과
    // 스크롤이 새 나갔다(셀프 리뷰). Esc와 닫기 버튼으로 닫고, 닫으면 연 곳으로 초점을 돌려준다.
    // 시연 모드 요청 로그 패널은 덮지 않게 오른쪽을 그 너비만큼 비운다
    <section
      ref={panelRef}
      data-slot="assistant-panel"
      role="dialog"
      aria-modal={fullScreen ? true : undefined}
      aria-labelledby={titleId}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.stopPropagation()
          onClose()
        } else if (event.key === 'Tab' && fullScreen) {
          trapFocus(event.currentTarget, event)
        }
      }}
      className={cn(
        'fixed z-40 flex h-[min(640px,calc(100dvh-2.5rem))] w-[400px] flex-col overflow-hidden rounded-[22px] border border-gray-100 bg-gray-0 shadow-lg',
        CORNER,
        '@max-[40rem]:inset-y-0 @max-[40rem]:right-[var(--demo-panel-width,0px)] @max-[40rem]:left-0 @max-[40rem]:h-dvh @max-[40rem]:w-auto @max-[40rem]:rounded-none @max-[40rem]:border-0',
      )}
    >
      <header className="flex items-center gap-3 border-b border-gray-100 px-5 py-3.5">
        <span
          aria-hidden="true"
          className="grid size-9 shrink-0 place-items-center rounded-full bg-primary-50 text-primary-600"
        >
          <MessageCircleQuestion className="size-[18px]" />
        </span>
        <div className="grid min-w-0 flex-1">
          <h2 id={titleId} className="type-body font-semibold text-gray-900">
            예매 문의
          </h2>
          <p className="truncate type-caption text-gray-500">예매 규정을 바탕으로 답해요</p>
        </div>
        <Button
          ref={closeRef}
          variant="ghost"
          size="icon-sm"
          className="rounded-full text-gray-500"
          aria-label="예매 문의 닫기"
          onClick={onClose}
        >
          <X />
        </Button>
      </header>
      <div
        ref={scrollRef}
        onScroll={(event) => {
          const element = event.currentTarget
          stickToBottomRef.current =
            element.scrollHeight - element.scrollTop - element.clientHeight < 48
        }}
        className="flex-1 overflow-y-auto overscroll-contain px-5 py-4"
      >
        {!signedIn ? (
          <SignInPrompt />
        ) : chat.turns.length === 0 ? (
          <Intro onPick={ask} />
        ) : (
          <ol aria-label="대화" className="grid gap-5">
            {chat.turns.map((turn) => (
              <li
                key={turn.id}
                aria-busy={turn.status === 'streaming' ? true : undefined}
                className="grid gap-2"
              >
                <p className="ml-auto max-w-[85%] rounded-[18px] rounded-br-[6px] bg-primary-600 px-4 py-2.5 type-body-sm whitespace-pre-line text-gray-0">
                  <span className="sr-only">질문: </span>
                  {turn.question}
                </p>
                <AnswerBubble
                  turn={turn}
                  retryDisabled={chat.streaming}
                  onRetry={() => ask(turn.question)}
                />
              </li>
            ))}
          </ol>
        )}
      </div>
      {/* 토큰마다 읽지 않게 답이 끝났을 때(또는 실패했을 때) 한 번만 알린다(2-1절) */}
      <p role="status" className="sr-only">
        {chat.announcement}
      </p>
      {signedIn ? <Composer ref={textareaRef} chat={chat} onAsk={ask} /> : null}
    </section>
  )
}

// 창이 앱 화면 왼쪽 끝까지 덮는지(좁은 앱 화면). 창 크기는 @container 질의로 정해지므로 그린 결과를 잰다
function useCoversScreen(ref: RefObject<HTMLElement | null>): boolean {
  const [covers, setCovers] = useState(false)
  useLayoutEffect(() => {
    const element = ref.current
    if (element === null) {
      return
    }
    const measure = () => {
      const rect = element.getBoundingClientRect()
      setCovers(rect.width > 0 && rect.left <= 0)
    }
    measure()
    if (typeof ResizeObserver === 'undefined') {
      return
    }
    const observer = new ResizeObserver(measure)
    observer.observe(document.documentElement)
    observer.observe(element)
    return () => observer.disconnect()
  }, [ref])

  useEffect(() => {
    if (!covers) {
      return
    }
    const root = document.documentElement
    const previous = root.style.overflow
    root.style.overflow = 'hidden'
    return () => {
      root.style.overflow = previous
    }
  }, [covers])

  return covers
}

const FOCUSABLE =
  'button:not([disabled]), a[href], textarea:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'

// 창 안에서 Tab이 돌게 한다(끝에서 처음으로, 처음에서 끝으로)
function trapFocus(panel: HTMLElement, event: KeyboardEvent<HTMLElement>) {
  const items = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE))
  const first = items[0]
  const last = items[items.length - 1]
  if (first === undefined || last === undefined) {
    return
  }
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function SignInPrompt() {
  const location = useLocation()
  const state: AuthNavigationState = { from: toReturnPath(location) }
  return (
    <div className="grid gap-4 py-6 text-center">
      <p className="type-body-sm text-gray-700">
        로그인하면 오픈 시각, 매수 제한, 결제·환불, 대기열 같은 예매 규정을 물어볼 수 있어요.
      </p>
      <Button asChild size="lg" className="w-full">
        <Link to={paths.login} state={state}>
          로그인하고 물어보기
        </Link>
      </Button>
    </div>
  )
}

function Intro({ onPick }: { onPick: (question: string) => void }) {
  return (
    <div className="grid gap-4">
      <p className="type-body-sm text-gray-700">
        오픈 시각, 매수 제한, 결제·환불, 대기열처럼 예매 규정을 바탕으로 답해요. 답에는 근거가 된
        안내를 함께 보여 드려요.
      </p>
      <div className="grid gap-2">
        <p className="type-caption font-semibold text-gray-500">이런 걸 물어볼 수 있어요</p>
        <ul className="flex flex-wrap gap-2">
          {EXAMPLE_QUESTIONS.map((example) => (
            <li key={example}>
              <button
                type="button"
                onClick={() => onPick(example)}
                className="rounded-full border border-gray-200 bg-gray-0 px-3.5 py-2 text-left type-body-sm text-gray-700 outline-none transition-colors duration-(--dur-fast) hover:border-gray-300 hover:text-gray-900 focus-visible:ring-2 focus-visible:ring-ring"
              >
                {example}
              </button>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}

function AnswerBubble({
  turn,
  retryDisabled,
  onRetry,
}: {
  turn: ChatTurn
  retryDisabled: boolean
  onRetry: () => void
}) {
  const streaming = turn.status === 'streaming'
  const fallback = turn.fallbackReason === null ? null : FALLBACK_NOTICE[turn.fallbackReason]
  return (
    <div className="grid max-w-[92%] gap-2.5 rounded-[18px] rounded-bl-[6px] bg-gray-50 px-4 py-3">
      <span className="sr-only">답변: </span>
      {turn.mode !== null ? (
        <span
          className={cn(
            'inline-flex w-fit items-center rounded-pill px-2 py-0.5 text-[11px] leading-4 font-semibold',
            turn.mode === 'AI' ? 'bg-primary-50 text-primary-700' : 'bg-gray-100 text-gray-600',
          )}
        >
          {turn.mode === 'AI' ? 'AI 답변' : '관련 안내'}
        </span>
      ) : null}
      {fallback !== null ? <p className="type-caption text-gray-600">{fallback}</p> : null}
      {turn.text !== '' ? (
        <p
          className={cn(
            'type-body-sm whitespace-pre-line text-gray-900',
            // 받는 중에는 글 끝에 커서를 둔다(꾸밈)
            streaming &&
              "after:ml-0.5 after:inline-block after:h-[1.1em] after:w-[2px] after:translate-y-[3px] after:bg-gray-900 after:content-[''] motion-safe:after:animate-pulse",
          )}
        >
          {turn.text}
        </p>
      ) : streaming ? (
        <TypingDots />
      ) : null}
      {turn.status === 'stopped' ? (
        <p className="type-caption text-gray-500">답변을 멈췄어요</p>
      ) : null}
      {turn.status === 'failed' ? (
        <FailureNote
          error={turn.error}
          streamOpened={turn.mode !== null}
          retryDisabled={retryDisabled}
          onRetry={onRetry}
        />
      ) : null}
      {turn.sources.length > 0 ? <Sources sources={turn.sources} /> : null}
    </div>
  )
}

function TypingDots() {
  return (
    <p className="flex items-center gap-2 type-caption text-gray-500">
      <span aria-hidden="true" className="flex gap-1">
        {[0, 150, 300].map((delay) => (
          <span
            key={delay}
            className="size-1.5 rounded-full bg-gray-400 motion-safe:animate-bounce"
            style={{ animationDelay: `${delay}ms` }}
          />
        ))}
      </span>
      답변을 만들고 있어요
    </p>
  )
}

function Sources({ sources }: { sources: AssistantSource[] }) {
  return (
    <div className="grid gap-1.5 border-t border-gray-200 pt-2.5">
      <p className="type-caption font-semibold text-gray-500">근거가 된 안내</p>
      <ul className="grid gap-1.5">
        {sources.map((source) => (
          <li key={source.id} className="grid gap-0.5 rounded-[12px] bg-gray-0 px-3 py-2">
            <span className="type-caption font-semibold text-gray-900">{source.title}</span>
            <span className="line-clamp-3 type-caption text-gray-600">{source.excerpt}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

// 실패 안내. 429는 기다릴 시간을, 서버를 쓸 수 없으면 예매 안내가 있는 곳을 알린다. 자동으로 다시 묻지 않는다
function FailureNote({
  error,
  streamOpened,
  retryDisabled,
  onRetry,
}: {
  error: unknown
  streamOpened: boolean
  retryDisabled: boolean
  onRetry: () => void
}) {
  const limited = isApiError(error) && error.status === 429
  const wait = isApiError(error) ? error.retryAfterSeconds : null
  const title = failureTitle(error, streamOpened)
  const body = limited
    ? wait !== null
      ? `${wait}초 뒤 다시 물어봐 주세요.`
      : '잠시 뒤 다시 물어봐 주세요.'
    : title === '지금 상담을 쓸 수 없어요'
      ? '공연 상세의 "알아 두세요"에서 예매 안내를 볼 수 있어요.'
      : '다시 물어봐 주세요.'
  return (
    <div className="grid gap-1 rounded-[12px] bg-warning-50 px-3 py-2.5 type-caption text-warning-700">
      <p className="font-semibold">{title}</p>
      <p>{body}</p>
      {limited ? null : (
        <button
          type="button"
          disabled={retryDisabled}
          onClick={onRetry}
          className="mt-1 w-fit rounded-sm font-semibold underline underline-offset-2 outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-60"
        >
          다시 묻기
        </button>
      )}
    </div>
  )
}

function Composer({
  ref,
  chat,
  onAsk,
}: {
  ref: RefObject<HTMLTextAreaElement | null>
  chat: AssistantChat
  onAsk: (question: string) => void
}) {
  const [question, setQuestion] = useState('')
  // 400이면 보낸 질문을 입력 칸에 다시 채워 고쳐 보내게 한다(오류마다 한 번)
  const [restoredFor, setRestoredFor] = useState<AssistantChat['fieldError']>(null)
  if (chat.fieldError !== null && chat.fieldError !== restoredFor) {
    setRestoredFor(chat.fieldError)
    if (question === '') {
      setQuestion(chat.fieldError.question)
    }
  }
  const inputId = useId()
  const hintId = useId()
  const errorId = useId()
  const trimmed = question.trim()

  function submit(event?: FormEvent) {
    event?.preventDefault()
    if (trimmed === '' || chat.streaming) {
      return
    }
    onAsk(trimmed)
    setQuestion('')
    // 마우스로 [보내기]를 눌렀어도 다음 질문을 바로 쓰게 입력 칸으로 돌아간다
    ref.current?.focus()
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // Enter로 보내고 Shift+Enter로 줄을 바꾼다. 한글 조합 중의 Enter는 글자를 끝내는 것이라 보내지 않는다
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      submit()
    }
  }

  return (
    <form onSubmit={submit} noValidate className="grid gap-1.5 border-t border-gray-100 px-4 py-3">
      <div className="flex items-end gap-2 rounded-[16px] border border-gray-200 bg-gray-25 py-1.5 pr-1.5 pl-3.5 focus-within:border-primary-600 focus-within:ring-1 focus-within:ring-primary-600">
        <label htmlFor={inputId} className="sr-only">
          질문
        </label>
        <textarea
          ref={ref}
          id={inputId}
          rows={1}
          value={question}
          maxLength={QUESTION_MAX_LENGTH}
          placeholder="예: 결제가 확인 중으로 나오면 어떻게 하나요?"
          aria-invalid={chat.fieldError !== null ? true : undefined}
          aria-describedby={chat.fieldError !== null ? `${errorId} ${hintId}` : hintId}
          onChange={(event) => {
            setQuestion(event.target.value)
            if (chat.fieldError !== null) {
              chat.clearFieldError()
            }
          }}
          onKeyDown={handleKeyDown}
          className="max-h-32 min-h-10 flex-1 resize-none bg-transparent py-2 type-body-sm text-gray-900 outline-none field-sizing-content placeholder:text-gray-400"
        />
        {/* Note: 보내기와 멈추기는 같은 버튼 하나이고, 빈 질문일 때도 disabled가 아니라 aria-disabled로 둔다.
            버튼을 바꿔 끼우거나 초점이 있는 버튼을 disabled로 만들면 초점이 body로 떨어져 Esc로 닫을 수도 없었다
            (테스트와 셀프 리뷰로 찾음). 빈 질문은 submit이 거른다 */}
        <button
          type={chat.streaming ? 'button' : 'submit'}
          onClick={chat.streaming ? chat.stop : undefined}
          aria-label={chat.streaming ? '답변 멈추기' : '보내기'}
          aria-disabled={!chat.streaming && trimmed === '' ? true : undefined}
          className={cn(
            'grid size-10 shrink-0 place-items-center rounded-full text-gray-0 outline-none transition-colors duration-(--dur-fast) focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring aria-disabled:cursor-not-allowed aria-disabled:bg-gray-200 aria-disabled:text-gray-400',
            chat.streaming ? 'bg-gray-900' : 'bg-primary-600',
          )}
        >
          {chat.streaming ? (
            <Square aria-hidden="true" className="size-3.5 fill-current" />
          ) : (
            <ArrowUp aria-hidden="true" className="size-5" />
          )}
        </button>
      </div>
      {chat.fieldError !== null ? (
        <p id={errorId} role="alert" className="type-caption text-danger-600">
          {chat.fieldError.reason}
        </p>
      ) : null}
      <p id={hintId} className="flex justify-between gap-3 type-caption text-gray-500">
        <span>AI 답변은 참고용이에요. 개인정보는 쓰지 마세요.</span>
        <span className="shrink-0 tabular-nums">
          {question.length}/{QUESTION_MAX_LENGTH}
        </span>
      </p>
    </form>
  )
}
