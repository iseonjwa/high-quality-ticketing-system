import { useEffect, useRef, useState } from 'react'
import { isOutcomeUnknown } from '@/api/apiError'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import { submitHold, type HoldOutcome } from '@/pages/seats/seatHold'

// sending: 첫 요청을 기다린다. retrying: 결과를 모르거나 일시 장애라 같은 키로 다시 보내는 중이다
export type HoldRequestState = 'idle' | 'sending' | 'retrying'

interface IssuedKey {
  key: string
  seatIds: string
}

// [선택 완료] 한 번을 보내고 결과를 돌려준다. 멱등키는 고른 좌석 묶음마다 하나다.
// Note: 결과를 끝내 모르고 끝난 요청(응답 없음·504)만 키를 남긴다. 같은 좌석으로 다시 누르면 같은 키로 보내
// 서버가 처음 결과를 돌려주게 한다(그 사이 선점이 성립했을 수 있다). 결과가 정해진 요청(성공, 409, 400 등) 뒤에는
// 같은 좌석이라도 새 키로 보낸다. 같은 키에 다른 좌석이면 서버가 IDEMPOTENCY_KEY_REUSED로 거절한다.
export function useHoldRequest(scheduleId: number) {
  const [state, setState] = useState<HoldRequestState>('idle')
  const issuedRef = useRef<IssuedKey | null>(null)
  const controllerRef = useRef<AbortController | null>(null)

  // 화면을 떠나면 다시 보내기를 멈춘다
  useEffect(() => () => controllerRef.current?.abort(), [])

  async function submit(seatIds: number[]): Promise<HoldOutcome> {
    // 본문 비교에 순서가 들어가므로 늘 오름차순으로 보낸다(응답 seatIds도 오름차순이다)
    const sorted = [...seatIds].sort((a, b) => a - b)
    const signature = sorted.join(',')
    if (issuedRef.current?.seatIds !== signature) {
      issuedRef.current = { key: newIdempotencyKey(), seatIds: signature }
    }
    const controller = new AbortController()
    controllerRef.current = controller
    setState('sending')

    const outcome = await submitHold({
      scheduleId,
      seatIds: sorted,
      idempotencyKey: issuedRef.current.key,
      signal: controller.signal,
      onRetrying: () => setState('retrying'),
    })

    if (outcome.kind === 'aborted') {
      return outcome
    }
    if (!(outcome.kind === 'failed' && isOutcomeUnknown(outcome.error))) {
      issuedRef.current = null
    }
    setState('idle')
    return outcome
  }

  return { state, busy: state !== 'idle', submit }
}
