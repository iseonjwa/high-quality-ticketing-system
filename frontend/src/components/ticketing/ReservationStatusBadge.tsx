import type { ReservationStatus } from '@/api/reservations'
import { Badge } from '@/components/ui/badge'

// 예매 상태 배지(samples/reservations.html). 결제 상태는 예매 응답에 없어 예매 상태로만 보인다
const RESERVATION_STATUS_BADGES = {
  HELD: { variant: 'pending', label: '결제 대기' },
  CONFIRMED: { variant: 'onSale', label: '예매 확정' },
  EXPIRED: { variant: 'soldOut', label: '기한 만료' },
  RELEASED: { variant: 'soldOut', label: '좌석 반환' },
  CANCELED: { variant: 'soldOut', label: '취소됨' },
} as const

export function ReservationStatusBadge({ status }: { status: ReservationStatus }) {
  const { variant, label } = RESERVATION_STATUS_BADGES[status]
  return <Badge variant={variant}>{label}</Badge>
}
