import { useMutation } from '@tanstack/react-query'
import { MessageCircleQuestion } from 'lucide-react'
import { useId, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import {
  askAssistant,
  QUESTION_MAX_LENGTH,
  type AssistantAnswer,
  type AssistantSource,
} from '@/api/assistant'
import { CLIENT_ERROR_CODES, isApiError } from '@/api/apiError'
import { paths } from '@/app/paths'
import { TextAreaField } from '@/components/form/TextAreaField'
import { Alert } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'

// 예매 문의(SA F8, ISE-126). 규정 문서를 근거로 답하고 출처를 보인다(docs/design/assistant/README.md 7절).
// 화면을 열 때는 요청하지 않고 보낼 때만 요청한다. 대화는 이 화면 상태에만 두고 저장하지 않는다.
// 자동 재시도는 하지 않는다(질문마다 LLM 비용이 든다).

const EXAMPLE_QUESTIONS = [
  '예매 오픈은 언제 하나요?',
  '한 사람이 몇 장까지 살 수 있나요?',
  '결제가 늦게 되면 환불되나요?',
  '대기열 순번은 어떻게 정해지나요?',
]

const FALLBACK_NOTICE: Record<NonNullable<AssistantAnswer['fallbackReason']>, string> = {
  LLM_DISABLED: 'AI 답변이 꺼져 있어 관련 안내만 보여 드려요.',
  LLM_UNAVAILABLE: 'AI 답변이 잠시 어려워 관련 안내만 보여 드려요.',
}

interface Turn {
  id: number
  question: string
  answer: AssistantAnswer
}

export function AssistantPage() {
  const [turns, setTurns] = useState<Turn[]>([])
  const [question, setQuestion] = useState('')
  const [fieldError, setFieldError] = useState<string | undefined>()
  const nextId = useRef(1)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const statusId = useId()

  const ask = useMutation({
    mutationFn: askAssistant,
    // 답변을 화면을 떠난 뒤 캐시에 남기지 않는다
    gcTime: 0,
    onSuccess: (answer, asked) => {
      setTurns((current) => [...current, { id: nextId.current++, question: asked, answer }])
      // 기다리는 동안 다음 질문을 쓰기 시작했으면 지우지 않는다
      setQuestion((current) => (current.trim() === asked ? '' : current))
    },
    onError: (error) => {
      if (isApiError(error, 'VALIDATION_FAILED')) {
        setFieldError(error.fieldErrors?.[0]?.reason ?? '질문을 확인해 주세요')
      }
    },
  })

  function send(text: string) {
    const trimmed = text.trim()
    if (trimmed === '') {
      setFieldError('질문을 입력해 주세요')
      textareaRef.current?.focus()
      return
    }
    setFieldError(undefined)
    ask.mutate(trimmed)
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    send(question)
  }

  return (
    <main className="mx-auto grid w-full max-w-[720px] gap-6">
      <div className="grid gap-1">
        <h1 className="type-title text-gray-900">예매 문의</h1>
        <p className="type-body-sm text-gray-500">
          오픈 시각, 매수 제한, 결제·환불, 대기열처럼 예매 규정을 바탕으로 답해요
        </p>
      </div>

      <Alert variant="info">
        <p>AI 답변은 참고용이에요. 이름·연락처·카드 번호 같은 개인정보는 쓰지 마세요.</p>
      </Alert>

      {turns.length === 0 ? (
        <section aria-labelledby="examples-title" className="grid gap-3">
          <h2 id="examples-title" className="type-body font-semibold text-gray-900">
            이런 걸 물어볼 수 있어요
          </h2>
          <ul className="flex flex-wrap gap-2">
            {EXAMPLE_QUESTIONS.map((example) => (
              <li key={example}>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={ask.isPending}
                  onClick={() => {
                    setQuestion(example)
                    send(example)
                  }}
                >
                  {example}
                </Button>
              </li>
            ))}
          </ul>
        </section>
      ) : (
        <ol aria-label="대화" className="grid gap-5">
          {turns.map((turn) => (
            <li key={turn.id} className="grid gap-3">
              <p className="ml-auto max-w-[85%] rounded-lg bg-primary-50 px-4 py-3 type-body whitespace-pre-line text-gray-900">
                <span className="sr-only">질문: </span>
                {turn.question}
              </p>
              <AnswerCard answer={turn.answer} />
            </li>
          ))}
        </ol>
      )}

      <p id={statusId} role="status" aria-live="polite" className="sr-only">
        {ask.isPending ? '답변을 만들고 있어요' : ''}
      </p>

      {ask.isError && !isApiError(ask.error, 'VALIDATION_FAILED') ? (
        <AskErrorNotice error={ask.error} />
      ) : null}

      <form onSubmit={handleSubmit} noValidate className="grid gap-3">
        <TextAreaField
          ref={textareaRef}
          label="질문"
          value={question}
          maxLength={QUESTION_MAX_LENGTH}
          placeholder="예: 결제가 확인 중으로 나오면 어떻게 하나요?"
          error={fieldError}
          description={`${question.length}/${QUESTION_MAX_LENGTH}자`}
          onChange={(event) => {
            setQuestion(event.target.value)
            if (fieldError !== undefined) setFieldError(undefined)
          }}
        />
        <Button type="submit" className="w-fit" loading={ask.isPending}>
          물어보기
        </Button>
      </form>
    </main>
  )
}

function AnswerCard({ answer }: { answer: AssistantAnswer }) {
  const fallback = answer.fallbackReason === null ? null : FALLBACK_NOTICE[answer.fallbackReason]
  return (
    <Card className="gap-3 px-5 py-4">
      <div className="flex items-center gap-2">
        <MessageCircleQuestion aria-hidden="true" className="size-4 text-gray-500" />
        {answer.mode === 'AI' ? (
          <Badge variant="lottery">AI 답변</Badge>
        ) : (
          <Badge variant="soldOut">관련 안내</Badge>
        )}
      </div>
      {fallback !== null ? <p className="type-body-sm text-gray-500">{fallback}</p> : null}
      <p className="type-body whitespace-pre-line text-gray-900">{answer.answer}</p>
      {answer.sources.length > 0 ? (
        <div className="grid gap-2">
          <p className="type-caption font-semibold text-gray-500">출처</p>
          <ul className="grid gap-2">
            {answer.sources.map((source) => (
              <SourceItem key={source.id} source={source} />
            ))}
          </ul>
        </div>
      ) : null}
    </Card>
  )
}

function SourceItem({ source }: { source: AssistantSource }) {
  return (
    <li className="grid gap-0.5 rounded-md bg-gray-50 px-3 py-2">
      <span className="type-body-sm font-semibold text-gray-900">{source.title}</span>
      <span className="type-caption text-gray-600">{source.excerpt}</span>
    </li>
  )
}

function AskErrorNotice({ error }: { error: Error }) {
  if (isApiError(error) && error.status === 429) {
    const wait = error.retryAfterSeconds
    return (
      <Alert variant="warning">
        <p>
          질문이 많아요.{' '}
          {wait !== null ? `${wait}초 뒤 다시 물어봐 주세요.` : '잠시 뒤 다시 물어봐 주세요.'}
        </p>
      </Alert>
    )
  }
  const unavailable =
    isApiError(error) &&
    (error.status === 503 || error.code === CLIENT_ERROR_CODES.networkError || error.status >= 500)
  return (
    <Alert variant={unavailable ? 'warning' : 'danger'}>
      <p className="font-semibold">
        {unavailable ? '지금 상담을 쓸 수 없어요' : '답변을 받지 못했어요'}
      </p>
      <p>
        공연 상세의 "알아 두세요"에서 예매 안내를 볼 수 있어요.{' '}
        <Link to={paths.home} className="font-semibold underline underline-offset-2">
          공연 보러 가기
        </Link>
      </p>
    </Alert>
  )
}
