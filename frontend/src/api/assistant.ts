import { z } from 'zod'
import { ApiError, CLIENT_ERROR_CODES } from '@/api/apiError'
import { apiStream } from '@/api/client'
import { createSseParser, type SseFrame } from '@/api/sse'

// 내부 AI 상담(SA F8, docs/design/assistant/README.md 2-1절). 로그인한 사용자만 쓴다.
// 답은 SSE로 조각조각 온다. LLM이 꺼져 있거나 장애면 오류 대신 mode SEARCH(검색 결과와 출처만)로 온다.

export const QUESTION_MAX_LENGTH = 500

export const assistantSourceSchema = z.object({
  id: z.string(),
  title: z.string(),
  excerpt: z.string(),
})
export type AssistantSource = z.infer<typeof assistantSourceSchema>

export const fallbackReasonSchema = z.enum(['LLM_DISABLED', 'LLM_UNAVAILABLE'])
export type AssistantFallbackReason = z.infer<typeof fallbackReasonSchema>

const modeSchema = z.enum(['AI', 'SEARCH'])
type AssistantMode = z.infer<typeof modeSchema>

export interface AssistantAnswer {
  answer: string
  mode: AssistantMode
  fallbackReason: AssistantFallbackReason | null
  sources: AssistantSource[]
}

export type AssistantStreamEvent =
  | { type: 'meta'; mode: AssistantMode; fallbackReason: AssistantFallbackReason | null }
  | { type: 'delta'; text: string }
  | { type: 'fallback'; fallbackReason: AssistantFallbackReason }
  | { type: 'sources'; sources: AssistantSource[] }
  | {
      type: 'done'
      answer: string
      mode: AssistantMode
      fallbackReason: AssistantFallbackReason | null
    }

const eventSchemas = {
  meta: z.object({ mode: modeSchema, fallbackReason: fallbackReasonSchema.nullable() }),
  delta: z.object({ text: z.string() }),
  fallback: z.object({ fallbackReason: fallbackReasonSchema }),
  sources: z.object({ sources: z.array(assistantSourceSchema) }),
  done: z.object({
    answer: z.string(),
    mode: modeSchema,
    fallbackReason: fallbackReasonSchema.nullable(),
  }),
} as const

type EventName = keyof typeof eventSchemas

function isEventName(name: string): name is EventName {
  return Object.hasOwn(eventSchemas, name)
}

// 질문을 보내고 답을 스트림으로 읽는다. 이벤트가 오는 대로 onEvent를 부르고, `done`을 받으면 최종 답을 돌려준다.
// `done` 없이 끝나면 연결이 끊긴 것이므로 networkError다. 화면은 받던 글을 버리거나 다시 묻게 한다.
export async function streamAssistant(
  question: string,
  options: { signal?: AbortSignal; onEvent: (event: AssistantStreamEvent) => void },
): Promise<AssistantAnswer> {
  const { signal, onEvent } = options
  const response = await apiStream({
    method: 'POST',
    path: '/assistant/messages/stream',
    body: { question },
    signal,
  })
  if (response.body === null) {
    throw incompleteAnswer()
  }

  let sources: AssistantSource[] = []
  let result: AssistantAnswer | null = null
  const parser = createSseParser((frame) => {
    // done 뒤나 취소 뒤에 같은 조각으로 온 이벤트는 쓰지 않는다
    if (result !== null || signal?.aborted) {
      return
    }
    const event = parseEvent(frame)
    if (event === null) {
      return
    }
    if (event.type === 'sources') {
      sources = event.sources
    }
    onEvent(event)
    if (event.type === 'done') {
      result = {
        answer: event.answer,
        mode: event.mode,
        fallbackReason: event.fallbackReason,
        sources,
      }
    }
  })

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  try {
    while (result === null) {
      const chunk = await readChunk(reader, signal)
      if (chunk.done) {
        break
      }
      parser.push(decoder.decode(chunk.value, { stream: true }))
      // 한 조각에 이벤트가 여러 개 와서 읽는 중에 취소해도 취소로 끝낸다(done까지 와 있어도 답을 돌려주지 않는다)
      signal?.throwIfAborted()
    }
  } finally {
    parser.end()
    void reader.cancel().catch(() => undefined)
  }
  if (result === null) {
    throw incompleteAnswer()
  }
  return result
}

async function readChunk(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  signal: AbortSignal | undefined,
) {
  try {
    return await reader.read()
  } catch (error) {
    // 취소는 호출한 쪽이 직접 처리하므로 그대로 던진다
    if (signal?.aborted) {
      throw error
    }
    throw incompleteAnswer()
  }
}

function incompleteAnswer(): ApiError {
  return new ApiError({
    status: 0,
    code: CLIENT_ERROR_CODES.networkError,
    message: '답변을 끝까지 받지 못했습니다',
  })
}

// 알 수 없는 이벤트는 null(무시). 계약과 다른 data는 화면이 깨지기 전에 드러내려고 던진다(transport의 parseData와 같은 이유).
function parseEvent(frame: SseFrame): AssistantStreamEvent | null {
  if (!isEventName(frame.event)) {
    return null
  }
  let json: unknown
  try {
    json = JSON.parse(frame.data)
  } catch {
    throw schemaMismatch(frame.event, 'JSON이 아닙니다')
  }
  const parsed = eventSchemas[frame.event].safeParse(json)
  if (!parsed.success) {
    throw schemaMismatch(frame.event, parsed.error.issues)
  }
  return { type: frame.event, ...parsed.data } as AssistantStreamEvent
}

function schemaMismatch(event: string, detail: unknown): ApiError {
  console.error(`[api] POST /assistant/messages/stream ${event} 이벤트가 계약과 다릅니다`, detail)
  return new ApiError({
    status: 200,
    code: CLIENT_ERROR_CODES.responseSchemaMismatch,
    message: '응답 형식이 계약과 다릅니다',
  })
}
