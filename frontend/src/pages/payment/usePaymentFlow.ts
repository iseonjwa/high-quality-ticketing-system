import { useCallback, useEffect, useRef, useState } from 'react'
import { newIdempotencyKey } from '@/api/idempotencyKey'
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

// 결제 흐름을 화면에 잇는다. submit이 [결제하기]다.
// 멱등키는 결제 토큰 문자열마다 하나다. 서버는 토큰 원문까지 본문 비교에 넣으므로(PaymentRequestHash) 같은 토큰이면
// 실패(pgUnavailable·failed) 뒤 다시 눌러도 같은 키로 보내고, 예매 조회로 토큰이 바뀌면 새 키를 쓴다.
export function usePaymentFlow({ reservationId, paymentToken, amount }: UsePaymentFlowOptions): {
  state: PaymentFlowViewState
  submit: () => void
} {
  const [state, setState] = useState<PaymentFlowViewState>({ step: 'ready' })
  const keyRef = useRef<{ token: string; key: string } | null>(null)
  const controllerRef = useRef<AbortController | null>(null)
  const runningRef = useRef(false)

  useEffect(() => () => controllerRef.current?.abort(), [])

  const submit = useCallback(() => {
    // 진행 중에 또 누르면 같은 요청을 한 번 더 보내지 않는다
    if (runningRef.current) {
      return
    }
    if (keyRef.current === null || keyRef.current.token !== paymentToken) {
      keyRef.current = { token: paymentToken, key: newIdempotencyKey() }
    }
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

  return { state, submit }
}
