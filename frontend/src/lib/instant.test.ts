import { describe, expect, it } from 'vitest'
import { instantMillis } from '@/lib/instant'

describe('instantMillis', () => {
  it('나노초 9자리는 밀리초까지 읽는다', () => {
    expect(instantMillis('2026-11-02T08:42:38.508856924Z')).toBe(
      Date.UTC(2026, 10, 2, 8, 42, 38, 508),
    )
  })

  it('소수점이 없거나 오프셋으로 온 시각도 같은 기준으로 읽는다', () => {
    expect(instantMillis('2026-11-02T08:42:38Z')).toBe(Date.UTC(2026, 10, 2, 8, 42, 38))
    expect(instantMillis('2026-11-02T17:42:38+09:00')).toBe(Date.UTC(2026, 10, 2, 8, 42, 38))
  })
})
