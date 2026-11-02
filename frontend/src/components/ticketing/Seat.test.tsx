import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Seat, type SeatProps } from '@/components/ticketing/Seat'

const BASE: SeatProps = {
  section: 'A',
  grade: 'VIP',
  price: 165000,
  row: 1,
  number: 7,
  state: 'available',
}

afterEach(() => {
  vi.useRealTimers()
})

describe('Seat', () => {
  it('선택 가능한 좌석은 누를 수 있고 aria-pressed가 false다', async () => {
    const onToggle = vi.fn()
    render(<Seat {...BASE} onToggle={onToggle} />)

    const seat = screen.getByRole('button', { name: 'VIP석 A구역 1열 7번, 165,000원' })
    expect(seat).toHaveAttribute('aria-pressed', 'false')
    expect(seat).toBeEnabled()

    await userEvent.click(seat)

    expect(onToggle).toHaveBeenCalledTimes(1)
  })

  it('선택한 좌석은 aria-pressed가 true다', () => {
    render(<Seat {...BASE} state="selected" />)

    expect(screen.getByRole('button', { name: /VIP석 A구역 1열 7번/ })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('선택할 수 없는 좌석은 비활성이고 이름 끝에 사유가 붙는다', async () => {
    const onToggle = vi.fn()
    render(<Seat {...BASE} state="unavailable" onToggle={onToggle} />)

    const seat = screen.getByRole('button', {
      name: 'VIP석 A구역 1열 7번, 165,000원, 선택할 수 없음',
    })
    expect(seat).toBeDisabled()
    expect(seat).not.toHaveAttribute('aria-pressed')

    await userEvent.click(seat)

    expect(onToggle).not.toHaveBeenCalled()
  })

  it('추첨석은 비활성이고 이유를 이름에 담는다', () => {
    render(<Seat {...BASE} state="lottery" />)

    const seat = screen.getByRole('button', {
      name: 'VIP석 A구역 1열 7번, 165,000원, 추첨석이라 일반 예매로 고를 수 없음',
    })
    expect(seat).toBeDisabled()
  })

  it('justTaken은 1.6초 동안 깜빡이고 그 뒤에는 선택할 수 없음으로 남는다', () => {
    vi.useFakeTimers()
    render(<Seat {...BASE} state="available" justTaken />)

    const seat = screen.getByRole('button', { name: /선택할 수 없음/ })
    expect(seat).toBeDisabled()
    expect(seat).toHaveAttribute('data-flashing')

    act(() => {
      vi.advanceTimersByTime(1599)
    })
    expect(seat).toHaveAttribute('data-flashing')

    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(seat).not.toHaveAttribute('data-flashing')
    expect(seat).toHaveAttribute('data-state', 'unavailable')
  })

  it('justTaken이 꺼졌다 다시 켜지면 깜빡임을 다시 시작한다', () => {
    vi.useFakeTimers()
    const { rerender } = render(<Seat {...BASE} justTaken />)
    const seat = screen.getByRole('button')
    act(() => {
      vi.advanceTimersByTime(1700)
    })
    expect(seat).not.toHaveAttribute('data-flashing')

    rerender(<Seat {...BASE} justTaken={false} />)
    rerender(<Seat {...BASE} justTaken />)

    expect(seat).toHaveAttribute('data-flashing')
  })
})
