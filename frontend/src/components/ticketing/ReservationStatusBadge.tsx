import type { ReservationStatus } from '@/api/reservations'
import { RESERVATION_STATUS_BADGES } from '@/components/ticketing/reservationStatus'
import { Badge } from '@/components/ui/badge'

export function ReservationStatusBadge({ status }: { status: ReservationStatus }) {
  const { variant, label } = RESERVATION_STATUS_BADGES[status]
  return <Badge variant={variant}>{label}</Badge>
}
