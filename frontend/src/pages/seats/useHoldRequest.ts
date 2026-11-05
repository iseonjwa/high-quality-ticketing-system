import { useEffect, useRef, useState } from 'react'
import { isOutcomeUnknown } from '@/api/apiError'
import { newIdempotencyKey } from '@/api/idempotencyKey'
import type { SeatHold } from '@/api/reservations'
import { sleepUntil, submitHold } from '@/pages/seats/seatHold'

// sending: 첫 요청을 기다린다. retrying: 결과를 모르거나 일시 장애라 같은 키로 다시 보내는 중이다
export type HoldRequestState = 'idle' | 'sending' | 'retrying'

export type HoldResult =
  | { kind: 'held'; hold: SeatHold }
  // 다른 사람이 먼저 잡은 좌석이 있다. 화면이 좌석을 다시 받아 고르게 한다
  | { kind: 'conflict' }
  // 선점이 됐는지 끝내 모른다. 키를 남겨 두고 같은 좌석으로 다시 확인하게 한다
  | { kind: 'unknown' }
  // 입장 토큰이 끝났다. maybeHeld면 앞서 결과를 모른 요청이 있어 좌석이 잡혔을 수 있다
  | { kind: 'entryExpired'; maybeHeld: boolean }
  | { kind: 'failed'; error: unknown }
  | { kind: 'aborted' }

interface IssuedKey {
  key: string
  seatIds: string
  // 이 키로 보낸 요청 중 결과를 모른 것(응답 없음·504)이 한 번이라도 있었다
  uncertain: boolean
}

// 결과를 모른 요청 뒤 같은 키 재요청이 409를 받았을 때 한 번 더 확인하기까지 기다리는 시간.
// Gateway 응답 시한(3초)만큼 기다리면 앞 요청의 트랜잭션이 커밋(같은 키가 201을 다시 준다)이나 롤백으로 끝나 있다
const CONFLICT_RECHECK_MILLIS = 3000

interface UseHoldRequestOptions {
  // 테스트가 바꿔 끼운다
  sleep?: (millis: number, signal: AbortSignal) => Promise<void>
}

// [선택 완료] 한 번을 보내고 결과를 돌려준다. 멱등키는 고른 좌석 묶음마다 하나다(API 클라이언트 규칙 "멱등키").
// Note: 결과를 모른 요청이 한 번이라도 있으면 그 키는 "결과 미확인"으로 남는다. 앞 요청이 서버에서 아직 처리 중이면
// 같은 키 재요청이 내 좌석과 겹쳐 409를 받고(예매 계약 3-2 ⑥), 503이 이어지면 성공 여부를 끝내 모른다. 이때 키를 버리고
// 409를 "남이 가져감"으로 보이면, 사실 내가 잡은 좌석을 잃은 것으로 안내하게 된다(셀프 리뷰에서 재현). 그래서
// - 결과 미확인 뒤 409는 잠시 기다려 같은 키로 한 번 더 확인한다. 그래도 409면 진짜 겹침이다
// - 결과 미확인 뒤 그 밖의 실패는 "결과 모름"으로 돌려주고 키를 남긴다. 같은 좌석으로 다시 누르면 같은 키로 확인한다
// 결과가 정해진 요청 뒤에는 새 키다. 서버는 성공한 선점만 키를 기록하고 좌석 ID를 정렬해 비교하므로,
// 실패 뒤 키를 바꾸지 않아도 결과는 같지만 성공한 키를 다른 좌석에 다시 쓰면 IDEMPOTENCY_KEY_REUSED다.
export function useHoldRequest(scheduleId: number, options: UseHoldRequestOptions = {}) {
  const { sleep = sleepUntil } = options
  const [state, setState] = useState<HoldRequestState>('idle')
  // 결과를 모르는 키가 남아 있다. 화면은 좌석을 바꾸지 못하게 하고 [결과 확인하기]로 같은 좌석을 다시 보내게 한다
  const [outstanding, setOutstanding] = useState(false)
  const issuedRef = useRef<IssuedKey | null>(null)
  const controllerRef = useRef<AbortController | null>(null)

  // 화면을 떠나면 다시 보내기를 멈춘다
  useEffect(() => () => controllerRef.current?.abort(), [])

  function clearKey() {
    issuedRef.current = null
    setOutstanding(false)
  }

  async function submit(seatIds: number[]): Promise<HoldResult> {
    // 같은 좌석 묶음을 같은 키로 알아보려고 정렬해 둔다(서버도 정렬해서 비교한다)
    const sorted = [...seatIds].sort((a, b) => a - b)
    const signature = sorted.join(',')
    if (issuedRef.current?.seatIds !== signature) {
      issuedRef.current = { key: newIdempotencyKey(), seatIds: signature, uncertain: false }
    }
    const issued = issuedRef.current
    const controller = new AbortController()
    controllerRef.current = controller
    setState('sending')

    const attempt = () =>
      submitHold({
        scheduleId,
        seatIds: sorted,
        idempotencyKey: issued.key,
        signal: controller.signal,
        onRetrying: (error) => {
          if (isOutcomeUnknown(error)) {
            issued.uncertain = true
          }
          setState('retrying')
        },
      })

    let outcome = await attempt()
    if (outcome.kind === 'conflict' && issued.uncertain) {
      setState('retrying')
      await sleep(CONFLICT_RECHECK_MILLIS, controller.signal)
      outcome = await attempt()
    }
    if (outcome.kind === 'aborted' || controller.signal.aborted) {
      return { kind: 'aborted' }
    }
    setState('idle')

    switch (outcome.kind) {
      case 'held':
        clearKey()
        return outcome
      case 'conflict':
        // 결과 미확인이었어도 기다린 뒤 다시 확인한 409라 진짜 겹침이다
        clearKey()
        return outcome
      case 'entryExpired':
        clearKey()
        return { kind: 'entryExpired', maybeHeld: issued.uncertain }
      case 'failed':
        if (issued.uncertain || isOutcomeUnknown(outcome.error)) {
          issued.uncertain = true
          setOutstanding(true)
          return { kind: 'unknown' }
        }
        clearKey()
        return outcome
    }
  }

  // [다른 좌석 고르기]: 결과를 모르는 키를 버린다. 잡혔더라도 결제하지 않으면 선점 시간이 지나 풀린다
  function abandon() {
    clearKey()
  }

  return { state, busy: state !== 'idle', outstanding, submit, abandon }
}
