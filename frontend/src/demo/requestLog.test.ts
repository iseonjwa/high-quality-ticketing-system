import { describe, expect, it } from 'vitest'
import { disableDemoMode, enableDemoMode } from '@/demo/demoMode'
import {
  recordRequest,
  REQUEST_LOG_LIMIT,
  requestLogStore,
  type RequestLogEntry,
} from '@/demo/requestLog'

function entry(path: string): Omit<RequestLogEntry, 'id'> {
  return { startedAt: 1000, method: 'GET', path, status: 200, durationMs: 5, traceId: null }
}

const entries = () => requestLogStore.getState().entries

describe('요청 기록', () => {
  it('시연 모드가 꺼져 있으면 기록하지 않는다', () => {
    recordRequest(entry('/api/v1/a'))

    expect(entries()).toEqual([])
  })

  it('최신이 앞에 오고 id가 1씩 늘어난다', () => {
    enableDemoMode()

    recordRequest(entry('/api/v1/a'))
    recordRequest(entry('/api/v1/b'))

    const [latest, previous] = entries()
    expect(latest.path).toBe('/api/v1/b')
    expect(previous.path).toBe('/api/v1/a')
    expect(latest.id).toBe(previous.id + 1)
  })

  it(`${REQUEST_LOG_LIMIT}건을 넘으면 오래된 것을 버린다`, () => {
    enableDemoMode()

    for (let i = 0; i < REQUEST_LOG_LIMIT + 5; i++) {
      recordRequest(entry(`/api/v1/${i}`))
    }

    expect(entries()).toHaveLength(REQUEST_LOG_LIMIT)
    expect(entries()[0].path).toBe(`/api/v1/${REQUEST_LOG_LIMIT + 4}`)
    expect(entries().at(-1)?.path).toBe('/api/v1/5')
  })

  it('시연 모드를 끄면 기록이 비워진다', () => {
    enableDemoMode()
    recordRequest(entry('/api/v1/a'))

    disableDemoMode()

    expect(entries()).toEqual([])
  })
})
