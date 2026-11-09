import { act, renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { ApiError, CLIENT_ERROR_CODES } from '@/api/apiError'
import { failureTitle, useAssistantChat } from '@/components/assistant/useAssistantChat'
import { fail } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { signInAsDemo } from '@/test/api'

const STREAM_PATH = '/api/v1/assistant/messages/stream'
const encoder = new TextEncoder()

const frame = (event: string, data: unknown) =>
  encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)

const AI_META = { mode: 'AI', fallbackReason: null }

// 첫 프레임들을 내보내다 멈춘 스트림. release() 뒤에 나머지 프레임을 보내고 닫는다.
// 클라이언트가 끊었는지(스트림 cancel 또는 요청 signal)는 wasCut()으로 본다
function gatedStream(
  first: Array<[string, unknown]>,
  rest: Array<[string, unknown]> = [],
  { once = true }: { once?: boolean } = {},
) {
  let release: () => void = () => {}
  const released = new Promise<void>((resolve) => {
    release = resolve
  })
  let cut = false
  mockServer.use(
    http.post(
      STREAM_PATH,
      ({ request }) => {
        request.signal.addEventListener('abort', () => {
          cut = true
        })
        const stream = new ReadableStream<Uint8Array>({
          async start(controller) {
            for (const [event, data] of first) {
              controller.enqueue(frame(event, data))
            }
            await released
            if (cut) {
              return
            }
            for (const [event, data] of rest) {
              controller.enqueue(frame(event, data))
            }
            controller.close()
          },
          cancel() {
            cut = true
          },
        })
        return new HttpResponse(stream, { headers: { 'Content-Type': 'text/event-stream' } })
      },
      { once },
    ),
  )
  return { release, wasCut: () => cut }
}

describe('useAssistantChat', () => {
  it('받는 중 새 질문을 보내면 앞 답은 멈춘 것으로 두고 새 답이 끝까지 간다', async () => {
    await signInAsDemo()
    gatedStream([
      ['meta', AI_META],
      ['delta', { text: '앞 답' }],
    ])
    const { result } = renderHook(() => useAssistantChat())

    act(() => result.current.ask('첫 질문'))
    await waitFor(() => expect(result.current.turns[0]?.text).toBe('앞 답'))
    act(() => result.current.ask('둘째 질문'))

    await waitFor(() =>
      expect(result.current.turns.map((turn) => turn.status)).toEqual(['stopped', 'done']),
    )
    expect(result.current.turns[0].text).toBe('앞 답')
    expect(result.current.turns[1].question).toBe('둘째 질문')
    expect(result.current.streaming).toBe(false)
  })

  it('받던 중 fallback이 오면 글을 비우고 검색 모드로 바꾸고, done으로 확정한다', async () => {
    await signInAsDemo()
    const { release } = gatedStream(
      [
        ['meta', AI_META],
        ['delta', { text: 'abc' }],
        ['fallback', { fallbackReason: 'LLM_UNAVAILABLE' }],
      ],
      [
        ['sources', { sources: [{ id: 's', title: '제목', excerpt: '발췌' }] }],
        ['done', { answer: '고정 문장', mode: 'SEARCH', fallbackReason: 'LLM_UNAVAILABLE' }],
      ],
    )
    const { result } = renderHook(() => useAssistantChat())

    act(() => result.current.ask('질문'))

    await waitFor(() => expect(result.current.turns[0].mode).toBe('SEARCH'))
    expect(result.current.turns[0]).toMatchObject({
      text: '',
      status: 'streaming',
      fallbackReason: 'LLM_UNAVAILABLE',
    })
    release()
    await waitFor(() => expect(result.current.turns[0].status).toBe('done'))
    expect(result.current.turns[0]).toMatchObject({
      text: '고정 문장',
      mode: 'SEARCH',
      sources: [{ id: 's', title: '제목', excerpt: '발췌' }],
    })
    expect(result.current.announcement).toBe('답변: 고정 문장')
  })

  it('받는 중 언마운트하면 서버 쪽 요청을 끊는다', async () => {
    await signInAsDemo()
    const { wasCut } = gatedStream([
      ['meta', AI_META],
      ['delta', { text: '받는 중' }],
    ])
    const { result, unmount } = renderHook(() => useAssistantChat())
    act(() => result.current.ask('질문'))
    await waitFor(() => expect(result.current.turns[0]?.text).toBe('받는 중'))

    unmount()

    await waitFor(() => expect(wasCut()).toBe(true))
  })

  it('400이면 대화에서 그 질문을 빼고 이유와 보낸 질문을 fieldError로 둔다', async () => {
    await signInAsDemo()
    mockServer.use(
      http.post(STREAM_PATH, () =>
        fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
          errors: [{ field: 'question', reason: '이유 문장' }],
        }),
      ),
    )
    const { result } = renderHook(() => useAssistantChat())

    act(() => result.current.ask('거절될 질문'))

    await waitFor(() => expect(result.current.fieldError).not.toBeNull())
    expect(result.current.fieldError).toEqual({ reason: '이유 문장', question: '거절될 질문' })
    expect(result.current.turns).toEqual([])
    expect(result.current.streaming).toBe(false)
  })
})

describe('failureTitle', () => {
  const apiError = (status: number, code: string) => new ApiError({ status, code, message: '실패' })

  it.each([
    ['429', apiError(429, 'RATE_LIMITED'), false, '질문이 많아요'],
    [
      '네트워크 오류, 스트림이 열린 뒤',
      apiError(0, CLIENT_ERROR_CODES.networkError),
      true,
      '답변이 중간에 끊겼어요',
    ],
    [
      '네트워크 오류, 스트림이 열리기 전',
      apiError(0, CLIENT_ERROR_CODES.networkError),
      false,
      '지금 상담을 쓸 수 없어요',
    ],
    ['503', apiError(503, 'SERVICE_UNAVAILABLE'), false, '지금 상담을 쓸 수 없어요'],
    [
      '응답 스키마 불일치',
      apiError(200, CLIENT_ERROR_CODES.responseSchemaMismatch),
      true,
      '답변을 받지 못했어요',
    ],
    ['ApiError가 아닌 오류', new Error('boom'), false, '답변을 받지 못했어요'],
  ])('%s → "%s" 문장', (_name, error, opened, expected) => {
    expect(failureTitle(error, opened)).toBe(expected)
  })
})
