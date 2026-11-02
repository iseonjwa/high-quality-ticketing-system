import { QueryClient } from '@tanstack/react-query'
import { ApiError } from '@/api/apiError'

const MAX_QUERY_RETRIES = 2
const BASE_RETRY_DELAY_MILLIS = 1000
const MAX_RETRY_DELAY_MILLIS = 8000
// SA 11-5: 장애에서 회복되는 순간 재시도가 한꺼번에 몰리지 않게 0~+20%를 더한다
const RETRY_JITTER_RATIO = 0.2

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: shouldRetryQuery, retryDelay: queryRetryDelay },
    },
  })
}

// 조회만 자동으로 다시 시도한다. 변경 요청(선점·결제)은 화면이 같은 멱등키로 직접 다시 보낸다.
// 4xx는 다시 보내도 같은 답이고(403 입장 만료, 409 이선좌 등), 429는 다시 보내면 제한이 길어진다.
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= MAX_QUERY_RETRIES || !(error instanceof ApiError)) {
    return false
  }
  return error.status === 0 || error.status >= 500
}

export function queryRetryDelay(
  attemptIndex: number,
  error: unknown,
  random: () => number = Math.random,
): number {
  const baseMillis =
    error instanceof ApiError && error.retryAfterSeconds !== null
      ? error.retryAfterSeconds * 1000
      : Math.min(BASE_RETRY_DELAY_MILLIS * 2 ** attemptIndex, MAX_RETRY_DELAY_MILLIS)
  return Math.round(baseMillis * (1 + random() * RETRY_JITTER_RATIO))
}
