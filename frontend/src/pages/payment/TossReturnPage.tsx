import { useEffect, useRef } from 'react'
import { Navigate, useNavigate, useParams, useSearchParams } from 'react-router'
import { paths, paymentPath } from '@/app/paths'
import {
  readPaymentAttempt,
  readTossCheckout,
  removeTossCheckout,
  savePaymentAttempt,
} from '@/auth/paymentAttempts'
import { readRouteId } from '@/lib/routeId'
import { PaymentSkeleton } from '@/pages/payment/PaymentSkeleton'
import { resolveTossReturn, tossReturnState } from '@/pages/payment/tossReturn'

// 토스 결제창이 돌려보내는 주소(`/reservations/{id}/payment/toss/{success|fail}`). 판단은 resolveTossReturn이 하고
// 이 화면은 저장소를 고친 뒤 결제 화면으로 바꿔 들어가기만 한다. 승인은 결제 화면이 저장한 시도로 이어서 보낸다.
export function TossReturnPage() {
  const params = useParams()
  const reservationId = readRouteId(params.reservationId)
  const result = params.result
  if (reservationId === null || (result !== 'success' && result !== 'fail')) {
    return <Navigate to={paths.myReservations} replace />
  }
  return <TossReturn reservationId={reservationId} result={result} />
}

function TossReturn({
  reservationId,
  result,
}: {
  reservationId: number
  result: 'success' | 'fail'
}) {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  // Note: StrictMode는 효과를 두 번 돌린다. 한 번만 판단한다
  const handledRef = useRef(false)

  useEffect(() => {
    if (handledRef.current) {
      return
    }
    handledRef.current = true
    const decision = resolveTossReturn({
      result,
      params: searchParams,
      checkout: readTossCheckout(reservationId),
      stored: readPaymentAttempt(reservationId),
    })
    let state: ReturnType<typeof tossReturnState> | undefined
    if (decision.kind === 'approve') {
      savePaymentAttempt(reservationId, decision.attempt)
      removeTossCheckout(reservationId)
    } else if (decision.kind !== 'resume') {
      removeTossCheckout(reservationId)
      state = tossReturnState(decision)
    }
    void navigate(paymentPath(reservationId), { replace: true, state })
  }, [navigate, reservationId, result, searchParams])

  return <PaymentSkeleton label="결제 결과를 확인하고 있어요" />
}
