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

// minBaseMillis는 서버가 Retry-After를 주지 않았을 때 지킬 최소 간격이다(대기열은 SA 9-4의 최소 조회 간격 3초)
export function queryRetryDelay(
  attemptIndex: number,
  error: unknown,
  random: () => number = Math.random,
  minBaseMillis = 0,
): number {
  const baseMillis =
    error instanceof ApiError && error.retryAfterSeconds !== null
      ? error.retryAfterSeconds * 1000
      : Math.max(
          minBaseMillis,
          Math.min(BASE_RETRY_DELAY_MILLIS * 2 ** attemptIndex, MAX_RETRY_DELAY_MILLIS),
        )
  return Math.round(baseMillis * (1 + random() * RETRY_JITTER_RATIO))
}

// 로그인한 사용자와 상관없는 공개 조회의 키 첫 칸(concertQueryKeys). 로그아웃해도 그대로 둔다
const PUBLIC_QUERY_ROOTS: readonly unknown[] = ['concerts']

// 로그아웃 뒤 이전 사용자의 조회 결과를 비운다. 공개 조회는 남겨 처음 화면의 공연 목록이 깜빡이지 않게 한다.
// Note: 지우지(removeQueries) 않고 초기화한다. 화면에 붙은 사용자 조회가 있으면 지울 때 캐시와 끊겨 이전 데이터가
// 화면에 남지만, 초기화는 데이터를 비운다. 사용자 조회를 새로 만들면 키 첫 칸이 위 목록에 없게 둔다.
export function resetUserQueries(queryClient: QueryClient): Promise<void> {
  return queryClient.resetQueries({
    predicate: (query) => !PUBLIC_QUERY_ROOTS.includes(query.queryKey[0]),
  })
}
