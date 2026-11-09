import { http, HttpResponse } from 'msw'
import { waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ApiError, CLIENT_ERROR_CODES } from '@/api/apiError'
import { streamAssistant, type AssistantStreamEvent } from '@/api/assistant'
import { sessionStore } from '@/auth/session'
import { expireMockAccessTokens } from '@/mocks/db'
import { mockServer } from '@/mocks/server'
import { recordRequests, signInAsDemo } from '@/test/api'

const STREAM_PATH = '/api/v1/assistant/messages/stream'

async function ask(question: string, signal?: AbortSignal) {
  const events: AssistantStreamEvent[] = []
  const answer = await streamAssistant(question, { signal, onEvent: (event) => events.push(event) })
  return { events, answer }
}

async function captureError(promise: Promise<unknown>): Promise<unknown> {
  return promise.then(
    () => null,
    (reason: unknown) => reason,
  )
}

function eventStreamResponse(body: string) {
  return new HttpResponse(body, { headers: { 'Content-Type': 'text/event-stream' } })
}

const types = (events: AssistantStreamEvent[]) => events.map((event) => event.type)

describe('streamAssistant', () => {
  it('meta → delta… → sources → done 순서로 알리고, 조각을 이으면 done의 답과 같다', async () => {
    await signInAsDemo()

    const { events, answer } = await ask('오픈 시각이 언제인가요?')

    const kinds = types(events)
    expect(kinds[0]).toBe('meta')
    expect(kinds.at(-2)).toBe('sources')
    expect(kinds.at(-1)).toBe('done')
    expect(kinds.filter((kind) => kind === 'delta').length).toBeGreaterThan(1)
    expect(kinds.slice(1, -2).every((kind) => kind === 'delta')).toBe(true)
    const joined = events.flatMap((event) => (event.type === 'delta' ? [event.text] : [])).join('')
    expect(answer.answer).toBe(joined)
    expect(answer).toMatchObject({ mode: 'AI', fallbackReason: null })
    expect(answer.sources.map((source) => source.id)).toEqual(['open-time.when'])
  })

  it('검색 모드는 delta 없이 sources와 done이다', async () => {
    await signInAsDemo()

    const { events, answer } = await ask('검색해 주세요')

    expect(types(events)).toEqual(['meta', 'sources', 'done'])
    expect(events[0]).toEqual({ type: 'meta', mode: 'SEARCH', fallbackReason: 'LLM_DISABLED' })
    expect(answer.mode).toBe('SEARCH')
    expect(answer.fallbackReason).toBe('LLM_DISABLED')
    expect(answer.sources.length).toBeGreaterThan(0)
  })

  it('LLM 장애로 시작한 검색 모드는 사유가 LLM_UNAVAILABLE이다', async () => {
    await signInAsDemo()

    const { answer } = await ask('지연되나요')

    expect(answer).toMatchObject({ mode: 'SEARCH', fallbackReason: 'LLM_UNAVAILABLE' })
  })

  it('답하는 중 강등되면 fallback 뒤 done이 검색 모드다', async () => {
    await signInAsDemo()

    const { events, answer } = await ask('끊김')

    const kinds = types(events)
    expect(kinds).toContain('delta')
    expect(kinds.indexOf('fallback')).toBeGreaterThan(kinds.lastIndexOf('delta'))
    expect(kinds.slice(-2)).toEqual(['sources', 'done'])
    expect(answer).toMatchObject({ mode: 'SEARCH', fallbackReason: 'LLM_UNAVAILABLE' })
  })

  it('done 없이 스트림이 끝나면 NETWORK_ERROR다', async () => {
    await signInAsDemo()
    const events: AssistantStreamEvent[] = []

    const error = await captureError(
      streamAssistant('중단', { onEvent: (event) => events.push(event) }),
    )

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 0, code: CLIENT_ERROR_CODES.networkError })
    expect(types(events)).toContain('delta')
  })

  it('알 수 없는 이벤트는 무시한다', async () => {
    await signInAsDemo()
    mockServer.use(
      http.post(STREAM_PATH, () =>
        eventStreamResponse(
          'event: meta\ndata: {"mode":"AI","fallbackReason":null}\n\n' +
            'event: heartbeat\ndata: {}\n\n' +
            'event: done\ndata: {"answer":"끝","mode":"AI","fallbackReason":null}\n\n',
        ),
      ),
    )

    const { events, answer } = await ask('질문')

    expect(types(events)).toEqual(['meta', 'done'])
    expect(answer).toEqual({ answer: '끝', mode: 'AI', fallbackReason: null, sources: [] })
  })

  it('done을 받으면 그 뒤 이벤트는 쓰지 않는다', async () => {
    await signInAsDemo()
    mockServer.use(
      http.post(STREAM_PATH, () =>
        eventStreamResponse(
          'event: done\ndata: {"answer":"끝","mode":"AI","fallbackReason":null}\n\n' +
            'event: delta\ndata: {"text":"뒤"}\n\n',
        ),
      ),
    )

    const { events } = await ask('질문')

    expect(types(events)).toEqual(['done'])
  })

  it('data가 JSON이 아니면 RESPONSE_SCHEMA_MISMATCH다', async () => {
    await signInAsDemo()
    mockServer.use(
      http.post(STREAM_PATH, () => eventStreamResponse('event: delta\ndata: 안녕\n\n')),
    )
    const consoleError = console.error
    console.error = () => {}

    try {
      const error = await captureError(ask('질문'))
      expect(error).toMatchObject({ code: CLIENT_ERROR_CODES.responseSchemaMismatch })
    } finally {
      console.error = consoleError
    }
  })

  it('data가 스키마와 다르면 RESPONSE_SCHEMA_MISMATCH다', async () => {
    await signInAsDemo()
    mockServer.use(
      http.post(STREAM_PATH, () =>
        eventStreamResponse('event: meta\ndata: {"mode":"UNKNOWN","fallbackReason":null}\n\n'),
      ),
    )
    const consoleError = console.error
    console.error = () => {}

    try {
      const error = await captureError(ask('질문'))
      expect(error).toMatchObject({ code: CLIENT_ERROR_CODES.responseSchemaMismatch })
    } finally {
      console.error = consoleError
    }
  })

  it('질문이 비어 있으면 400 VALIDATION_FAILED와 칸별 오류를 그대로 던진다', async () => {
    await signInAsDemo()

    const error = await captureError(ask('   '))

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: 400,
      code: 'VALIDATION_FAILED',
      fieldErrors: [{ field: 'question', reason: '1자 이상 500자 이하로 입력해 주세요' }],
    })
  })

  it('요청 제한 429는 Retry-After를 살려 던진다', async () => {
    await signInAsDemo()

    const error = await captureError(ask('많이 물어봅니다'))

    expect(error).toMatchObject({ status: 429, code: 'RATE_LIMITED', retryAfterSeconds: 30 })
  })

  it('200인데 스트림이 아니면 UNEXPECTED_RESPONSE다', async () => {
    await signInAsDemo()
    mockServer.use(http.post(STREAM_PATH, () => HttpResponse.json({ data: {} })))

    const error = await captureError(ask('질문'))

    expect(error).toMatchObject({ status: 200, code: CLIENT_ERROR_CODES.unexpectedResponse })
  })

  it('연결하지 못하면 NETWORK_ERROR다', async () => {
    await signInAsDemo()
    mockServer.use(http.post(STREAM_PATH, () => HttpResponse.error()))

    const error = await captureError(ask('질문'))

    expect(error).toMatchObject({ status: 0, code: CLIENT_ERROR_CODES.networkError })
  })

  it('요청은 스트림을 받겠다는 Accept와 Bearer, 질문 본문으로 나간다', async () => {
    await signInAsDemo()
    const requests = recordRequests(STREAM_PATH)

    await ask('환불')

    expect(requests).toHaveLength(1)
    // 400·429를 JSON으로 받으려면 JSON도 함께 받겠다고 알려야 한다
    expect(requests[0].headers.get('Accept')).toBe('text/event-stream, application/json')
    expect(requests[0].headers.get('Authorization')).toBe(
      `Bearer ${sessionStore.getState().accessToken}`,
    )
    expect(await requests[0].json()).toEqual({ question: '환불' })
  })

  it('요청 전에 이미 취소했으면 AbortError로 끝난다', async () => {
    await signInAsDemo()
    const controller = new AbortController()
    controller.abort()

    const error = await captureError(ask('질문', controller.signal))

    expect(error).toMatchObject({ name: 'AbortError' })
  })

  it('답을 읽는 중에 취소하면 AbortError로 끝난다', async () => {
    await signInAsDemo()
    const controller = new AbortController()
    const events: AssistantStreamEvent[] = []

    const error = await captureError(
      streamAssistant('오픈', {
        signal: controller.signal,
        onEvent: (event) => {
          events.push(event)
          if (event.type === 'delta') {
            controller.abort()
          }
        },
      }),
    )

    expect(error).toMatchObject({ name: 'AbortError' })
    expect(types(events)).not.toContain('done')
  })

  it('Access 토큰이 만료되면 한 번 재발급하고 스트림 요청을 다시 보낸다', async () => {
    await signInAsDemo()
    expireMockAccessTokens()
    const streamRequests = recordRequests(STREAM_PATH)
    const refreshRequests = recordRequests('/api/v1/auth/refresh')

    const { answer } = await ask('오픈')

    expect(answer.mode).toBe('AI')
    expect(refreshRequests).toHaveLength(1)
    expect(streamRequests).toHaveLength(2)
  })

  it('다음 조각을 기다리는 중에 취소하면 AbortError로 끝나고 서버 스트림이 끊긴다', async () => {
    await signInAsDemo()
    let cut = false
    mockServer.use(
      http.post(STREAM_PATH, () => {
        const stream = new ReadableStream<Uint8Array>({
          start(controller) {
            controller.enqueue(
              new TextEncoder().encode(
                'event: meta\ndata: {"mode":"AI","fallbackReason":null}\n\n',
              ),
            )
          },
          cancel() {
            cut = true
          },
        })
        return new HttpResponse(stream, { headers: { 'Content-Type': 'text/event-stream' } })
      }),
    )
    const controller = new AbortController()
    let opened: () => void = () => {}
    const metaArrived = new Promise<void>((resolve) => {
      opened = resolve
    })

    const pending = captureError(
      streamAssistant('질문', { signal: controller.signal, onEvent: () => opened() }),
    )
    await metaArrived
    controller.abort()

    expect(await pending).toMatchObject({ name: 'AbortError' })
    await waitFor(() => expect(cut).toBe(true))
  })

  it('읽는 중에 서버 스트림이 오류로 끊기면 NETWORK_ERROR다', async () => {
    await signInAsDemo()
    mockServer.use(
      http.post(STREAM_PATH, () => {
        const stream = new ReadableStream<Uint8Array>({
          start(controller) {
            controller.enqueue(
              new TextEncoder().encode(
                'event: meta\ndata: {"mode":"AI","fallbackReason":null}\n\n',
              ),
            )
            setTimeout(() => controller.error(new Error('연결 끊김')), 5)
          },
        })
        return new HttpResponse(stream, { headers: { 'Content-Type': 'text/event-stream' } })
      }),
    )

    const error = await captureError(ask('질문'))

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 0, code: CLIENT_ERROR_CODES.networkError })
  })

  it('한글이 바이트 중간에서 잘려 와도 맞게 읽는다', async () => {
    await signInAsDemo()
    const bytes = new TextEncoder().encode(
      'event: delta\ndata: {"text":"한글 조각"}\n\n' +
        'event: done\ndata: {"answer":"한글 답변입니다","mode":"AI","fallbackReason":null}\n\n',
    )
    mockServer.use(
      http.post(STREAM_PATH, () => {
        const stream = new ReadableStream<Uint8Array>({
          start(controller) {
            // 한 바이트씩 보내 글자(3바이트) 중간에서 끊기게 한다
            for (const byte of bytes) {
              controller.enqueue(new Uint8Array([byte]))
            }
            controller.close()
          },
        })
        return new HttpResponse(stream, { headers: { 'Content-Type': 'text/event-stream' } })
      }),
    )

    const { events, answer } = await ask('질문')

    expect(events[0]).toEqual({ type: 'delta', text: '한글 조각' })
    expect(answer.answer).toBe('한글 답변입니다')
  })
})
