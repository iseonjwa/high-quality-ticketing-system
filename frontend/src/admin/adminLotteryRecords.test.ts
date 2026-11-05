import { afterEach, describe, expect, it } from 'vitest'
import {
  forgetLottery,
  forgetLotteryOfSchedule,
  readLotteryIdOfSchedule,
  saveLotteryOfSchedule,
} from '@/admin/adminLotteryRecords'

// 키는 실행 모드(import.meta.env.MODE)별이다. 테스트의 모드는 test다
const KEY = 'iseonjwa.adminLotteries.test'

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

  it('실행 모드별 키에 저장한다', () => {
    expect(import.meta.env.MODE).toBe('test')

    saveLotteryOfSchedule(31, 9)

    expect(localStorage.getItem(KEY)).not.toBeNull()
    expect(localStorage.getItem('iseonjwa.adminLotteries')).toBeNull()
  })

  it('다른 모드(dev, dev:mock)나 모드 없는 옛 키의 기록은 읽지 않는다', () => {
    localStorage.setItem('iseonjwa.adminLotteries', '{"31":9}')
    localStorage.setItem('iseonjwa.adminLotteries.development', '{"31":9}')
    localStorage.setItem('iseonjwa.adminLotteries.mock', '{"31":9}')

    expect(readLotteryIdOfSchedule(31)).toBeNull()
  })

  it('회차의 기록을 지운다', () => {
    saveLotteryOfSchedule(31, 9)
    saveLotteryOfSchedule(32, 10)

    forgetLotteryOfSchedule(31)

    expect(readLotteryIdOfSchedule(31)).toBeNull()
    expect(readLotteryIdOfSchedule(32)).toBe(10)
  })

  it('추첨 번호로 지우면 그 번호를 가리키는 기록을 모두 지우고 다른 번호는 남긴다', () => {
    saveLotteryOfSchedule(31, 9)
    saveLotteryOfSchedule(32, 9)
    saveLotteryOfSchedule(33, 10)

    forgetLottery(9)

    expect(readLotteryIdOfSchedule(31)).toBeNull()
    expect(readLotteryIdOfSchedule(32)).toBeNull()
    expect(readLotteryIdOfSchedule(33)).toBe(10)
  })
})
