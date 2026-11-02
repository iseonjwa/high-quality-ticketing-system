import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Countdown } from '@/components/ticketing/Countdown'

const BROWSER_NOW = new Date('2026-11-02T12:00:00Z').getTime()

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(BROWSER_NOW)
})

afterEach(() => {
  vi.useRealTimers()
})

describe('Countdown', () => {
  it('서버 시각으로 보정한 남은 시간을 보인다', () => {
    // 서버가 브라우저보다 10초 앞서 있어 오픈까지 남은 시간이 10초 줄어든다
    render(<Countdown targetAt="2026-11-02T12:01:00Z" serverTime="2026-11-02T12:00:10Z" />)

    expect(screen.getByRole('timer')).toHaveTextContent('00:00:50')
  })

  it('하루 이상 남으면 일을 보인다', () => {
    render(<Countdown targetAt="2026-11-04T15:04:05Z" serverTime="2026-11-02T12:00:00Z" />)

    expect(screen.getByRole('timer')).toHaveTextContent('2일 03:04:05')
  })

  it('0이 되면 onComplete를 한 번 부른다', () => {
    const onComplete = vi.fn()
    render(
      <Countdown
        targetAt="2026-11-02T12:00:30Z"
        serverTime="2026-11-02T12:00:10Z"
        onComplete={onComplete}
      />,
    )
    expect(onComplete).not.toHaveBeenCalled()

    act(() => {
      vi.advanceTimersByTime(19_000)
    })
    expect(onComplete).not.toHaveBeenCalled()

    act(() => {
      vi.advanceTimersByTime(1500)
    })
    expect(screen.getByRole('timer')).toHaveTextContent('00:00:00')
    expect(onComplete).toHaveBeenCalledTimes(1)

    act(() => {
      vi.advanceTimersByTime(5000)
    })
    expect(onComplete).toHaveBeenCalledTimes(1)
  })
})
