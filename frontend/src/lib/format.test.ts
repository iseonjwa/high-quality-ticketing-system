import { describe, expect, it } from 'vitest'
import { formatCountdown, formatRemaining, formatWaitTime, formatWon } from '@/lib/format'

describe('formatWon', () => {
  it('천 단위 쉼표와 원을 붙인다', () => {
    expect(formatWon(165000)).toBe('165,000원')
    expect(formatWon(0)).toBe('0원')
  })
})

describe('formatRemaining', () => {
  it('1시간 미만은 분:초', () => {
    expect(formatRemaining(270_000)).toBe('04:30')
  })

  it('초는 올림이라 1초 미만 남으면 00:01, 정확히 0이어야 00:00이다', () => {
    expect(formatRemaining(59_001)).toBe('01:00')
    expect(formatRemaining(59_000)).toBe('00:59')
    expect(formatRemaining(1)).toBe('00:01')
  })

  it('1시간 이상은 시:분:초', () => {
    expect(formatRemaining(3_600_000)).toBe('1:00:00')
    expect(formatRemaining(3_870_000)).toBe('1:04:30')
  })

  it('음수는 00:00', () => {
    expect(formatRemaining(-1)).toBe('00:00')
    expect(formatRemaining(0)).toBe('00:00')
  })
})

describe('formatCountdown', () => {
  it('하루 미만은 시:분:초', () => {
    expect(formatCountdown(((3 * 60 + 4) * 60 + 5) * 1000)).toBe('03:04:05')
    expect(formatCountdown(86_399_000)).toBe('23:59:59')
  })

  it('하루 이상은 일을 앞에 붙인다', () => {
    expect(formatCountdown((86_400 + (3 * 60 + 4) * 60 + 5) * 1000)).toBe('1일 03:04:05')
    expect(formatCountdown((2 * 86_400 + (3 * 60 + 4) * 60 + 5) * 1000)).toBe('2일 03:04:05')
  })

  it('음수는 00:00:00', () => {
    expect(formatCountdown(-5000)).toBe('00:00:00')
  })
})

describe('formatWaitTime', () => {
  it('60초 미만은 1분 미만', () => {
    expect(formatWaitTime(0)).toBe('1분 미만')
    expect(formatWaitTime(59)).toBe('1분 미만')
  })

  it('1시간 미만은 약 N분', () => {
    expect(formatWaitTime(60)).toBe('약 1분')
    expect(formatWaitTime(300)).toBe('약 5분')
    expect(formatWaitTime(3599)).toBe('약 59분')
  })

  it('1시간 이상은 약 N시간 M분', () => {
    expect(formatWaitTime(3900)).toBe('약 1시간 5분')
    expect(formatWaitTime(3600)).toBe('약 1시간')
  })
})
