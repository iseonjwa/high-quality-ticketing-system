import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { ScheduleSeats } from '@/api/concerts'
import { SeatSectionGrid } from '@/components/ticketing/SeatSectionGrid'

type Section = ScheduleSeats['sections'][number]

// 1열: 1·3번만 고를 수 있다 / 2열: 2·4번만 고를 수 있다 / 3열: 모두 고를 수 있다
const SECTION: Section = {
  section: 'A',
  grade: 'VIP',
  price: 165000,
  seats: [
    { seatId: 1, row: 1, number: 1, available: true },
    { seatId: 2, row: 1, number: 2, available: false },
    { seatId: 3, row: 1, number: 3, available: true },
    { seatId: 4, row: 1, number: 4, available: false },
    { seatId: 5, row: 2, number: 1, available: false },
    { seatId: 6, row: 2, number: 2, available: true },
    { seatId: 7, row: 2, number: 3, available: false },
    { seatId: 8, row: 2, number: 4, available: true },
    { seatId: 9, row: 3, number: 1, available: true },
    { seatId: 10, row: 3, number: 2, available: true },
    { seatId: 11, row: 3, number: 3, available: true },
    { seatId: 12, row: 3, number: 4, available: true },
  ],
}

const seat = (row: number, number: number) =>
  screen.getByRole('button', { name: new RegExp(`A구역 ${row}열 ${number}번,`) })

function renderGrid(props: Partial<Parameters<typeof SeatSectionGrid>[0]> = {}) {
  const onToggle = vi.fn()
  render(<SeatSectionGrid section={SECTION} selectedSeatIds={[]} onToggle={onToggle} {...props} />)
  return { onToggle }
}

const tabStops = () =>
  screen.getAllByRole('button', { hidden: true }).filter((button) => button.tabIndex === 0)

describe('SeatSectionGrid 키보드', () => {
  it('좌우 화살표는 같은 열에서 선택할 수 없는 좌석을 건너뛴다', async () => {
    renderGrid()
    await userEvent.tab()
    expect(seat(1, 1)).toHaveFocus()

    await userEvent.keyboard('{ArrowRight}')
    expect(seat(1, 3)).toHaveFocus()

    await userEvent.keyboard('{ArrowRight}')
    // 4번은 선택할 수 없고 더 오른쪽 좌석이 없어 그대로다
    expect(seat(1, 3)).toHaveFocus()

    await userEvent.keyboard('{ArrowLeft}')
    expect(seat(1, 1)).toHaveFocus()
  })

  it('위아래 화살표는 다음 열에서 번호가 가장 가까운 선택 가능한 좌석으로 간다', async () => {
    renderGrid()
    await userEvent.tab()
    await userEvent.keyboard('{ArrowRight}')
    expect(seat(1, 3)).toHaveFocus()

    // 2열은 2·4번만 고를 수 있다. 3번에서 거리가 같으면 작은 번호다
    await userEvent.keyboard('{ArrowDown}')
    expect(seat(2, 2)).toHaveFocus()

    await userEvent.keyboard('{ArrowDown}')
    expect(seat(3, 2)).toHaveFocus()

    await userEvent.keyboard('{ArrowRight}{ArrowRight}')
    expect(seat(3, 4)).toHaveFocus()
    await userEvent.keyboard('{ArrowUp}')
    expect(seat(2, 4)).toHaveFocus()
    await userEvent.keyboard('{ArrowUp}')
    // 1열은 1·3번만 고를 수 있다. 4번에 가장 가까운 3번이다
    expect(seat(1, 3)).toHaveFocus()
  })

  it('고를 수 있는 열이 더 없으면 위아래 화살표는 그 자리에 머문다', async () => {
    renderGrid()
    await userEvent.tab()

    await userEvent.keyboard('{ArrowUp}')

    expect(seat(1, 1)).toHaveFocus()
  })

  it('Tab 정지점은 하나이고 마지막으로 초점이 간 좌석을 따라간다', async () => {
    renderGrid()
    expect(tabStops()).toEqual([seat(1, 1)])

    await userEvent.tab()
    await userEvent.keyboard('{ArrowRight}')

    expect(tabStops()).toEqual([seat(1, 3)])
  })

  it('고른 좌석이 있으면 정지점이 그 좌석이다', () => {
    renderGrid({ selectedSeatIds: [8] })

    expect(tabStops()).toEqual([seat(2, 4)])
  })

  it('좌석을 누르면 좌석 ID로 알린다', async () => {
    const { onToggle } = renderGrid()

    await userEvent.click(seat(3, 3))

    expect(onToggle).toHaveBeenCalledWith(11)
  })
})

describe('SeatSectionGrid locked', () => {
  it('모든 좌석을 누를 수 없게 하고 Tab 정지점도 없앤다', async () => {
    const { onToggle } = renderGrid({ locked: true })

    const buttons = screen.getAllByRole('button')
    expect(buttons).toHaveLength(12)
    buttons.forEach((button) => expect(button).toBeDisabled())
    expect(tabStops()).toHaveLength(0)

    await userEvent.click(seat(1, 1))
    await userEvent.tab()
    expect(onToggle).not.toHaveBeenCalled()
    expect(document.body).toHaveFocus()
  })
})
