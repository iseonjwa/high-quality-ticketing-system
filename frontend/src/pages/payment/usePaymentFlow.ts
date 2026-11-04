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
// 멱등키는 결제 토큰 문자열마다 하나다. 서버는 토큰 원문까지 본문 비교에 넣으므로(PaymentRequestHash) 같은 토큰이면
// 실패(pgUnavailable·failed) 뒤 다시 눌러도 같은 키로 보내고, 예매 조회로 토큰이 바뀌면 새 키를 쓴다.
// 결제를 시작하면 { token, key }를 sessionStorage에 둔다. 새로고침하거나 뒤로 갔다 와서 화면을 다시 열 때
// 같은 토큰이면 저장한 키로 바로 이어서 보내 지금 결과를 받는다. 토큰이 다르면 이어 보내지 않고 지운다.
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
    if (keyRef.current === null || keyRef.current.token !== paymentToken) {
      keyRef.current = { token: paymentToken, key: newIdempotencyKey() }
    }
    savePaymentAttempt(reservationId, keyRef.current)
    const controller = new AbortController()
    controllerRef.current = controller
    runningRef.current = true
    void runPaymentFlow({
      reservationId,
      paymentToken,
      amount,
      idempotencyKey: keyRef.current.key,
      signal: controller.signal,
      onState: setState,
    }).then((outcome: PaymentFlowOutcome) => {
      if (controllerRef.current === controller) {
        runningRef.current = false
      }
      return outcome
    })
  }, [reservationId, paymentToken, amount])

  // 화면을 열 때 저장한 시도가 있으면 같은 키로 이어서 보낸다
  useEffect(() => {
    const stored = readPaymentAttempt(reservationId)
    if (stored === null) {
      return
    }
    if (stored.token === paymentToken) {
      keyRef.current = stored
      submit()
    } else {
      removePaymentAttempt(reservationId)
    }
  }, [reservationId, paymentToken, submit])

  useEffect(() => {
    if (SETTLED_STEPS.has(state.step)) {
      removePaymentAttempt(reservationId)
    }
  }, [state.step, reservationId])

  return { state, submit }
}
