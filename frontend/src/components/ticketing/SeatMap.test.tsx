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

  it('Tab 정지점은 구역마다 하나이고 처음에는 구역의 첫 선택 가능 좌석이다', () => {
    render(<Harness />)

    const stops = screen.getAllByRole('button').filter((b) => b.getAttribute('tabindex') === '0')
    expect(stops).toHaveLength(2)
    expect(stops[0]).toHaveAccessibleName(/A구역 1열 1번/)
    expect(stops[1]).toHaveAccessibleName(/B구역 1열 1번/)
  })

  it('Tab으로 모든 구역의 좌석에 닿는다', async () => {
    render(
      <>
        <button type="button">앞</button>
        <Harness />
      </>,
    )
    screen.getByRole('button', { name: '앞' }).focus()

    await userEvent.tab()
    expect(seat(/A구역 1열 1번/)).toHaveFocus()
    await userEvent.tab()
    expect(seat(/B구역 1열 1번/)).toHaveFocus()
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

  it('화살표는 구역 안에서만 움직인다(구역 사이는 Tab)', async () => {
    render(<Harness />)
    seat(/A구역 2열 3번/).focus()

    await userEvent.keyboard('{ArrowRight}{ArrowDown}')

    expect(seat(/A구역 2열 3번/)).toHaveFocus()
  })

  it('구역에서 마지막으로 간 좌석이 그 구역의 Tab 정지점이 되고, 그 좌석이 비활성이 되면 다른 좌석으로 옮긴다', async () => {
    const { rerender } = render(
      <SeatMap sections={SECTIONS} selectedSeatIds={[]} maxSelectable={2} onChange={() => {}} />,
    )
    seat(/A구역 1열 1번/).focus()
    await userEvent.keyboard('{ArrowDown}')
    expect(seat(/A구역 2열 1번/)).toHaveAttribute('tabindex', '0')
    expect(seat(/A구역 1열 1번/)).toHaveAttribute('tabindex', '-1')

    // 다시 조회했더니 그 좌석이 팔렸다
    const resold = SECTIONS.map((section) => ({
      ...section,
      seats: section.seats.map((s) => (s.seatId === 4 ? { ...s, available: false } : s)),
    }))
    rerender(
      <SeatMap sections={resold} selectedSeatIds={[]} maxSelectable={2} onChange={() => {}} />,
    )

    expect(seat(/A구역 1열 1번/)).toHaveAttribute('tabindex', '0')
  })

  it('고를 수 없게 된 좌석이 선택 목록에 남아 있어도 한도에 세지 않는다', async () => {
    const onChange = vi.fn()
    // 1번은 409로 방금 남에게 넘어갔지만 화면이 아직 선택 목록에서 빼지 않았다
    render(
      <SeatMap
        sections={SECTIONS}
        selectedSeatIds={[1, 3]}
        justTakenSeatIds={[1]}
        maxSelectable={2}
        onChange={onChange}
      />,
    )

    await userEvent.click(seat(/A구역 2열 1번/))

    expect(onChange).toHaveBeenCalledWith([1, 3, 4])
  })

  it('잠그면 모든 좌석을 누를 수 없고 Tab 정지점도 없다', async () => {
    const onChange = vi.fn()
    render(
      <SeatMap
        sections={SECTIONS}
        selectedSeatIds={[5]}
        maxSelectable={2}
        onChange={onChange}
        locked
      />,
    )

    const seats = screen
      .getAllByRole('button')
      .filter((b) => b.getAttribute('data-slot') === 'seat')
    expect(seats.every((b) => (b as HTMLButtonElement).disabled)).toBe(true)
    expect(seats.some((b) => b.getAttribute('tabindex') === '0')).toBe(false)
    await userEvent.click(seat(/A구역 1열 1번/))
    expect(onChange).not.toHaveBeenCalled()
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
