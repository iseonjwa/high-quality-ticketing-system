import { ApiError, isOutcomeUnknown } from '@/api/apiError'
import { queryRetryDelay } from '@/api/queryClient'
import { approvePayment, getPayment } from '@/api/payments'
import { getReservation, type ReservationDetail } from '@/api/reservations'

// 결제 한 번을 끝까지 처리하는 실행기(`docs/design/payment/api.md`, design-system 5·6절). React를 쓰지 않아 훅이 감싼다.
// 승인 요청 → (202면) 결제 조회로 확인 → APPROVED면 예매 조회로 확정 확인 → 끝.
// APPROVED는 돈이 승인됐다는 뜻일 뿐 예매 확정이 아니다. 예매가 CONFIRMED가 돼야 결과 화면으로 간다.

export type PaymentFlowState =
  // 승인 요청을 보내고 응답을 기다리는 중
  | { step: 'paying' }
  // 202(REQUESTED·UNKNOWN) 또는 응답 없음·504. 결제 결과를 확인하는 중. 결제 번호를 아직 모르면 null
  | { step: 'checking'; paymentId: number | null }
  // 결제는 승인됐고 예매가 CONFIRMED가 되길 기다리는 중
  | { step: 'confirming'; paymentId: number }
  // 예매 확정. 화면이 결과 화면으로 간다
  | { step: 'confirmed'; reservation: ReservationDetail }
  // 결제 거절. 승인 응답에만 사유가 있고 결제 조회에는 없다
  | { step: 'declined'; reasonCode: string | null }
  // 환불됨. 승인 뒤 예매가 EXPIRED가 된 경우도 같다(환불 예정)
  | { step: 'refunded' }
  // NEEDS_MANUAL_CHECK(운영자 확인 필요) 또는 409 PAYMENT_ALREADY_EXISTS
  | { step: 'manualCheck' }
  // 409 PAYMENT_ALREADY_CLOSED. 이 선점의 결제는 끝났다
  | { step: 'closed' }
  // 결제 토큰이 못 쓰게 됐다. 403 PAYMENT_TOKEN_INVALID
  | { step: 'tokenExpired' }
  // 503 PG_UNAVAILABLE. 결제 기록이 남지 않아 같은 키로 다시 누르면 된다
  | { step: 'pgUnavailable' }
  // 확정 확인이 30초를 넘겼거나(approved), 결과 모름 재전송·결제 확인이 한도에 닿았다(approved false). 결과는 나중에 예매 내역에서 확인한다
  | { step: 'slowConfirm'; approved: boolean }
  // 승인 뒤 예매가 RELEASED·CANCELED가 됐다
  | { step: 'notConfirmed' }
  // 그 밖의 오류
  | { step: 'failed'; error: unknown }

// 화면이 쓰는 상태. 아직 누르기 전이면 ready다
export type PaymentFlowViewState =
  | PaymentFlowState
  | { step: 'ready' }
  // 결제 준비(checkout) 응답을 기다리는 중
  | { step: 'preparing' }
  // 토스 결제창을 열었고 이동을 기다리는 중
  | { step: 'redirecting' }
  // 구매자가 토스 결제창을 닫았다. 다시 누를 수 있다
  | { step: 'tossCanceled' }
  // 토스 결제창이 오류로 끝났다. 다시 누를 수 있다
  | { step: 'tossFailed'; code: string | null }

// 실행기가 돌려주는 값. 마지막 단계이거나, signal이 취소돼 멈춘 aborted다
export type PaymentFlowOutcome = PaymentFlowState | { step: 'aborted' }

export interface RunPaymentFlowOptions {
  reservationId: number
  paymentToken: string
  amount: number
  idempotencyKey: string
  // 토스 paymentKey. 있으면 승인 본문에 실어 재전송에도 같은 본문을 보낸다
  clientPaymentRef?: string
  // 토스 모드의 Mock 건너뛰기. 있으면 승인 본문에 싣는다
  pgProvider?: 'MOCK'
  signal: AbortSignal
  onState: (state: PaymentFlowState) => void
  // 테스트가 바꿔 끼운다. 기본은 setTimeout 기반이고 signal이 취소되면 바로 끝난다
  sleep?: (millis: number, signal: AbortSignal) => Promise<void>
  random?: () => number
}

// 승인 요청을 결과 모름·일시 장애로 다시 보내는 최대 횟수(처음 요청 제외)
const MAX_RESENDS = 8
// 503 PG_UNAVAILABLE 자동 재시도(ISE-242). 누름 한 번당 한 번만 다시 보낸다. 기다림은 1~2초 무작위이고,
// 응답에 Retry-After가 있으면 그 값을 쓰되 3초를 넘기지 않는다
const PG_UNAVAILABLE_BASE_MILLIS = 1000
const PG_UNAVAILABLE_JITTER_MILLIS = 1000
const PG_UNAVAILABLE_MAX_MILLIS = 3000
// 지연의 최소 기본값. Retry-After가 있으면 그것이 우선이다
const MIN_RETRY_MILLIS = 3000
// 202에 간격이 없던 경우(결과 모름 재전송 뒤 곧바로 조회로 넘어가는 경우)의 기본 조회 간격
const DEFAULT_POLL_MILLIS = 10_000
const CONFIRM_INTERVAL_MILLIS = 1000
const CONFIRM_LIMIT_MILLIS = 30_000
// 결제 결과를 확인하는 단계(checking)에 머무는 최대 시간. 기다린 시간의 합으로 센다.
// 서버의 재확인 스케줄은 22분까지 가지만 화면이 그동안 계속 조회하지 않는다
const CHECK_LIMIT_MILLIS = 120_000

export async function runPaymentFlow(options: RunPaymentFlowOptions): Promise<PaymentFlowOutcome> {
  const { signal, onState, sleep = sleepUntil, random = Math.random } = options

  const aborted: PaymentFlowOutcome = { step: 'aborted' }

  function emit(state: PaymentFlowState): PaymentFlowState {
    if (!signal.aborted) {
      onState(state)
    }
    return state
  }

  emit({ step: 'paying' })

  // 1. 승인 요청. 결과를 모르면 같은 키·같은 본문으로 다시 보낸다
  let paymentId: number | undefined
  let pollMillis = DEFAULT_POLL_MILLIS
  let resends = 0
  // 503 PG_UNAVAILABLE를 자동으로 다시 보냈는지. 이 실행(사용자의 누름 한 번)에서 한 번만 보내고, 결과 모름 재전송 한도와 따로 센다
  let pgUnavailableRetried = false
  let approved = false
  // checking이 처음 된 때부터 기다린 시간의 합
  let checking = false
  let checkWaited = 0
  async function waitWhileChecking(millis: number) {
    await sleep(millis, signal)
    if (checking) {
      checkWaited += millis
    }
  }
  for (;;) {
    if (signal.aborted) {
      return aborted
    }
    try {
      const result = await approvePayment(
        {
          paymentToken: options.paymentToken,
          amount: options.amount,
          method: 'CARD',
          clientPaymentRef: options.clientPaymentRef,
          pgProvider: options.pgProvider,
        },
        options.idempotencyKey,
        { signal },
      )
      if (signal.aborted) {
        return aborted
      }
      paymentId = result.paymentId
      if (result.status === 'APPROVED') {
        approved = true
      } else if (result.status === 'DECLINED') {
        return emit({ step: 'declined', reasonCode: result.reasonCode })
      } else if (result.status === 'REFUNDED') {
        return emit({ step: 'refunded' })
      } else {
        pollMillis = result.nextPollAfterSeconds * 1000
        checking = true
        emit({ step: 'checking', paymentId })
      }
      break
    } catch (error) {
      if (signal.aborted) {
        return aborted
      }
      // 서버는 이 503에서 결제 행도 멱등 기록도 만들지 않으므로(ADR 0018) 같은 키·같은 본문으로 다시 보내도 안전하다.
      // 한 번만 보내는 이유: 차단기가 열린 장애(10초)는 다시 보내도 또 503이고, 여러 번·지연 없이 보내면 포화 때 요청이 더 몰린다.
      // 무작위 지연은 같은 순간에 거절된 사용자들이 같은 순간에 다시 몰리지 않게 한다. 기다리는 동안 단계는 paying 그대로다
      if (isPgUnavailable(error) && !pgUnavailableRetried) {
        pgUnavailableRetried = true
        await sleep(pgUnavailableRetryDelay(error, random), signal)
        continue
      }
      const settled = settleApproveError(error)
      if (settled !== null) {
        return emit(settled)
      }
      // 결과를 모르거나 지나가는 장애다. 한도 안에서 같은 요청을 다시 보낸다
      if (resends >= MAX_RESENDS) {
        return emit({ step: 'slowConfirm', approved: false })
      }
      if (isOutcomeUnknown(error)) {
        checking = true
        emit({ step: 'checking', paymentId: null })
      }
      await waitWhileChecking(queryRetryDelay(resends, error, random, MIN_RETRY_MILLIS))
      resends += 1
    }
  }

  // 2. 승인 응답이 202였다면 결제 조회로 결과를 확인한다
  if (!approved) {
    let failures = 0
    // 다음 조회까지 기다릴 시간. 처음은 서버가 준 간격, 조회가 실패하면 다시 시도 지연
    let wait = pollMillis
    for (;;) {
      // 결제 결과를 오래 확인하지 못했다. 서버는 계속 확인하지만 화면은 여기서 멈춘다
      if (checkWaited >= CHECK_LIMIT_MILLIS) {
        return emit({ step: 'slowConfirm', approved: false })
      }
      await waitWhileChecking(wait)
      if (signal.aborted) {
        return aborted
      }
      try {
        const detail = await getPayment(paymentId!, { signal })
        if (signal.aborted) {
          return aborted
        }
        failures = 0
        wait = pollMillis
        if (detail.status === 'APPROVED') {
          break
        }
        if (detail.status === 'DECLINED') {
          return emit({ step: 'declined', reasonCode: null })
        }
        if (detail.status === 'REFUNDED') {
          return emit({ step: 'refunded' })
        }
        if (detail.status === 'NEEDS_MANUAL_CHECK') {
          return emit({ step: 'manualCheck' })
        }
        // REQUESTED·UNKNOWN은 계속 확인 중이다. 마지막으로 받은 간격으로 다시 본다
      } catch (error) {
        if (signal.aborted) {
          return aborted
        }
        // 승인 요청이 이미 서버에 닿았다. 조회 오류는 어떤 것이든 결과를 모를 뿐이라 다시 조회한다
        wait = queryRetryDelay(failures, error, random, MIN_RETRY_MILLIS)
        failures += 1
      }
    }
  }

  // 3. 승인됐다. 예매가 확정될 때까지 1초마다 예매를 조회한다
  emit({ step: 'confirming', paymentId: paymentId! })
  let waited = 0
  let failures = 0
  for (;;) {
    if (signal.aborted) {
      return aborted
    }
    try {
      const reservation = await getReservation(options.reservationId, { signal })
      if (signal.aborted) {
        return aborted
      }
      failures = 0
      if (reservation.status === 'CONFIRMED') {
        return emit({ step: 'confirmed', reservation })
      }
      if (reservation.status === 'EXPIRED') {
        return emit({ step: 'refunded' })
      }
      if (reservation.status === 'RELEASED' || reservation.status === 'CANCELED') {
        return emit({ step: 'notConfirmed' })
      }
      // HELD: 아직 반영 중이다
      if (waited >= CONFIRM_LIMIT_MILLIS) {
        return emit({ step: 'slowConfirm', approved: true })
      }
      await sleep(CONFIRM_INTERVAL_MILLIS, signal)
      waited += CONFIRM_INTERVAL_MILLIS
    } catch (error) {
      if (signal.aborted) {
        return aborted
      }
      // 승인된 뒤에는 오류가 무엇이든 failed로 끝내지 않는다. 결제는 이미 됐으니 한도까지 다시 조회한다
      if (waited >= CONFIRM_LIMIT_MILLIS) {
        return emit({ step: 'slowConfirm', approved: true })
      }
      const delay = queryRetryDelay(failures, error, random, MIN_RETRY_MILLIS)
      await sleep(delay, signal)
      waited += delay
      failures += 1
    }
  }
}

function isPgUnavailable(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 503 && error.code === 'PG_UNAVAILABLE'
}

function pgUnavailableRetryDelay(error: ApiError, random: () => number): number {
  if (error.retryAfterSeconds !== null) {
    return Math.min(error.retryAfterSeconds * 1000, PG_UNAVAILABLE_MAX_MILLIS)
  }
  return Math.round(PG_UNAVAILABLE_BASE_MILLIS + random() * PG_UNAVAILABLE_JITTER_MILLIS)
}

// 다시 보내지 않고 끝나는 승인 오류는 마지막 단계로 바꾼다. 다시 보낼 오류면 null
function settleApproveError(error: unknown): PaymentFlowState | null {
  if (!(error instanceof ApiError)) {
    return { step: 'failed', error }
  }
  if (error.status === 503 && error.code === 'PG_UNAVAILABLE') {
    return { step: 'pgUnavailable' }
  }
  if (error.status === 403 && error.code === 'PAYMENT_TOKEN_INVALID') {
    return { step: 'tokenExpired' }
  }
  if (error.status === 409) {
    switch (error.code) {
      case 'PAYMENT_ALREADY_EXISTS':
        return { step: 'manualCheck' }
      case 'PAYMENT_ALREADY_CLOSED':
        return { step: 'closed' }
    }
  }
  if (isOutcomeUnknown(error) || RETRYABLE_STATUSES.has(error.status)) {
    return null
  }
  return { step: 'failed', error }
}

// 응답을 못 받았거나(0) 지나가는 장애(429, 502·503·504)면 같은 승인 요청을 다시 보낸다
const RETRYABLE_STATUSES = new Set([0, 429, 502, 503, 504])

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
