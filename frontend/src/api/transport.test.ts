import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { z } from 'zod'
import { send } from '@/api/transport'
import { enableDemoMode } from '@/demo/demoMode'
import { requestLogStore } from '@/demo/requestLog'
import { mockServer } from '@/mocks/server'

const TRACE_ID = '4bf92f3577b34da6a3ce929d0e0e4736'
const schema = z.object({ value: z.number() })

const entries = () => requestLogStore.getState().entries

function success(data: unknown) {
  return HttpResponse.json({ status: 200, message: 'OK', data, traceId: TRACE_ID })
}

function failure(status: number) {
  return HttpResponse.json(
    { status, code: 'SOMETHING', message: '실패', traceId: TRACE_ID },
    { status },
  )
}

describe('send 요청 기록', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('성공 응답의 메서드·경로·상태·traceId를 남기고 쿼리는 뺀다', async () => {
    enableDemoMode()
    mockServer.use(http.get('/api/v1/probe', () => success({ value: 1 })))

    await send({ method: 'GET', path: '/probe?secret=1', schema })

    expect(entries()).toHaveLength(1)
    expect(entries()[0]).toMatchObject({
      method: 'GET',
      path: '/api/v1/probe',
      status: 200,
      traceId: TRACE_ID,
    })
    expect(Number.isInteger(entries()[0].durationMs)).toBe(true)
  })

  it('실패 응답도 상태와 traceId를 남긴다', async () => {
    enableDemoMode()
    mockServer.use(http.get('/api/v1/probe', () => failure(409)))

    await send({ method: 'GET', path: '/probe', schema }).catch(() => undefined)

    expect(entries()[0]).toMatchObject({ status: 409, traceId: TRACE_ID })
  })

  it('응답을 받지 못하면 상태 0, traceId null로 남긴다', async () => {
    enableDemoMode()
    mockServer.use(http.get('/api/v1/probe', () => HttpResponse.error()))

    await send({ method: 'GET', path: '/probe', schema }).catch(() => undefined)

    expect(entries()[0]).toMatchObject({ status: 0, traceId: null })
  })

  it('본문을 끝까지 받지 못하면 상태는 남기고 traceId는 null이다', async () => {
    enableDemoMode()
    // MSW 스트림 오류는 fetch 단계에서 실패해 재현되지 않아 본문 읽기만 실패하는 응답을 직접 만든다.
    const response = new Response(null, { status: 200 })
    vi.spyOn(response, 'text').mockRejectedValue(new Error('끊김'))
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(response)

    await send({ method: 'GET', path: '/probe', schema }).catch(() => undefined)

    expect(entries()[0]).toMatchObject({ status: 200, traceId: null })
  })

  it('성공 응답이 스키마와 달라도 traceId와 함께 남긴다', async () => {
    enableDemoMode()
    mockServer.use(http.get('/api/v1/probe', () => success({ value: 'x' })))

    await send({ method: 'GET', path: '/probe', schema }).catch(() => undefined)

    expect(entries()[0]).toMatchObject({ status: 200, traceId: TRACE_ID })
  })

  it('취소된 요청은 남기지 않는다', async () => {
    enableDemoMode()
    mockServer.use(http.get('/api/v1/probe', () => success({ value: 1 })))
    const controller = new AbortController()
    const pending = send({ method: 'GET', path: '/probe', schema, signal: controller.signal })

    controller.abort()
    await pending.catch(() => undefined)

    expect(entries()).toEqual([])
  })

  it('시연 모드가 꺼져 있으면 남기지 않는다', async () => {
    mockServer.use(http.get('/api/v1/probe', () => success({ value: 1 })))

    await send({ method: 'GET', path: '/probe', schema })

    expect(entries()).toEqual([])
  })
})
