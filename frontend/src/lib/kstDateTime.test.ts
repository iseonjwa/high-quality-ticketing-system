import { describe, expect, it } from 'vitest'
import { kstLocalToIso } from '@/lib/kstDateTime'

describe('kstLocalToIso', () => {
  it('입력 시각에 한국 오프셋을 붙인다', () => {
    expect(kstLocalToIso('2026-12-20T19:00')).toBe('2026-12-20T19:00:00+09:00')
  })

  it('시간대를 쓰지 않아 같은 입력은 어느 환경에서나 같은 순간이 된다', () => {
    const iso = kstLocalToIso('2026-12-20T19:00')
    expect(iso).not.toBeNull()
    expect(new Date(iso as string).toISOString()).toBe('2026-12-20T10:00:00.000Z')
  })

  it('날짜가 바뀌는 경계도 그대로 둔다', () => {
    expect(kstLocalToIso('2026-01-01T00:00')).toBe('2026-01-01T00:00:00+09:00')
  })

  it.each([
    '',
    '2026-12-20',
    '2026-12-20 19:00',
    '2026-12-20T19:00:00',
    '2026-02-30T10:00',
    '2026-12-20T25:00',
  ])('형식이 틀리면 null이다: %s', (value) => {
    expect(kstLocalToIso(value)).toBeNull()
  })
})
