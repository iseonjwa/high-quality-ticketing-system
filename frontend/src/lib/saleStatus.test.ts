import { describe, expect, it } from 'vitest'
import { saleStatusAt } from '@/lib/saleStatus'

const times = { openAt: '2026-11-03T11:00:00Z', startsAt: '2026-11-20T10:00:00Z' }
const at = (iso: string) => new Date(iso).getTime()

describe('saleStatusAt', () => {
  it('오픈 시각 전은 오픈 예정, 오픈 시각부터는 예매 중이다', () => {
    expect(saleStatusAt(times, at('2026-11-03T10:59:59.999Z'))).toBe('BEFORE_OPEN')
    expect(saleStatusAt(times, at('2026-11-03T11:00:00Z'))).toBe('ON_SALE')
  })

  it('공연이 시작하면 판매 종료다', () => {
    expect(saleStatusAt(times, at('2026-11-20T10:00:00Z'))).toBe('CLOSED')
  })

  it('남은 회차가 없으면 판매 종료다', () => {
    expect(saleStatusAt({ openAt: null, startsAt: null }, Date.now())).toBe('CLOSED')
  })
})
