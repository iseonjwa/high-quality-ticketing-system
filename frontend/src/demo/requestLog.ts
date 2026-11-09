import { useStore } from 'zustand'
import { createStore } from 'zustand/vanilla'
import type { HttpMethod } from '@/api/transport'
import { demoModeStore, isDemoModeEnabled } from '@/demo/demoMode'

export interface RequestLogEntry {
  // 기록 순서대로 1씩 늘어나는 값(React key)
  id: number
  // 요청을 보낸 시각, epoch ms
  startedAt: number
  method: HttpMethod
  // `/api/v1/...` 전체 경로. 쿼리 문자열은 뺀다
  path: string
  // HTTP 상태. 응답을 받지 못했으면 0
  status: number
  durationMs: number
  traceId: string | null
}

export const REQUEST_LOG_LIMIT = 50

interface RequestLogState {
  // 최신이 앞이다
  entries: RequestLogEntry[]
}

// 메모리에만 둔다. 새로고침하면 사라지고 시연 모드를 끄면 비운다.
export const requestLogStore = createStore<RequestLogState>()(() => ({ entries: [] }))

let nextId = 1

// 기록한 항목의 id를 돌려준다. 시연 모드가 꺼져 있으면 남기지 않고 null이다
export function recordRequest(entry: Omit<RequestLogEntry, 'id'>): number | null {
  if (!isDemoModeEnabled()) {
    return null
  }
  const recorded: RequestLogEntry = { ...entry, id: nextId++ }
  requestLogStore.setState((state) => ({
    entries: [recorded, ...state.entries].slice(0, REQUEST_LOG_LIMIT),
  }))
  return recorded.id
}

// 이미 남긴 항목에 traceId를 채운다. 스트림 응답은 공통 응답 형식이 없어 첫 이벤트(meta)에서야 traceId를 안다
export function setRequestTraceId(id: number, traceId: string) {
  requestLogStore.setState((state) => ({
    entries: state.entries.map((entry) => (entry.id === id ? { ...entry, traceId } : entry)),
  }))
}

export function clearRequestLog() {
  requestLogStore.setState({ entries: [] })
}

export function useRequestLog(): RequestLogEntry[] {
  return useStore(requestLogStore, (state) => state.entries)
}

demoModeStore.subscribe((state, previous) => {
  if (previous.enabled && !state.enabled) {
    clearRequestLog()
  }
})
