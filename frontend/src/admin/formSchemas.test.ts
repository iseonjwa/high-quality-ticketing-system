import { describe, expect, it } from 'vitest'
import {
  concertFormSchema,
  scheduleFormSchema,
  seatPriceFormSchema,
  toConcertInput,
  toScheduleInput,
  toSeatPrices,
} from '@/admin/formSchemas'

function messageAt(result: ReturnType<typeof scheduleFormSchema.safeParse>, field: string) {
  return result.error?.issues.find((issue) => issue.path[0] === field)?.message
}

describe('공연 입력', () => {
  it('제목의 앞뒤 공백을 지우고 설명은 비워도 된다', () => {
    const parsed = concertFormSchema.parse({ title: '  콘서트  ', description: '' })
    expect(parsed.title).toBe('콘서트')
    expect(toConcertInput(parsed)).toEqual({ title: '콘서트' })
  })

  it('설명이 있으면 그대로 보낸다', () => {
    const parsed = concertFormSchema.parse({ title: 'a', description: '설명' })
    expect(toConcertInput(parsed)).toEqual({ title: 'a', description: '설명' })
  })

  it('공백만 있거나 200자를 넘으면 실패한다', () => {
    expect(concertFormSchema.safeParse({ title: '   ', description: '' }).success).toBe(false)
    expect(concertFormSchema.safeParse({ title: 'a'.repeat(200), description: '' }).success).toBe(
      true,
    )
    expect(concertFormSchema.safeParse({ title: 'a'.repeat(201), description: '' }).success).toBe(
      false,
    )
  })
})

describe('회차 입력', () => {
  const valid = {
    venueId: '1',
    startsAt: '2026-12-20T19:00',
    openAt: '2026-12-10T20:00',
    maxPerUser: '',
    holdMinutes: '',
  }

  it('통과하면 숫자와 한국 시각 ISO로 바꾸고 빈 선택 값은 뺀다', () => {
    const parsed = scheduleFormSchema.parse(valid)
    expect(toScheduleInput(parsed)).toEqual({
      venueId: 1,
      startsAt: '2026-12-20T19:00:00+09:00',
      openAt: '2026-12-10T20:00:00+09:00',
    })
  })

  it('선택 값을 넣으면 숫자로 보낸다', () => {
    const parsed = scheduleFormSchema.parse({ ...valid, maxPerUser: '2', holdMinutes: '10' })
    expect(toScheduleInput(parsed)).toMatchObject({ maxPerUser: 2, holdMinutes: 10 })
  })

  it('오픈이 시작과 같거나 늦으면 openAt 칸에 안내한다', () => {
    for (const openAt of ['2026-12-20T19:00', '2026-12-21T19:00']) {
      const result = scheduleFormSchema.safeParse({ ...valid, openAt })
      expect(messageAt(result, 'openAt')).toBe('오픈 시각은 공연 시작보다 앞서야 해요')
    }
  })

  it('공연장 번호는 양의 정수여야 한다', () => {
    for (const venueId of ['', '0', '-1', '1.5', 'a']) {
      expect(scheduleFormSchema.safeParse({ ...valid, venueId }).success).toBe(false)
    }
  })

  it('공연장 번호가 안전한 정수 범위를 넘으면 확인 문구로 거절한다', () => {
    for (const venueId of ['9007199254740993', '99999999999999999999']) {
      const result = scheduleFormSchema.safeParse({ ...valid, venueId })
      expect(messageAt(result, 'venueId')).toBe('공연장 번호를 확인해 주세요')
    }
    expect(scheduleFormSchema.safeParse({ ...valid, venueId: '9007199254740991' }).success).toBe(
      true,
    )
  })

  it('시각이 비었거나 형식이 틀리면 실패한다', () => {
    expect(scheduleFormSchema.safeParse({ ...valid, startsAt: '' }).success).toBe(false)
    expect(scheduleFormSchema.safeParse({ ...valid, openAt: '2026-13-01T10:00' }).success).toBe(
      false,
    )
  })

  it('선택 칸 오류 문구는 칸 이름의 조사까지 맞다', () => {
    const labels = { maxPerUser: '1인 최대 매수는', holdMinutes: '선점 시간은' } as const
    for (const field of ['maxPerUser', 'holdMinutes'] as const) {
      expect(messageAt(scheduleFormSchema.safeParse({ ...valid, [field]: 'x' }), field)).toBe(
        `${labels[field]} 숫자로 입력해 주세요`,
      )
      expect(messageAt(scheduleFormSchema.safeParse({ ...valid, [field]: '0' }), field)).toBe(
        `${labels[field]} 1~32767 사이로 입력해 주세요`,
      )
    }
    expect(
      messageAt(scheduleFormSchema.safeParse({ ...valid, maxPerUser: 'x' }), 'maxPerUser'),
    ).toBe('1인 최대 매수는 숫자로 입력해 주세요')
    expect(
      messageAt(scheduleFormSchema.safeParse({ ...valid, holdMinutes: '0' }), 'holdMinutes'),
    ).toBe('선점 시간은 1~32767 사이로 입력해 주세요')
  })

  it('1인당 예매 수와 선점 시간은 1~32767 정수만 받는다', () => {
    for (const field of ['maxPerUser', 'holdMinutes'] as const) {
      expect(scheduleFormSchema.safeParse({ ...valid, [field]: '32767' }).success).toBe(true)
      for (const bad of ['0', '32768', '1.5', '-1', 'x']) {
        expect(scheduleFormSchema.safeParse({ ...valid, [field]: bad }).success).toBe(false)
      }
    }
  })
})

describe('좌석 가격 입력', () => {
  const empty = { VIP: '', R: '', S: '' }

  it('넣은 등급만 가격으로 바꾼다', () => {
    const parsed = seatPriceFormSchema.parse({ VIP: '165000', R: '', S: '112000' })
    expect(toSeatPrices(parsed)).toEqual([
      { grade: 'VIP', price: 165000 },
      { grade: 'S', price: 112000 },
    ])
  })

  it('모두 비면 칸이 아니라 root 오류로 알린다', () => {
    const result = seatPriceFormSchema.safeParse(empty)
    expect(result.success).toBe(false)
    expect(result.error?.issues.map((issue) => [issue.path, issue.message])).toEqual([
      [['root'], '적어도 한 등급의 가격을 입력해 주세요'],
    ])
  })

  it('가격 상한을 넘으면 하한과 다른 문구로 알린다', () => {
    const over = seatPriceFormSchema.safeParse({ ...empty, VIP: '1000000000000000' })
    expect(over.error?.issues.map((issue) => issue.message)).toEqual([
      '가격이 너무 커요. 다시 확인해 주세요',
    ])
    const zero = seatPriceFormSchema.safeParse({ ...empty, VIP: '0' })
    expect(zero.error?.issues.map((issue) => issue.message)).toEqual([
      '가격은 1원 이상으로 입력해 주세요',
    ])
    expect(seatPriceFormSchema.safeParse({ ...empty, VIP: '999999999999999' }).success).toBe(true)
  })

  it('0이나 음수, 소수, 숫자가 아닌 값은 실패한다', () => {
    for (const bad of ['0', '-1', '1.5', 'abc']) {
      expect(seatPriceFormSchema.safeParse({ ...empty, VIP: bad }).success).toBe(false)
    }
  })
})
