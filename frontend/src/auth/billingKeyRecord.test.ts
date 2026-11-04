import { afterEach, describe, expect, it } from 'vitest'
import {
  readBillingKeyRecord,
  removeBillingKeyRecord,
  saveBillingKeyRecord,
} from '@/auth/billingKeyRecord'

const KEY_OF_42 = 'iseonjwa.billingKey.42'
const RECORD = { cardLast4: '4242', registeredAt: '2026-11-04T12:00:00.000Z' }

// 전역 setup은 sessionStorage만 비운다
afterEach(() => {
  localStorage.clear()
})

describe('billingKeyRecord', () => {
  it('저장한 기록을 그대로 읽는다', () => {
    saveBillingKeyRecord(42, RECORD)

    expect(readBillingKeyRecord(42)).toEqual(RECORD)
  })

  it('카드 끝 네 자리가 없는(null) 기록도 읽는다', () => {
    saveBillingKeyRecord(42, { ...RECORD, cardLast4: null })

    expect(readBillingKeyRecord(42)).toEqual({ ...RECORD, cardLast4: null })
  })

  it('기록이 없으면 null이다', () => {
    expect(readBillingKeyRecord(42)).toBeNull()
  })

  it('사용자마다 따로 둔다', () => {
    saveBillingKeyRecord(42, RECORD)

    expect(readBillingKeyRecord(43)).toBeNull()

    saveBillingKeyRecord(43, { ...RECORD, cardLast4: '1111' })
    removeBillingKeyRecord(42)

    expect(readBillingKeyRecord(42)).toBeNull()
    expect(readBillingKeyRecord(43)?.cardLast4).toBe('1111')
  })

  it('깨진 값은 null을 주고 지운다', () => {
    for (const broken of ['{깨짐', '"문자열"', '{"cardLast4":4242,"registeredAt":"x"}', '{}']) {
      localStorage.setItem(KEY_OF_42, broken)

      expect(readBillingKeyRecord(42)).toBeNull()
      expect(localStorage.getItem(KEY_OF_42)).toBeNull()
    }
  })

  it('지운 뒤에는 읽히지 않는다', () => {
    saveBillingKeyRecord(42, RECORD)
    removeBillingKeyRecord(42)

    expect(readBillingKeyRecord(42)).toBeNull()
  })
})
