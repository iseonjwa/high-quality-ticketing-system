import { useCallback, useEffect, useRef, useState } from 'react'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import {
  readPaymentAttempt,
  removePaymentAttempt,
  savePaymentAttempt,
} from '@/auth/paymentAttempts'
import {
  runPaymentFlow,
  type PaymentFlowOutcome,
  type PaymentFlowViewState,
} from '@/pages/payment/paymentFlow'

// 화면이 단계를 가를 때 쓰는 이름. 누르기 전의 ready를 포함한다
export type { PaymentFlowViewState as PaymentFlowState }

export interface UsePaymentFlowOptions {
  reservationId: number
  paymentToken: string
  amount: number
}

// 끝난 단계. 서버가 이 선점의 결제를 마무리했거나 더 보낼 수 없어 저장한 시도를 지운다.
// pgUnavailable·failed·slowConfirm은 같은 키로 다시 보낼 수 있어 남긴다
const SETTLED_STEPS = new Set<PaymentFlowViewState['step']>([
  'confirmed',
  'declined',
  'closed',
  'refunded',
  'notConfirmed',
  'manualCheck',
  'tokenExpired',
])

// 결제 흐름을 화면에 잇는다. submit이 [결제하기]다.
// 결제를 시작하면 { token, key }를 sessionStorage에 두고, 그 뒤로는 이 예매의 결제를 늘 그 토큰·키로 보낸다.
// 서버는 토큰 원문까지 본문 비교에 넣으므로(PaymentRequestHash) 토큰과 키를 함께 지켜야 같은 요청이 된다.
// 화면을 다시 열 때(새로고침, 뒤로 갔다 오기, 예매 상세의 [결제하기]) 저장한 시도가 있으면 화면이 받은 토큰과
// 달라도(예매 조회는 조회마다 토큰을 새로 서명한다) 저장한 토큰·키로 바로 이어서 보내 지금 결과를 받는다.
// 서버는 같은 키 재요청이면 토큰이 만료됐어도 결과를 준다(결제 API 1절 3단계가 토큰 검증보다 먼저다).
// Note: 처음에는 토큰이 다르면 저장한 시도를 지웠다. 그러면 예매 상세에서 다시 열 때 새 키로 결제 버튼이 다시 보였다
// (ISE-117 셀프 리뷰)
export function usePaymentFlow({ reservationId, paymentToken, amount }: UsePaymentFlowOptions): {
  state: PaymentFlowViewState
  submit: () => void
} {
  const [state, setState] = useState<PaymentFlowViewState>({ step: 'ready' })
  const keyRef = useRef<{ token: string; key: string } | null>(null)
  const controllerRef = useRef<AbortController | null>(null)
  const runningRef = useRef(false)

  // Note: StrictMode는 마운트 직후 정리했다가 다시 마운트한다. 정리에서 진행 중 표시도 풀어야
  // 다시 마운트한 쪽의 이어 보내기가 "진행 중"으로 무시되지 않는다
  useEffect(
    () => () => {
      controllerRef.current?.abort()
      controllerRef.current = null
      runningRef.current = false
    },
    [],
  )

  const submit = useCallback(() => {
    // 진행 중에 또 누르면 같은 요청을 한 번 더 보내지 않는다
    if (runningRef.current) {
      return
    }
    if (keyRef.current === null) {
      keyRef.current = { token: paymentToken, key: newIdempotencyKey() }
    }
    const attempt = keyRef.current
    savePaymentAttempt(reservationId, attempt)
    const controller = new AbortController()
    controllerRef.current = controller
    runningRef.current = true
    void runPaymentFlow({
      reservationId,
      paymentToken: attempt.token,
      amount,
      idempotencyKey: attempt.key,
      signal: controller.signal,
      onState: setState,
    }).then((outcome: PaymentFlowOutcome) => {
      if (controllerRef.current === controller) {
        runningRef.current = false
      }
      return outcome
    })
  }, [reservationId, paymentToken, amount])

  // 화면을 열 때 저장한 시도가 있으면 그 토큰·키로 이어서 보낸다
  useEffect(() => {
    const stored = readPaymentAttempt(reservationId)
    if (stored === null) {
      return
    }
    keyRef.current = stored
    submit()
  }, [reservationId, submit])

  useEffect(() => {
    if (SETTLED_STEPS.has(state.step)) {
      removePaymentAttempt(reservationId)
      keyRef.current = null
    }
  }, [state.step, reservationId])

  return { state, submit }
}
