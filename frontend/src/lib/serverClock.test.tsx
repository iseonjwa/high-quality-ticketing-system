import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createServerClock, useNow } from '@/lib/serverClock'

const BASE = new Date('2026-11-02T12:00:00Z').getTime()

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(BASE)
})

afterEach(() => {
  vi.useRealTimers()
})

describe('createServerClock', () => {
  it('서버가 10초 빠르면 now()가 10초 앞선다', () => {
    const clock = createServerClock('2026-11-02T12:00:10Z')

    expect(clock.now()).toBe(BASE + 10_000)
  })

  it('시간이 흘러도 차이를 유지한다', () => {
    const clock = createServerClock('2026-11-02T12:00:10Z')

    vi.advanceTimersByTime(5000)

    expect(clock.now()).toBe(BASE + 15_000)
  })

  it('받은 시각을 직접 줄 수 있다', () => {
    const clock = createServerClock('2026-11-02T12:00:00Z', BASE + 3000)

    expect(clock.now()).toBe(BASE - 3000)
  })
})

describe('useNow', () => {
  it('주기마다 보정한 시각을 준다', () => {
    const clock = createServerClock('2026-11-02T12:00:10Z')
    const { result } = renderHook(() => useNow(1000, clock))
    expect(result.current).toBe(BASE + 10_000)

    act(() => {
      vi.advanceTimersByTime(2000)
    })

    expect(result.current).toBe(BASE + 12_000)
  })

  it('언마운트하면 타이머를 지운다', () => {
    const { unmount } = renderHook(() => useNow(1000))
    expect(vi.getTimerCount()).toBe(1)

    unmount()

    expect(vi.getTimerCount()).toBe(0)
  })
})
