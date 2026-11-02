import { act, render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Countdown } from '@/components/ticketing/Countdown'
import { createServerClock } from '@/lib/serverClock'

const BROWSER_NOW = new Date('2026-11-02T12:00:00Z').getTime()

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(BROWSER_NOW)
})

afterEach(() => {
  vi.useRealTimers()
})

// 응답을 지금 받았고 서버 시각이 serverTime인 시계
function clockAt(serverTime: string) {
  return createServerClock(serverTime, Date.now())
}

describe('Countdown', () => {
  it('서버 시각으로 보정한 남은 시간을 보이고 이름을 단다', () => {
    // 서버가 브라우저보다 10초 앞서 있어 오픈까지 남은 시간이 10초 줄어든다
    render(<Countdown targetAt="2026-11-02T12:01:00Z" clock={clockAt('2026-11-02T12:00:10Z')} />)

    expect(screen.getByRole('timer', { name: '오픈까지 남은 시간' })).toHaveTextContent('00:00:50')
  })

  it('하루 이상 남으면 일을 보인다', () => {
    render(<Countdown targetAt="2026-11-04T15:04:05Z" clock={clockAt('2026-11-02T12:00:00Z')} />)

    expect(screen.getByRole('timer')).toHaveTextContent('2일 03:04:05')
  })

  it('남은 시간은 올림이라 1초 미만 남았을 때 00:00:01을 보이고, 00:00:00과 onComplete가 같은 순간이다', () => {
    const onComplete = vi.fn()
    render(
      <Countdown
        targetAt="2026-11-02T12:00:01Z"
        clock={clockAt('2026-11-02T12:00:00Z')}
        onComplete={onComplete}
      />,
    )

    act(() => {
      vi.advanceTimersByTime(250)
    })
    expect(screen.getByRole('timer')).toHaveTextContent('00:00:01')
    expect(onComplete).not.toHaveBeenCalled()

    act(() => {
      vi.advanceTimersByTime(750)
    })
    expect(screen.getByRole('timer')).toHaveTextContent('00:00:00')
    expect(onComplete).toHaveBeenCalledTimes(1)
  })

  it('0이 되면 onComplete를 한 번만 부른다', () => {
    const onComplete = vi.fn()
    render(
      <Countdown
        targetAt="2026-11-02T12:00:30Z"
        clock={clockAt('2026-11-02T12:00:10Z')}
        onComplete={onComplete}
      />,
    )

    act(() => {
      vi.advanceTimersByTime(19_000)
    })
    expect(onComplete).not.toHaveBeenCalled()

    act(() => {
      vi.advanceTimersByTime(1500)
    })
    expect(onComplete).toHaveBeenCalledTimes(1)

    act(() => {
      vi.advanceTimersByTime(5000)
    })
    expect(onComplete).toHaveBeenCalledTimes(1)
  })

  it('StrictMode에서도 onComplete를 한 번만 부른다', () => {
    const onComplete = vi.fn()
    render(
      <StrictMode>
        <Countdown
          targetAt="2026-11-02T11:59:00Z"
          clock={clockAt('2026-11-02T12:00:00Z')}
          onComplete={onComplete}
        />
      </StrictMode>,
    )

    expect(onComplete).toHaveBeenCalledTimes(1)
  })

  it('다시 조회한 서버 시각 차이로 0을 막 넘긴 값이 양수로 돌아가도 두 번 부르지 않는다', () => {
    const onComplete = vi.fn()
    const target = '2026-11-02T12:00:00Z'
    const { rerender } = render(
      <Countdown
        targetAt={target}
        clock={clockAt('2026-11-02T12:00:00.500Z')}
        onComplete={onComplete}
      />,
    )
    expect(onComplete).toHaveBeenCalledTimes(1)

    // 새 응답의 서버 시각이 조금 늦게 잡혀 남은 시간이 다시 양수가 됐다가 0을 넘는다
    rerender(
      <Countdown
        targetAt={target}
        clock={clockAt('2026-11-02T11:59:59.500Z')}
        onComplete={onComplete}
      />,
    )
    act(() => {
      vi.advanceTimersByTime(1000)
    })

    expect(onComplete).toHaveBeenCalledTimes(1)
  })

  it('같은 시계로 다시 그려도 흐른 시간만큼 줄어든 값을 보인다(응답 시각 기준)', () => {
    const clock = clockAt('2026-11-02T12:00:00Z')
    const { unmount } = render(<Countdown targetAt="2026-11-02T12:10:00Z" clock={clock} />)
    expect(screen.getByRole('timer')).toHaveTextContent('00:10:00')
    unmount()

    act(() => {
      vi.advanceTimersByTime(3 * 60_000)
    })
    render(<Countdown targetAt="2026-11-02T12:10:00Z" clock={clock} />)

    expect(screen.getByRole('timer')).toHaveTextContent('00:07:00')
  })
})
