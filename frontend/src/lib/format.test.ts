import { describe, expect, it } from 'vitest'
import {
  formatCountdown,
  formatDate,
  formatDateTime,
  formatRemaining,
  formatShortDateTime,
  formatWaitTime,
  formatWon,
  kstDaysUntil,
  kstParts,
} from '@/lib/format'

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

describe('formatDateTime', () => {
  it('서버가 준 Instant를 한국 시각의 월·일·요일·시각으로 쓴다', () => {
    expect(formatDateTime('2026-11-20T10:00:00Z')).toBe('2026.11.20 (금) 19:00')
  })

  it('한국 시각으로 날짜가 바뀌는 시각도 한국 날짜로 쓴다', () => {
    expect(formatDateTime('2026-11-02T15:30:00Z')).toBe('2026.11.03 (화) 00:30')
  })

  it('+09:00으로 온 시각도 같은 값이다', () => {
    expect(formatDateTime('2026-11-20T19:00:00+09:00')).toBe('2026.11.20 (금) 19:00')
  })
})

describe('kstParts', () => {
  it('Instant를 한국 시각의 연·월·일·요일·시·분 문자열로 나눈다', () => {
    expect(kstParts('2026-11-20T10:00:00Z')).toEqual({
      year: '2026',
      month: '11',
      day: '20',
      weekday: '금',
      hour: '19',
      minute: '00',
    })
  })

  it('한국 자정을 넘기는 순간 날짜와 요일이 바뀌고 시는 24가 아니라 00이다', () => {
    expect(kstParts('2026-11-04T14:59:59Z')).toMatchObject({ day: '04', weekday: '수', hour: '23' })
    expect(kstParts('2026-11-04T15:00:00Z')).toMatchObject({ day: '05', weekday: '목', hour: '00' })
  })

  it('연말에는 해가 바뀐다', () => {
    expect(kstParts('2026-12-31T15:00:00Z')).toMatchObject({ year: '2027', month: '01', day: '01' })
  })
})

describe('formatDate', () => {
  it('시각 없이 한국 날짜와 요일만 쓴다', () => {
    expect(formatDate('2026-11-20T10:00:00Z')).toBe('2026.11.20 (금)')
    expect(formatDate('2026-11-04T15:00:00Z')).toBe('2026.11.05 (목)')
  })
})

describe('formatShortDateTime', () => {
  it('연도를 빼고 월.일 (요일) 시:분으로 쓴다', () => {
    expect(formatShortDateTime('2026-11-20T10:00:00Z')).toBe('11.20 (금) 19:00')
    expect(formatShortDateTime('2026-11-04T15:00:00Z')).toBe('11.05 (목) 00:00')
  })
})

describe('kstDaysUntil', () => {
  const now = Date.parse('2026-11-04T14:59:59Z') // 한국 11월 4일 23:59:59

  it('같은 한국 날짜면 시각이 지났어도 0이다', () => {
    expect(kstDaysUntil('2026-11-04T14:59:59Z', now)).toBe(0)
    expect(kstDaysUntil('2026-11-03T15:00:00Z', now)).toBe(0) // 한국 11월 4일 00:00
  })

  it('한국 자정 1초 전과 자정은 다른 날이라 1초 차이로도 하루가 된다', () => {
    expect(kstDaysUntil('2026-11-04T15:00:00Z', now)).toBe(1)
    expect(kstDaysUntil('2026-11-04T14:59:59Z', Date.parse('2026-11-04T15:00:00Z'))).toBe(-1)
  })

  it('지난 날은 음수, 먼 날은 날짜 차이다', () => {
    expect(kstDaysUntil('2026-11-03T14:59:59Z', now)).toBe(-1)
    expect(kstDaysUntil('2026-10-25T00:00:00Z', now)).toBe(-10)
    expect(kstDaysUntil('2026-11-07T00:00:00Z', now)).toBe(3)
  })

  it('+09:00으로 온 시각도 같은 날짜로 센다', () => {
    expect(kstDaysUntil('2026-11-05T00:00:00+09:00', now)).toBe(1)
  })
})
