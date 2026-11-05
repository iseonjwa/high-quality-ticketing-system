import { describe, expect, it } from 'vitest'
import type { ScheduleSeats } from '@/api/concerts'
import {
  findSeat,
  firstSectionWithSeats,
  lostSeatIds,
  remainingSeats,
  totalPrice,
} from '@/pages/seats/seatSections'

type Sections = ScheduleSeats['sections']

const SECTIONS: Sections = [
  {
    section: 'A',
    grade: 'VIP',
    price: 165000,
    seats: [
      { seatId: 1, row: 1, number: 1, available: false },
      { seatId: 2, row: 1, number: 2, available: true },
    ],
  },
  {
    section: 'B',
    grade: 'R',
    price: 143000,
    seats: [
      { seatId: 3, row: 1, number: 1, available: true },
      { seatId: 4, row: 1, number: 2, available: true },
    ],
  },
]

const SOLD_OUT: Sections = SECTIONS.map((section) => ({
  ...section,
  seats: section.seats.map((seat) => ({ ...seat, available: false })),
}))

describe('remainingSeats', () => {
  it('available인 좌석만 센다', () => {
    expect(remainingSeats(SECTIONS[0])).toBe(1)
    expect(remainingSeats(SECTIONS[1])).toBe(2)
    expect(remainingSeats(SOLD_OUT[1])).toBe(0)
  })
})

describe('findSeat', () => {
  it('구역·등급·가격과 함께 좌석을 준다', () => {
    expect(findSeat(SECTIONS, 4)).toEqual({
      section: 'B',
      grade: 'R',
      price: 143000,
      row: 1,
      number: 2,
      available: true,
    })
    expect(findSeat(SECTIONS, 1)?.available).toBe(false)
  })

  it('없는 좌석은 undefined다', () => {
    expect(findSeat(SECTIONS, 99)).toBeUndefined()
  })
})

describe('lostSeatIds', () => {
  it('available이 아니거나 응답에 없는 좌석을 고른 순서대로 준다', () => {
    expect(lostSeatIds([4, 99, 1, 2], SECTIONS)).toEqual([99, 1])
  })

  it('모두 고를 수 있으면 빈 배열이다', () => {
    expect(lostSeatIds([2, 3], SECTIONS)).toEqual([])
    expect(lostSeatIds([], SECTIONS)).toEqual([])
  })
})

describe('firstSectionWithSeats', () => {
  it('남은 좌석이 있는 첫 구역이다', () => {
    expect(firstSectionWithSeats(SECTIONS)).toBe('A')
    const aSoldOut: Sections = [SOLD_OUT[0], SECTIONS[1]]
    expect(firstSectionWithSeats(aSoldOut)).toBe('B')
  })

  it('모두 매진이면 첫 구역, 구역이 없으면 null이다', () => {
    expect(firstSectionWithSeats(SOLD_OUT)).toBe('A')
    expect(firstSectionWithSeats([])).toBeNull()
  })
})

describe('totalPrice', () => {
  it('고른 좌석의 가격을 더한다', () => {
    expect(totalPrice([2, 3, 4], SECTIONS)).toBe(165000 + 143000 * 2)
    expect(totalPrice([], SECTIONS)).toBe(0)
  })

  it('응답에 없는 좌석은 더하지 않는다', () => {
    expect(totalPrice([2, 99], SECTIONS)).toBe(165000)
  })
})
