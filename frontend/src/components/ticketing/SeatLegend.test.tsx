import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SeatLegend } from '@/components/ticketing/SeatLegend'

const GRADES = [{ grade: 'VIP', price: 165000 }]

describe('SeatLegend', () => {
  it('기본으로 등급·선택함·선택할 수 없음·추첨석을 보인다', () => {
    render(<SeatLegend grades={GRADES} />)

    expect(screen.getByText('VIP 165,000원')).toBeInTheDocument()
    expect(screen.getByText('선택함')).toBeInTheDocument()
    expect(screen.getByText('선택할 수 없음')).toBeInTheDocument()
    expect(screen.getByText('추첨석')).toBeInTheDocument()
  })

  it('showLottery가 false면 추첨석 칸을 뺀다', () => {
    render(<SeatLegend grades={GRADES} showLottery={false} />)

    expect(screen.queryByText('추첨석')).not.toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
  })
})
