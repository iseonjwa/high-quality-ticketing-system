import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { ReservationStatus } from '@/api/reservations'
import { ReservationStatusBadge } from '@/components/ticketing/ReservationStatusBadge'

const LABELS: Record<ReservationStatus, string> = {
  HELD: '결제 대기',
  CONFIRMED: '예매 확정',
  EXPIRED: '기한 만료',
  RELEASED: '좌석 반환',
  CANCELED: '취소됨',
}

describe('ReservationStatusBadge', () => {
  it.each(Object.entries(LABELS) as [ReservationStatus, string][])(
    '%s는 "%s"로 보인다',
    (status, label) => {
      render(<ReservationStatusBadge status={status} />)

      expect(screen.getByText(label)).toBeInTheDocument()
    },
  )
})
