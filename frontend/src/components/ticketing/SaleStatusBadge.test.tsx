import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SaleStatusBadge } from '@/components/ticketing/SaleStatusBadge'

describe('SaleStatusBadge', () => {
  it.each([
    ['BEFORE_OPEN', '오픈 예정', 'upcoming'],
    ['ON_SALE', '예매 중', 'onSale'],
    ['CLOSED', '판매 종료', 'soldOut'],
  ] as const)('%s는 %s로 보인다', (status, label, variant) => {
    render(<SaleStatusBadge status={status} />)

    expect(screen.getByText(label)).toHaveAttribute('data-variant', variant)
  })
})
