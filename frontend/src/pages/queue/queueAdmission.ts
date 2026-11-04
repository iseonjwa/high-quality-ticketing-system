import { ApiError } from '@/api/apiError'
import { queryRetryDelay } from '@/api/queryClient'
import { getQueuePosition, issueEntryToken, joinQueue, type QueuePosition } from '@/api/queue'
import { isEntryTokenFresh, readEntryToken } from '@/auth/entryTokens'

// 대기열 입장 흐름(SA 11-5, ADR 0031): 진입 → 순번 폴링 → READY면 토큰 → 입장 완료.
// 화면 상태와 그 상태를 바꾸는 비동기 실행기만 둔다. React를 쓰지 않아 훅이 감싼다.

export type QueueStep =
  // 진입 요청 중(처음, 다시 줄 서기)
  | 'joining'
  // 순번을 받았고 다음 조회를 기다리는 중
  | 'waiting'
  // READY를 받아 토큰을 요청하는 중
  | 'admitting'
  // 토큰을 저장했다. 화면이 좌석으로 옮긴다
  | 'admitted'
  // 404 QUEUE_NOT_JOINED. 다시 줄 서기를 보인다
  | 'expired'
  // 409 SALE_NOT_OPEN
  | 'notOpen'
  // 그 밖의 오류. error에 원인
  | 'failed'

export interface QueueAdmissionState {
  step: QueueStep
  // 마지막으로 받은 WAITING. joining·admitting·재시도 중에도 남아 화면이 순번을 계속 보인다
  waiting: { position: number; estimatedWaitSeconds: number; initialPosition: number } | null
  // 자동으로 다시 보내는 중인 오류. 다음 요청이 성공하면 null
  connectionError: ApiError | null
  // step이 failed일 때의 원인
  error: unknown
}

export interface RunQueueAdmissionOptions {
  scheduleId: number
  signal: AbortSignal
  onChange: (state: QueueAdmissionState) => void
  // 테스트가 바꿔 끼운다. 기본은 setTimeout 기반이고 signal이 취소되면 바로 끝난다
  sleep?: (millis: number, signal: AbortSignal) => Promise<void>
  random?: () => number
}

export const INITIAL_QUEUE_ADMISSION_STATE: QueueAdmissionState = {
  step: 'joining',
  waiting: null,
  connectionError: null,
  error: null,
}

// SA 9-4의 최소 조회 간격(폴링 부하 상한). 토큰 요청이 409 QUEUE_NOT_READY면 이만큼 기다렸다 순번 조회로 돌아가고,
// Retry-After 없이 다시 보낼 때도 이보다 짧게 기다리지 않는다
const MIN_POLL_MILLIS = 3000

type Request = 'join' | 'poll' | 'token'

export async function runQueueAdmission(options: RunQueueAdmissionOptions): Promise<void> {
  const { scheduleId, signal, onChange, sleep = sleepUntil, random = Math.random } = options
  let state: QueueAdmissionState = { ...INITIAL_QUEUE_ADMISSION_STATE }

  function update(patch: Partial<QueueAdmissionState>) {
    state = { ...state, ...patch }
    if (!signal.aborted) {
      onChange({ ...state })
    }
  }

  const stored = readEntryToken(scheduleId)
  if (stored !== null && isEntryTokenFresh(stored)) {
    update({ step: 'admitted' })
    return
  }

  update({})
  let request: Request = 'join'
  let failures = 0

  while (!signal.aborted) {
    let position: QueuePosition | null
    try {
      position = await send(request, scheduleId, signal)
    } catch (error) {
      if (signal.aborted) {
        return
      }
      if (isRetryable(error)) {
        update({ connectionError: error })
        await sleep(queryRetryDelay(failures, error, random, MIN_POLL_MILLIS), signal)
        failures += 1
        continue
      }
      const next = settle(request, error)
      update(next.patch)
      if (next.retry === undefined) {
        return
      }
      failures = 0
      request = next.retry.request
      await sleep(next.retry.afterMillis, signal)
      continue
    }

    failures = 0
    if (position === null) {
      update({ step: 'admitted', connectionError: null })
      return
    }
    if (position.status === 'READY') {
      update({ step: 'admitting', connectionError: null })
      request = 'token'
      continue
    }
    update({
      step: 'waiting',
      connectionError: null,
      waiting: {
        position: position.position,
        estimatedWaitSeconds: position.estimatedWaitSeconds,
        initialPosition: state.waiting?.initialPosition ?? position.position,
      },
    })
    request = 'poll'
    // 서버가 jitter까지 섞은 값이라 고치지 않는다(ADR 0031 결정 10)
    await sleep(position.nextPollAfterMillis, signal)
  }
}

// 토큰 요청은 성공하면 null을 준다(issueEntryToken이 저장까지 마쳤다)
async function send(
  request: Request,
  scheduleId: number,
  signal: AbortSignal,
): Promise<QueuePosition | null> {
  switch (request) {
    case 'join':
      return joinQueue(scheduleId, { signal })
    case 'poll':
      return getQueuePosition(scheduleId, { signal })
    case 'token':
      await issueEntryToken(scheduleId, { signal })
      return null
  }
}

// 응답을 못 받았거나(0) 지나가는 장애(429, 502·503·504)면 같은 요청을 다시 보낸다. 세 요청 모두 다시 보내도
// 결과가 같다(ADR 0031). 500은 다시 보내도 같은 결과일 가능성이 커서 끝없이 기다리게 하지 않고 문의 번호와 함께 실패로 보인다
const RETRYABLE_STATUSES = new Set([0, 429, 502, 503, 504])

function isRetryable(error: unknown): error is ApiError {
  return error instanceof ApiError && RETRYABLE_STATUSES.has(error.status)
}

interface Settled {
  patch: Partial<QueueAdmissionState>
  retry?: { request: Request; afterMillis: number }
}

function settle(request: Request, error: unknown): Settled {
  if (error instanceof ApiError) {
    if (error.status === 404 && error.code === 'QUEUE_NOT_JOINED' && request !== 'join') {
      return { patch: { step: 'expired', connectionError: null } }
    }
    if (error.status === 409 && error.code === 'SALE_NOT_OPEN' && request === 'join') {
      return { patch: { step: 'notOpen', connectionError: null } }
    }
    if (error.status === 409 && error.code === 'QUEUE_NOT_READY' && request === 'token') {
      return {
        patch: { step: 'waiting', connectionError: null },
        retry: { request: 'poll', afterMillis: MIN_POLL_MILLIS },
      }
    }
  }
  return { patch: { step: 'failed', connectionError: null, error } }
}

function sleepUntil(millis: number, signal: AbortSignal): Promise<void> {
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
