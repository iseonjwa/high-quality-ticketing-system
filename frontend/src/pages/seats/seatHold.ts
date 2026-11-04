import { ApiError, isApiError, isOutcomeUnknown } from '@/api/apiError'
import { queryRetryDelay } from '@/api/queryClient'
import { holdSeats, type SeatHold } from '@/api/reservations'

// `[선택 완료]` 한 번의 선점 요청을 끝까지 처리한다(`docs/design/reservation/contracts.md`). React를 쓰지 않는다.
// 결과를 모르는 실패(응답 없음, 504)와 지나가는 장애(503, 429)는 같은 멱등키·같은 좌석으로 다시 보낸다.
// 서버가 같은 키 재요청에 처음 결과를 주므로(HELD, 이미 결제됐으면 CONFIRMED) 좌석이 두 번 잡히지 않는다.

export type HoldOutcome =
  // 201. HELD이거나 같은 키 재요청이 이미 결제된 CONFIRMED
  | { kind: 'held'; hold: SeatHold }
  // 409 SEAT_ALREADY_HELD. 화면이 좌석을 다시 조회해 고르게 한다(새 멱등키)
  | { kind: 'conflict' }
  // 403 ENTRY_TOKEN_INVALID. 저장한 토큰은 API 클라이언트가 이미 지웠다. 대기열로 돌아간다
  | { kind: 'entryExpired' }
  // 그 밖. 결과를 끝내 모르면 마지막 오류
  | { kind: 'failed'; error: unknown }
  // signal이 취소됐다. 화면이 떠났거나 다시 시도로 바뀐 것이라 보여 줄 결과가 없다
  | { kind: 'aborted' }

export interface SubmitHoldOptions {
  scheduleId: number
  seatIds: number[]
  idempotencyKey: string
  signal: AbortSignal
  // 결과를 모르는 동안 다시 보내기 직전에 부른다(화면이 "좌석을 확인하고 있어요"를 보인다)
  onRetrying?: (error: ApiError) => void
  // 테스트가 바꿔 끼운다. 기본은 setTimeout 기반이고 signal이 취소되면 바로 끝난다
  sleep?: (millis: number, signal: AbortSignal) => Promise<void>
  random?: () => number
}

// 처음 요청을 포함한 최대 횟수
const MAX_ATTEMPTS = 4

export async function submitHold(options: SubmitHoldOptions): Promise<HoldOutcome> {
  const { scheduleId, seatIds, idempotencyKey, signal, onRetrying } = options
  const { sleep = sleepUntil, random = Math.random } = options

  for (let attempt = 0; ; attempt += 1) {
    if (signal.aborted) {
      return { kind: 'aborted' }
    }
    try {
      const hold = await holdSeats(scheduleId, seatIds, idempotencyKey, { signal })
      return { kind: 'held', hold }
    } catch (error) {
      if (signal.aborted) {
        return { kind: 'aborted' }
      }
      if (isApiError(error, 'SEAT_ALREADY_HELD')) {
        return { kind: 'conflict' }
      }
      if (isApiError(error, 'ENTRY_TOKEN_INVALID')) {
        return { kind: 'entryExpired' }
      }
      if (!isRetryable(error) || attempt + 1 >= MAX_ATTEMPTS) {
        return { kind: 'failed', error }
      }
      onRetrying?.(error)
      await sleep(queryRetryDelay(attempt, error, random), signal)
    }
  }
}

// 결과를 모르는 실패(0, 504)와 지나가는 장애(503, 429)만 다시 보낸다. 400·409 등은 같은 답이 돌아온다
function isRetryable(error: unknown): error is ApiError {
  return (
    isOutcomeUnknown(error) ||
    (error instanceof ApiError && (error.status === 503 || error.status === 429))
  )
}

export function sleepUntil(millis: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal.aborted) {
      resolve()
      return
    }
    const finish = () => {
      clearTimeout(timer)
      signal.removeEventListener('abort', finish)
      resolve()
    }
    const timer = setTimeout(finish, millis)
    signal.addEventListener('abort', finish)
  })
}
