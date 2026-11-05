import type { ReservationStatus } from '@/api/reservations'

// 예매 상태 배지의 모양과 글자(samples/reservations.html). 결제 상태는 예매 응답에 없어 예매 상태로만 보인다
export const RESERVATION_STATUS_BADGES = {
  HELD: { variant: 'pending', label: '결제 대기' },
  CONFIRMED: { variant: 'onSale', label: '예매 확정' },
  EXPIRED: { variant: 'soldOut', label: '기한 만료' },
  RELEASED: { variant: 'soldOut', label: '좌석 반환' },
  CANCELED: { variant: 'soldOut', label: '취소됨' },
} as const

// 배지 글자. 링크 이름처럼 배지 밖에서 상태를 글로 쓸 때 같은 말을 쓴다
export function reservationStatusLabel(status: ReservationStatus): string {
  return RESERVATION_STATUS_BADGES[status].label
}
