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

export function recordRequest(entry: Omit<RequestLogEntry, 'id'>) {
  if (!isDemoModeEnabled()) {
    return
  }
  const recorded: RequestLogEntry = { ...entry, id: nextId++ }
  requestLogStore.setState((state) => ({
    entries: [recorded, ...state.entries].slice(0, REQUEST_LOG_LIMIT),
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
