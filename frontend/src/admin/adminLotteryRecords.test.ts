import { afterEach, describe, expect, it } from 'vitest'
import { readLotteryIdOfSchedule, saveLotteryOfSchedule } from '@/admin/adminLotteryRecords'

const KEY = 'iseonjwa.adminLotteries'

// 전역 setup은 sessionStorage만 비운다
afterEach(() => {
  localStorage.clear()
})

describe('adminLotteryRecords', () => {
  it('기록이 없으면 null이다', () => {
    expect(readLotteryIdOfSchedule(31)).toBeNull()
  })

  it('저장한 회차의 추첨 번호를 돌려주고 다른 회차는 모른다', () => {
    saveLotteryOfSchedule(31, 9)

    expect(readLotteryIdOfSchedule(31)).toBe(9)
    expect(readLotteryIdOfSchedule(32)).toBeNull()
  })

  it('여러 회차를 따로 기억하고, 같은 회차를 다시 저장하면 바꾼다', () => {
    saveLotteryOfSchedule(31, 9)
    saveLotteryOfSchedule(32, 10)
    saveLotteryOfSchedule(31, 11)

    expect(readLotteryIdOfSchedule(31)).toBe(11)
    expect(readLotteryIdOfSchedule(32)).toBe(10)
    expect(JSON.parse(localStorage.getItem(KEY) as string)).toEqual({ '31': 11, '32': 10 })
  })

  it.each([
    ['JSON이 아닌 값', 'not json'],
    ['배열', '[1,2]'],
    ['숫자가 아닌 추첨 번호', '{"31":"x"}'],
    ['0 이하의 추첨 번호', '{"31":0}'],
    ['숫자가 아닌 회차 키', '{"abc":3}'],
  ])('깨진 값(%s)은 지우고 없는 것으로 읽는다', (_name, raw) => {
    localStorage.setItem(KEY, raw)

    expect(readLotteryIdOfSchedule(31)).toBeNull()
    expect(localStorage.getItem(KEY)).toBeNull()
  })

  it('깨진 값 위에 저장하면 새 기록만 남는다', () => {
    localStorage.setItem(KEY, '{"31":"x"}')

    saveLotteryOfSchedule(32, 10)

    expect(JSON.parse(localStorage.getItem(KEY) as string)).toEqual({ '32': 10 })
  })
})
