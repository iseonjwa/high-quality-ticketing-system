import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TimeChip } from '@/components/ticketing/TimeChip'

const NOW = new Date('2026-11-02T12:00:00Z').getTime()

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
})

afterEach(() => {
  vi.useRealTimers()
})

function advance(millis: number) {
  act(() => {
    vi.advanceTimersByTime(millis)
  })
}

describe('TimeChip', () => {
  it('기본 → 경고 → 위험으로 바뀌고 onExpire는 한 번만 불린다', () => {
    const onExpire = vi.fn()
    // 만료까지 62초
    render(<TimeChip label="입장 유효 시간" expiresAt="2026-11-02T12:01:02Z" onExpire={onExpire} />)
    const chip = screen.getByRole('timer')
    expect(chip).toHaveAttribute('data-level', 'default')
    expect(chip).toHaveTextContent('01:02')

    advance(1000)
    expect(chip).toHaveAttribute('data-level', 'default')
    expect(chip).toHaveTextContent('01:01')

    advance(1000)
    expect(chip).toHaveAttribute('data-level', 'warning')
    expect(chip).toHaveTextContent('01:00')

    advance(60_000)
    expect(chip).toHaveAttribute('data-level', 'danger')
    expect(chip).toHaveTextContent('00:00')
    expect(onExpire).toHaveBeenCalledTimes(1)

    advance(5000)
    expect(onExpire).toHaveBeenCalledTimes(1)
  })

  it('60초 경계: 정확히 60초 남으면 경고다', () => {
    render(<TimeChip label="결제 가능 시간" expiresAt="2026-11-02T12:01:00Z" />)

    expect(screen.getByRole('timer')).toHaveAttribute('data-level', 'warning')
  })

  it('이미 지난 시각이면 처음부터 위험이고 onExpire를 부른다', () => {
    const onExpire = vi.fn()
    render(<TimeChip label="입장 유효 시간" expiresAt="2026-11-02T11:59:00Z" onExpire={onExpire} />)

    expect(screen.getByRole('timer')).toHaveAttribute('data-level', 'danger')
    expect(onExpire).toHaveBeenCalledTimes(1)
  })
})
