import { describe, expect, it } from 'vitest'
import { isoToKstLocal, kstLocalToIso } from '@/lib/kstDateTime'

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

describe('isoToKstLocal', () => {
  it('Z로 온 시각을 한국 시각으로 바꾼다', () => {
    expect(isoToKstLocal('2026-12-20T10:00:00Z')).toBe('2026-12-20T19:00')
  })

  it('+09:00으로 온 시각은 그대로 읽는다', () => {
    expect(isoToKstLocal('2026-12-20T19:00:00+09:00')).toBe('2026-12-20T19:00')
  })

  it('다른 오프셋으로 온 시각도 같은 순간의 한국 시각이다', () => {
    expect(isoToKstLocal('2026-12-20T05:00:00-05:00')).toBe('2026-12-20T19:00')
  })

  it('초와 소수부는 버린다', () => {
    expect(isoToKstLocal('2026-12-20T10:00:59Z')).toBe('2026-12-20T19:00')
    expect(isoToKstLocal('2026-12-20T10:00:59.999Z')).toBe('2026-12-20T19:00')
  })

  it('나노초 9자리도 읽는다', () => {
    expect(isoToKstLocal('2026-12-20T10:00:38.508856924Z')).toBe('2026-12-20T19:00')
  })

  it('날짜가 바뀌는 경계를 한국 시각으로 넘긴다', () => {
    expect(isoToKstLocal('2026-12-31T15:00:00Z')).toBe('2027-01-01T00:00')
    expect(isoToKstLocal('2026-12-31T14:59:00Z')).toBe('2026-12-31T23:59')
  })

  it('kstLocalToIso로 만든 값을 되돌리면 같다', () => {
    const iso = kstLocalToIso('2026-12-20T19:00') as string
    expect(isoToKstLocal(iso)).toBe('2026-12-20T19:00')
  })
})
