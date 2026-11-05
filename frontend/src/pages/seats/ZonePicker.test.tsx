import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { ScheduleSeats } from '@/api/concerts'
import { ZonePicker } from '@/pages/seats/ZonePicker'

const SECTIONS: ScheduleSeats['sections'] = [
  {
    section: 'A',
    grade: 'VIP',
    price: 165000,
    seats: [
      { seatId: 1, row: 1, number: 1, available: true },
      { seatId: 2, row: 1, number: 2, available: true },
      { seatId: 3, row: 1, number: 3, available: false },
    ],
  },
  {
    section: 'B',
    grade: 'R',
    price: 132000,
    seats: [{ seatId: 4, row: 1, number: 1, available: false }],
  },
  {
    section: 'C',
    grade: 'R',
    price: 132000,
    seats: [{ seatId: 5, row: 1, number: 1, available: true }],
  },
]

function Harness({
  onSelect,
  picked = new Map<string, number>(),
}: {
  onSelect?: (section: string) => void
  picked?: Map<string, number>
}) {
  const [current, setCurrent] = useState('A')
  return (
    <ZonePicker
      sections={SECTIONS}
      current={current}
      pickedBySection={picked}
      onSelect={(next) => {
        setCurrent(next)
        onSelect?.(next)
      }}
    />
  )
}

describe('ZonePicker', () => {
  it('구역마다 등급과 남은 좌석 수를 이름으로 알리고 고른 구역을 표시한다', () => {
    render(<Harness />)

    expect(screen.getByRole('radiogroup', { name: '구역' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'A구역 VIP, 남은 2석' })).toBeChecked()
    expect(screen.getByRole('radio', { name: 'C구역 R, 남은 1석' })).not.toBeChecked()
  })

  it('고른 좌석이 있는 구역은 이름에 몇 석 골랐는지 덧붙인다', () => {
    render(<Harness picked={new Map([['C', 2]])} />)

    expect(screen.getByRole('radio', { name: 'C구역 R, 남은 1석, 2석 고름' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'A구역 VIP, 남은 2석' })).toBeInTheDocument()
  })

  it('매진 구역은 비활성이고 눌러도 고르지 않는다', async () => {
    const onSelect = vi.fn()
    render(<Harness onSelect={onSelect} />)
    const soldOut = screen.getByRole('radio', { name: 'B구역 R, 매진' })

    expect(soldOut).toBeDisabled()
    await userEvent.click(soldOut)

    expect(onSelect).not.toHaveBeenCalled()
  })

  it('구역을 누르면 그 구역을 고르고, 고른 구역을 다시 눌러도 선택이 유지된다', async () => {
    const onSelect = vi.fn()
    render(<Harness onSelect={onSelect} />)

    await userEvent.click(screen.getByRole('radio', { name: /^C구역/ }))
    expect(onSelect).toHaveBeenCalledWith('C')
    expect(screen.getByRole('radio', { name: /^C구역/ })).toBeChecked()

    onSelect.mockClear()
    await userEvent.click(screen.getByRole('radio', { name: /^C구역/ }))

    expect(onSelect).not.toHaveBeenCalled()
    expect(screen.getByRole('radio', { name: /^C구역/ })).toBeChecked()
  })

  it('화살표로 매진 구역을 건너뛰어 옮기고 Enter로 고른다', async () => {
    const onSelect = vi.fn()
    render(<Harness onSelect={onSelect} />)

    await userEvent.tab()
    expect(screen.getByRole('radio', { name: /^A구역/ })).toHaveFocus()
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('radio', { name: /^C구역/ })).toHaveFocus()

    await userEvent.keyboard('{Enter}')

    expect(onSelect).toHaveBeenCalledWith('C')
    expect(screen.getByRole('radio', { name: /^C구역/ })).toBeChecked()
  })

  it('등급마다 가격을 한 번씩 적는다', () => {
    render(<Harness />)

    const legend = screen.getByRole('list', { name: '등급별 가격' })
    expect(legend).toHaveTextContent('VIP165,000원')
    expect(legend).toHaveTextContent('R132,000원')
    expect(legend.querySelectorAll('li')).toHaveLength(2)
  })
})
