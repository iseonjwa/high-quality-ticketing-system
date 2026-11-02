import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import type { ScheduleSeats } from '@/api/concerts'
import { SeatMap, type SeatMapProps } from '@/components/ticketing/SeatMap'

const SECTIONS: ScheduleSeats['sections'] = [
  {
    section: 'A',
    grade: 'VIP',
    price: 165000,
    seats: [
      { seatId: 1, row: 1, number: 1, available: true },
      { seatId: 2, row: 1, number: 2, available: false },
      { seatId: 3, row: 1, number: 3, available: true },
      { seatId: 4, row: 2, number: 1, available: true },
      { seatId: 5, row: 2, number: 2, available: true },
      { seatId: 6, row: 2, number: 3, available: true },
    ],
  },
  {
    section: 'B',
    grade: 'R',
    price: 132000,
    seats: [{ seatId: 7, row: 1, number: 1, available: true }],
  },
]

function Harness(props: Partial<SeatMapProps> & { initial?: number[] }) {
  const { initial = [], ...rest } = props
  const [selected, setSelected] = useState<number[]>(initial)
  return (
    <SeatMap
      sections={SECTIONS}
      selectedSeatIds={selected}
      maxSelectable={2}
      onChange={setSelected}
      {...rest}
    />
  )
}

const seat = (name: RegExp | string) => screen.getByRole('button', { name })

describe('SeatMap', () => {
  it('구역 이름표에 등급 글자를 쓴다', () => {
    render(<Harness />)

    expect(screen.getByText('A구역 · VIP')).toBeInTheDocument()
    expect(screen.getByText('B구역 · R')).toBeInTheDocument()
    expect(screen.getByText('무대')).toBeInTheDocument()
  })

  it('누르면 고르고 다시 누르면 해제한다', async () => {
    render(<Harness />)
    const first = seat(/A구역 1열 1번/)

    await userEvent.click(first)
    expect(first).toHaveAttribute('aria-pressed', 'true')

    await userEvent.click(first)
    expect(first).toHaveAttribute('aria-pressed', 'false')
  })

  it('한도에서는 고르지 않고 onLimitReached만 부른다', async () => {
    const onChange = vi.fn()
    const onLimitReached = vi.fn()
    render(
      <SeatMap
        sections={SECTIONS}
        selectedSeatIds={[1, 3]}
        maxSelectable={2}
        onChange={onChange}
        onLimitReached={onLimitReached}
      />,
    )

    await userEvent.click(seat(/A구역 2열 1번/))

    expect(onChange).not.toHaveBeenCalled()
    expect(onLimitReached).toHaveBeenCalledTimes(1)
  })

  it('한도에서도 고른 좌석은 해제할 수 있다', async () => {
    const onChange = vi.fn()
    render(
      <SeatMap
        sections={SECTIONS}
        selectedSeatIds={[1, 3]}
        maxSelectable={2}
        onChange={onChange}
      />,
    )

    await userEvent.click(seat(/A구역 1열 1번/))

    expect(onChange).toHaveBeenCalledWith([3])
  })

  it('Tab 정지점은 하나이고 처음에는 첫 선택 가능 좌석이다', () => {
    render(<Harness />)

    const stops = screen.getAllByRole('button').filter((b) => b.getAttribute('tabindex') === '0')
    expect(stops).toHaveLength(1)
    expect(stops[0]).toHaveAccessibleName(/A구역 1열 1번/)
  })

  it('선택한 좌석이 있으면 그 좌석이 Tab 정지점이다', () => {
    render(<Harness initial={[5]} />)

    expect(seat(/A구역 2열 2번/)).toHaveAttribute('tabindex', '0')
    expect(seat(/A구역 1열 1번/)).toHaveAttribute('tabindex', '-1')
  })

  it('화살표로 같은 구역의 좌석으로 움직이고 비활성 좌석은 건너뛴다', async () => {
    render(<Harness />)
    seat(/A구역 1열 1번/).focus()

    await userEvent.keyboard('{ArrowRight}')
    // 1열 2번은 선택할 수 없어 3번으로 간다
    expect(seat(/A구역 1열 3번/)).toHaveFocus()

    await userEvent.keyboard('{ArrowDown}')
    expect(seat(/A구역 2열 3번/)).toHaveFocus()

    await userEvent.keyboard('{ArrowLeft}')
    expect(seat(/A구역 2열 2번/)).toHaveFocus()

    await userEvent.keyboard('{ArrowUp}')
    // 위 행의 2번이 비활성이라 가장 가까운 활성 좌석(1번과 3번 중 번호가 작은 쪽)으로 간다
    expect(seat(/A구역 1열 1번/)).toHaveFocus()
  })

  it('구역을 넘어 움직이지 않는다', async () => {
    render(<Harness />)
    seat(/A구역 2열 3번/).focus()

    await userEvent.keyboard('{ArrowRight}{ArrowDown}')

    expect(seat(/A구역 2열 3번/)).toHaveFocus()
  })

  it('방금 선택됨 좌석과 추첨석은 고를 수 없다', () => {
    render(<Harness justTakenSeatIds={[1]} lotterySeatIds={[3]} />)

    expect(seat(/A구역 1열 1번.*선택할 수 없음/)).toBeDisabled()
    expect(seat(/A구역 1열 3번.*추첨석/)).toBeDisabled()
  })

  it('범례에 등급별 가격과 상태를 보인다', () => {
    render(<Harness />)
    const legend = screen.getByRole('list', { name: '좌석 범례' })

    expect(within(legend).getByText('VIP 165,000원')).toBeInTheDocument()
    expect(within(legend).getByText('R 132,000원')).toBeInTheDocument()
    expect(within(legend).getByText('선택함')).toBeInTheDocument()
    expect(within(legend).getByText('선택할 수 없음')).toBeInTheDocument()
    expect(within(legend).getByText('추첨석')).toBeInTheDocument()
    // 견본은 누를 수 없고 버튼으로 나오지 않는다
    expect(within(legend).queryByRole('button')).not.toBeInTheDocument()
  })
})
