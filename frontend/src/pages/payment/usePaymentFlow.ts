import { useCallback, useEffect, useRef, useState } from 'react'
import { isApiError } from '@/api/apiError'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import { checkoutPayment } from '@/api/payments'
import { tossReturnUrl } from '@/app/paths'
import {
  readPaymentAttempt,
  removePaymentAttempt,
  removeTossCheckout,
  savePaymentAttempt,
  saveTossCheckout,
  type PaymentAttempt,
} from '@/auth/paymentAttempts'
import {
  runPaymentFlow,
  type PaymentFlowOutcome,
  type PaymentFlowViewState,
} from '@/pages/payment/paymentFlow'
import {
  isTossBuyerCancel,
  openTossPaymentWindow,
  tossErrorCode,
} from '@/pages/payment/tossPaymentWindow'

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
// 처음 누르면 서버의 PG 모드를 묻는다(checkout). MOCK이면 바로 승인을 보내고, TOSS면 토스 결제창을 열어 이동을 기다린다.
// 토스에서 돌아온 결과는 TossReturnPage가 시도로 저장하고, 이 화면을 다시 열 때 저장한 시도로 이어 보낸다.
// Note: 토스 모드에서는 결제창을 열기 전에 승인 시도(멱등키)를 저장하지 않는다. 결제창을 닫고 다시 열면
// paymentKey가 바뀌는데 같은 멱등키를 쓰면 409 IDEMPOTENCY_KEY_REUSED가 나므로, paymentKey가 정해진 뒤에만 키를 만든다.
export function usePaymentFlow({ reservationId, paymentToken, amount }: UsePaymentFlowOptions): {
  state: PaymentFlowViewState
  submit: () => void
} {
  const [state, setState] = useState<PaymentFlowViewState>({ step: 'ready' })
  const keyRef = useRef<PaymentAttempt | null>(null)
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
    const controller = new AbortController()
    controllerRef.current = controller
    runningRef.current = true

    function send(attempt: PaymentAttempt) {
      savePaymentAttempt(reservationId, attempt)
      void runPaymentFlow({
        reservationId,
        paymentToken: attempt.token,
        amount,
        idempotencyKey: attempt.key,
        clientPaymentRef: attempt.clientPaymentRef,
        signal: controller.signal,
        onState: setState,
      }).then((outcome: PaymentFlowOutcome) => {
        if (controllerRef.current === controller) {
          runningRef.current = false
        }
        return outcome
      })
    }

    // 저장한 시도가 있으면 checkout을 부르지 않고 그 토큰·키(·paymentKey)로 다시 보낸다.
    // 토스 모드에서 승인 응답을 못 받은 뒤 다시 누르면 결제창을 다시 열지 않고 같은 paymentKey로 보내야 한다
    if (keyRef.current !== null) {
      send(keyRef.current)
      return
    }

    // 멈춘 단계에서는 다시 누를 수 있게 진행 중 표시를 푼다
    function stop(next: PaymentFlowViewState) {
      runningRef.current = false
      setState(next)
    }

    async function prepare() {
      setState({ step: 'preparing' })
      let checkout
      try {
        checkout = await checkoutPayment(paymentToken, { signal: controller.signal })
      } catch (error) {
        if (controller.signal.aborted) {
          return
        }
        stop(
          isApiError(error, 'PAYMENT_TOKEN_INVALID') && error.status === 403
            ? { step: 'tokenExpired' }
            : { step: 'failed', error },
        )
        return
      }
      if (controller.signal.aborted) {
        return
      }
      // 서버가 준 금액이 화면 금액과 다르면 결제창을 열지 않는다
      if (checkout.amount !== amount) {
        stop({
          step: 'failed',
          error: new Error('결제 금액이 화면과 달라 결제를 시작하지 않았어요'),
        })
        return
      }
      if (checkout.provider === 'MOCK') {
        const attempt = { token: paymentToken, key: newIdempotencyKey() }
        keyRef.current = attempt
        send(attempt)
        return
      }
      saveTossCheckout(reservationId, {
        token: paymentToken,
        orderId: checkout.orderId,
        amount: checkout.amount,
      })
      setState({ step: 'redirecting' })
      try {
        await openTossPaymentWindow({
          clientKey: checkout.clientKey,
          orderId: checkout.orderId,
          amount: checkout.amount,
          orderName: checkout.orderName,
          successUrl: tossReturnUrl(reservationId, 'success'),
          failUrl: tossReturnUrl(reservationId, 'fail'),
        })
        // 이행은 결제창을 열었다는 뜻이다. 돌아오는 주소로 이동할 때까지 redirecting을 둔다
      } catch (error) {
        if (controller.signal.aborted) {
          return
        }
        removeTossCheckout(reservationId)
        const code = tossErrorCode(error)
        stop(isTossBuyerCancel(code) ? { step: 'tossCanceled' } : { step: 'tossFailed', code })
      }
    }
    void prepare()
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

  // Note: 모바일 결제창은 페이지를 떠난다. 뒤로 가기로 돌아오면 브라우저가 떠나기 전 화면(bfcache)을 그대로 되살려
  // "결제창 여는 중"에 멈춘다. 그때는 진행 중이던 호출을 버리고 다시 누를 수 있게 되돌린다
  useEffect(() => {
    if (state.step !== 'redirecting') {
      return
    }
    function onPageShow(event: PageTransitionEvent) {
      if (!event.persisted) {
        return
      }
      controllerRef.current?.abort()
      controllerRef.current = null
      runningRef.current = false
      setState({ step: 'ready' })
    }
    window.addEventListener('pageshow', onPageShow)
    return () => window.removeEventListener('pageshow', onPageShow)
  }, [state.step])

  return { state, submit }
}
