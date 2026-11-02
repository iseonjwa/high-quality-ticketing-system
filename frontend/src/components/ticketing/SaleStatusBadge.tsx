import type { SaleStatus } from '@/api/concerts'
import { Badge } from '@/components/ui/badge'

const SALE_STATUS_BADGES = {
  BEFORE_OPEN: { variant: 'upcoming', label: '오픈 예정' },
  ON_SALE: { variant: 'onSale', label: '예매 중' },
  CLOSED: { variant: 'soldOut', label: '판매 종료' },
} as const

export function SaleStatusBadge({ status }: { status: SaleStatus }) {
  const { variant, label } = SALE_STATUS_BADGES[status]
  return <Badge variant={variant}>{label}</Badge>
}
