import { useSyncExternalStore } from 'react'
import { readBillingKeyRecord, type BillingKeyRecord } from '@/auth/billingKeyRecord'

// 이 브라우저의 자동결제 카드 등록 기록을 읽는다. 기록을 바꾼 쪽이 notifyBillingKeyRecordChanged를 부르면
// 같은 화면의 다른 카드·안내(내 정보, 추첨 응모 화면)가 바로 다시 그린다.
const listeners = new Set<() => void>()

function subscribe(listener: () => void) {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export function notifyBillingKeyRecordChanged() {
  listeners.forEach((listener) => listener())
}

export function useBillingKeyRecord(userId: number): BillingKeyRecord | null {
  // 문자열로 비교해야 렌더마다 새 객체로 다시 그리지 않는다
  const raw = useSyncExternalStore(subscribe, () => JSON.stringify(readBillingKeyRecord(userId)))
  return JSON.parse(raw) as BillingKeyRecord | null
}

// 자동결제 카드 영역의 id. 추첨 응모 화면의 "카드 등록" 안내가 이 영역으로 초점을 옮긴다
export const BILLING_KEY_CARD_ID = 'billing-key'
