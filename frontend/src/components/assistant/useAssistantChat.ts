import { useCallback, useEffect, useRef, useState } from 'react'
import { CLIENT_ERROR_CODES, isApiError } from '@/api/apiError'
import {
  streamAssistant,
  type AssistantFallbackReason,
  type AssistantSource,
} from '@/api/assistant'

// 예매 문의 대화 한 번(질문 하나와 그 답). 대화는 이 탭 메모리에만 두고 저장하지 않는다(docs/design/assistant/README.md 7절)
export interface ChatTurn {
  id: number
  question: string
  // streaming 받는 중, done 끝, stopped 사용자가 멈춤(또는 위젯을 닫을 수 없는 화면으로 감), failed 실패
  status: 'streaming' | 'done' | 'stopped' | 'failed'
  // 받은 글. 받는 동안은 delta를 이은 값이고, done을 받으면 서버가 확정한 답으로 바꾼다
  text: string
  mode: 'AI' | 'SEARCH' | null
  fallbackReason: AssistantFallbackReason | null
  sources: AssistantSource[]
  error: unknown
}

export interface AssistantChat {
  turns: ChatTurn[]
  streaming: boolean
  // 입력 칸 아래에 보이는 400 오류
  fieldError: string | null
  // 화면 낭독기에 한 번 알릴 문장. 토큰마다 읽지 않게 끝났을 때만 바꾼다
  announcement: string
  ask: (question: string) => void
  stop: () => void
  reset: () => void
  clearFieldError: () => void
}

// 질문을 보내고 스트리밍 답을 받는다(2-1절). 자동 재시도는 하지 않는다(질문마다 LLM 비용이 든다).
// Note: 받는 중에는 새 질문을 보내지 못하게 화면이 막는다. 그래도 새 질문이 오면 앞 스트림을 끊고 시작한다
export function useAssistantChat(): AssistantChat {
  const [turns, setTurns] = useState<ChatTurn[]>([])
  const [fieldError, setFieldError] = useState<string | null>(null)
  const [announcement, setAnnouncement] = useState('')
  const controllerRef = useRef<AbortController | null>(null)
  const nextIdRef = useRef(1)

  const update = useCallback((id: number, change: (turn: ChatTurn) => ChatTurn) => {
    setTurns((current) => current.map((turn) => (turn.id === id ? change(turn) : turn)))
  }, [])

  const ask = useCallback(
    (question: string) => {
      controllerRef.current?.abort()
      const controller = new AbortController()
      controllerRef.current = controller
      const id = nextIdRef.current++
      setFieldError(null)
      setAnnouncement('')
      setTurns((current) => [
        ...current,
        {
          id,
          question,
          status: 'streaming',
          text: '',
          mode: null,
          fallbackReason: null,
          sources: [],
          error: null,
        },
      ])

      // 실패 문장을 고르려고 받은 글이 있었는지 센다(중간에 끊긴 것과 처음부터 안 된 것을 나눈다)
      let received = ''
      void streamAssistant(question, {
        signal: controller.signal,
        onEvent: (event) => {
          switch (event.type) {
            case 'meta':
              update(id, (turn) => ({
                ...turn,
                mode: event.mode,
                fallbackReason: event.fallbackReason,
              }))
              break
            case 'delta':
              received += event.text
              update(id, (turn) => ({ ...turn, text: turn.text + event.text }))
              break
            case 'fallback':
              // 이어 붙이던 AI 답을 버리고 검색 모드로 바꾼다(2-1절)
              received = ''
              update(id, (turn) => ({
                ...turn,
                text: '',
                mode: 'SEARCH',
                fallbackReason: event.fallbackReason,
              }))
              break
            case 'sources':
              update(id, (turn) => ({ ...turn, sources: event.sources }))
              break
            case 'done':
              break
          }
        },
      })
        .then((answer) => {
          update(id, (turn) => ({
            ...turn,
            status: 'done',
            text: answer.answer,
            mode: answer.mode,
            fallbackReason: answer.fallbackReason,
            sources: answer.sources,
          }))
          setAnnouncement(`답변: ${answer.answer}`)
        })
        .catch((error: unknown) => {
          if (controller.signal.aborted) {
            update(id, (turn) => ({ ...turn, status: 'stopped' }))
            return
          }
          if (isApiError(error, 'VALIDATION_FAILED')) {
            // 질문을 고쳐 다시 보내게 대화에서 빼고 입력 칸 아래에 알린다
            setTurns((current) => current.filter((turn) => turn.id !== id))
            setFieldError(error.fieldErrors?.[0]?.reason ?? '질문을 확인해 주세요')
            return
          }
          update(id, (turn) => ({ ...turn, status: 'failed', error }))
          setAnnouncement(failureTitle(error, received !== ''))
        })
        .finally(() => {
          if (controllerRef.current === controller) {
            controllerRef.current = null
          }
        })
    },
    [update],
  )

  const stop = useCallback(() => {
    controllerRef.current?.abort()
  }, [])

  const reset = useCallback(() => {
    controllerRef.current?.abort()
    setTurns([])
    setFieldError(null)
    setAnnouncement('')
  }, [])

  const clearFieldError = useCallback(() => setFieldError(null), [])

  // 위젯이 사라지면(로그아웃 등) 받던 답을 끊는다
  useEffect(() => () => controllerRef.current?.abort(), [])

  return {
    turns,
    streaming: turns.some((turn) => turn.status === 'streaming'),
    fieldError,
    announcement,
    ask,
    stop,
    reset,
    clearFieldError,
  }
}

// 실패한 답의 제목. 화면과 알림 영역이 같은 문장을 쓴다
export function failureTitle(error: unknown, hadText: boolean): string {
  if (isApiError(error) && error.status === 429) {
    return '질문이 많아요'
  }
  if (isApiError(error, CLIENT_ERROR_CODES.networkError) && hadText) {
    return '답변이 중간에 끊겼어요'
  }
  if (isApiError(error) && (error.status === 0 || error.status >= 500)) {
    return '지금 상담을 쓸 수 없어요'
  }
  return '답변을 받지 못했어요'
}
