import { describe, expect, it } from 'vitest'
import {
  isLowTrafficDrawTime,
  lotteryFormSchema,
  toLotteryCreateInput,
  toLotteryUpdateInput,
  type LotteryFormInput,
} from '@/admin/lotteryFormSchema'

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR
const KST_OFFSET = 9 * HOUR

// datetime-local 칸의 한국 시각 글자("2026-11-20T10:00")
const kstLocal = (millis: number) => new Date(millis + KST_OFFSET).toISOString().slice(0, 16)

// 회차는 열흘 뒤 시작한다. 응모 내일, 마감 이틀 뒤, 추첨 사흘 뒤가 기본이다
function baseInput(overrides: Partial<LotteryFormInput> = {}): LotteryFormInput {
  const now = Date.now()
  return {
    sourceGrade: 'VIP',
    seatCount: '10',
    waitlistCount: '5',
    maxPerEntry: '2',
    entryStartAt: kstLocal(now + DAY),
    entryEndAt: kstLocal(now + 2 * DAY),
    drawAt: kstLocal(now + 3 * DAY),
    ...overrides,
  }
}

const SCHEDULE_STARTS_AT = () => new Date(Date.now() + 10 * DAY).toISOString()

function create(overrides: Partial<LotteryFormInput> = {}, startsAt: string | null = null) {
  return lotteryFormSchema({
    mode: 'create',
    scheduleStartsAt: startsAt ?? SCHEDULE_STARTS_AT(),
  }).safeParse(baseInput(overrides))
}

function edit(overrides: Partial<LotteryFormInput> = {}) {
  return lotteryFormSchema({ mode: 'edit', scheduleStartsAt: null }).safeParse(baseInput(overrides))
}

// 칸마다 첫 안내만 본다(react-hook-form은 칸의 첫 오류를 보인다)
function messagesOf(result: { error?: { issues: { path: PropertyKey[]; message: string }[] } }) {
  const messages: Record<string, string> = {}
  for (const issue of result.error?.issues ?? []) {
    messages[String(issue.path[0])] ??= issue.message
  }
  return messages
}

describe('추첨 설정 입력: 칸별 규칙', () => {
  it('올바른 값이면 통과한다', () => {
    expect(create().success).toBe(true)
  })

  it('등급·추첨석 수·예비 순번 수가 비면 칸마다 안내한다', () => {
    const result = create({ sourceGrade: '', seatCount: '', waitlistCount: '' })

    expect(messagesOf(result)).toMatchObject({
      sourceGrade: '추첨석으로 뗄 등급을 골라 주세요',
      seatCount: '추첨석 수를 입력해 주세요',
      waitlistCount: '예비 순번 수를 입력해 주세요',
    })
  })

  it('추첨석 수는 1 이상의 정수이고 INT 범위 안이다', () => {
    expect(messagesOf(create({ seatCount: '0' })).seatCount).toBe(
      '추첨석 수는 1 이상으로 입력해 주세요',
    )
    expect(messagesOf(create({ seatCount: '1.5' })).seatCount).toBe(
      '추첨석 수는 숫자로 입력해 주세요',
    )
    expect(messagesOf(create({ seatCount: 'abc' })).seatCount).toBe(
      '추첨석 수는 숫자로 입력해 주세요',
    )
    expect(messagesOf(create({ seatCount: '2147483648' })).seatCount).toBe(
      '추첨석 수는 1 이상으로 입력해 주세요',
    )
    expect(create({ seatCount: '1' }).success).toBe(true)
    expect(create({ seatCount: '2147483647' }).success).toBe(true)
  })

  it('예비 순번 수는 0도 된다', () => {
    expect(create({ waitlistCount: '0' }).success).toBe(true)
    expect(messagesOf(create({ waitlistCount: '-1' })).waitlistCount).toBe(
      '예비 순번 수는 숫자로 입력해 주세요',
    )
  })

  it('앞뒤 공백은 지우고 읽는다', () => {
    expect(create({ seatCount: ' 10 ', waitlistCount: ' 5 ' }).success).toBe(true)
  })

  it('한 번에 응모할 매수는 1 또는 2만 된다', () => {
    expect(create({ maxPerEntry: '1' }).success).toBe(true)
    expect(create({ maxPerEntry: '3' as '1' }).success).toBe(false)
  })

  it('시각 칸이 비면 칸마다 안내하고, 달력에 없는 시각은 형식 안내를 한다', () => {
    const empty = create({ entryStartAt: '', entryEndAt: '', drawAt: '' })
    expect(messagesOf(empty)).toMatchObject({
      entryStartAt: '응모 시작 시각을 입력해 주세요',
      entryEndAt: '응모 마감 시각을 입력해 주세요',
      drawAt: '추첨 시각을 입력해 주세요',
    })
    expect(messagesOf(create({ drawAt: '2026-02-30T10:00' })).drawAt).toBe(
      '시각 형식을 확인해 주세요',
    )
  })
})

describe('추첨 설정 입력: 값 사이 규칙', () => {
  it('응모 시작은 지금보다 뒤여야 한다', () => {
    const now = Date.now()

    expect(messagesOf(create({ entryStartAt: kstLocal(now - HOUR) })).entryStartAt).toMatch(
      /응모 시작은 지금보다 뒤여야 해요/,
    )
    expect(create({ entryStartAt: kstLocal(now + 5 * MINUTE) }).success).toBe(true)
  })

  it('응모 마감은 응모 시작보다 뒤여야 한다(같아도 안 된다)', () => {
    const now = Date.now()
    const start = kstLocal(now + DAY)

    expect(messagesOf(create({ entryStartAt: start, entryEndAt: start })).entryEndAt).toBe(
      '응모 마감은 응모 시작보다 뒤여야 해요',
    )
    expect(
      messagesOf(create({ entryStartAt: start, entryEndAt: kstLocal(now + DAY - HOUR) }))
        .entryEndAt,
    ).toBe('응모 마감은 응모 시작보다 뒤여야 해요')
  })

  it('추첨은 응모 마감과 같거나 뒤여야 한다', () => {
    const now = Date.now()
    const end = kstLocal(now + 2 * DAY)

    expect(create({ entryEndAt: end, drawAt: end }).success).toBe(true)
    expect(
      messagesOf(create({ entryEndAt: end, drawAt: kstLocal(now + 2 * DAY - 10 * MINUTE) })).drawAt,
    ).toBe('추첨은 응모 마감과 같거나 뒤여야 해요')
  })

  it('추첨 + 60분이 회차 시작 이전이어야 한다(같으면 된다)', () => {
    const startsAtMillis = Math.floor((Date.now() + 10 * DAY) / MINUTE) * MINUTE
    const startsAt = new Date(startsAtMillis).toISOString()

    expect(create({ drawAt: kstLocal(startsAtMillis - HOUR) }, startsAt).success).toBe(true)
    expect(
      messagesOf(create({ drawAt: kstLocal(startsAtMillis - HOUR + MINUTE) }, startsAt)).drawAt,
    ).toBe('추첨은 공연 시작 1시간 전까지여야 해요(배정·자동결제 시간)')
  })

  it('수정 화면은 회차 시각을 모르므로 공연 시작 규칙은 보지 않는다', () => {
    expect(edit({ drawAt: kstLocal(Date.now() + 400 * DAY) }).success).toBe(true)
  })
})

describe('설정과 수정의 차이', () => {
  it('수정은 등급과 예비 순번 수를 비워도 된다', () => {
    expect(edit({ sourceGrade: '', waitlistCount: '' }).success).toBe(true)
  })

  it('수정도 추첨석 수는 비울 수 없다', () => {
    expect(messagesOf(edit({ seatCount: '' })).seatCount).toBe('추첨석 수를 입력해 주세요')
  })

  it('수정도 비우지 않은 예비 순번 수는 형식을 본다', () => {
    expect(messagesOf(edit({ waitlistCount: 'x' })).waitlistCount).toBe(
      '예비 순번 수는 숫자로 입력해 주세요',
    )
  })
})

describe('isLowTrafficDrawTime', () => {
  it.each([
    ['2026-12-01T02:59', false],
    ['2026-12-01T03:00', true],
    ['2026-12-01T04:59', true],
    ['2026-12-01T05:00', false],
    ['2026-12-01T00:00', false],
    ['2026-12-01T23:59', false],
  ])('%s → %s', (local, expected) => {
    expect(isLowTrafficDrawTime(local)).toBe(expected)
  })

  it('읽을 수 없는 시각은 권장 시간대 안내를 띄우지 않는다', () => {
    expect(isLowTrafficDrawTime('')).toBe(true)
    expect(isLowTrafficDrawTime('2026-02-30T04:00')).toBe(true)
  })
})

describe('toLotteryCreateInput', () => {
  it('숫자와 한국 오프셋이 붙은 ISO 시각으로 바꾼다', () => {
    const parsed = lotteryFormSchema({ mode: 'create', scheduleStartsAt: null }).parse(
      baseInput({
        sourceGrade: 'R',
        seatCount: '12',
        waitlistCount: '0',
        maxPerEntry: '1',
        entryStartAt: '2099-12-01T10:00',
        entryEndAt: '2099-12-02T10:00',
        drawAt: '2099-12-03T04:00',
      }),
    )

    expect(toLotteryCreateInput(parsed)).toEqual({
      sourceGrade: 'R',
      seatCount: 12,
      waitlistCount: 0,
      maxPerEntry: 1,
      entryStartAt: '2099-12-01T10:00:00+09:00',
      entryEndAt: '2099-12-02T10:00:00+09:00',
      drawAt: '2099-12-03T04:00:00+09:00',
    })
  })
})

describe('toLotteryUpdateInput', () => {
  const values = () =>
    lotteryFormSchema({ mode: 'edit', scheduleStartsAt: null }).parse(
      baseInput({
        sourceGrade: 'S',
        waitlistCount: '7',
        seatCount: '20',
        maxPerEntry: '1',
        entryStartAt: '2099-12-01T10:00',
        entryEndAt: '2099-12-02T10:00',
        drawAt: '2099-12-03T04:00',
      }),
    )

  it('바꾼 칸만 보낸다', () => {
    expect(toLotteryUpdateInput(values(), { seatCount: true })).toEqual({ seatCount: 20 })
    expect(toLotteryUpdateInput(values(), { maxPerEntry: true, drawAt: true })).toEqual({
      maxPerEntry: 1,
      drawAt: '2099-12-03T04:00:00+09:00',
    })
  })

  it('바꾼 칸이 없으면 빈 본문이다', () => {
    expect(toLotteryUpdateInput(values(), {})).toEqual({})
    expect(toLotteryUpdateInput(values(), { seatCount: false, drawAt: undefined })).toEqual({})
  })

  it('바꾼 등급과 예비 순번 수는 보낸다', () => {
    expect(toLotteryUpdateInput(values(), { sourceGrade: true, waitlistCount: true })).toEqual({
      sourceGrade: 'S',
      waitlistCount: 7,
    })
  })

  it('비운 등급과 예비 순번 수는 바꿨다고 표시돼도 보내지 않는다', () => {
    const emptied = lotteryFormSchema({ mode: 'edit', scheduleStartsAt: null }).parse(
      baseInput({ sourceGrade: '', waitlistCount: '' }),
    )

    expect(
      toLotteryUpdateInput(emptied, { sourceGrade: true, waitlistCount: true, seatCount: true }),
    ).toEqual({ seatCount: 10 })
  })

  it('예비 순번 수를 0으로 바꾸면 0을 보낸다', () => {
    const zero = lotteryFormSchema({ mode: 'edit', scheduleStartsAt: null }).parse(
      baseInput({ waitlistCount: '0' }),
    )

    expect(toLotteryUpdateInput(zero, { waitlistCount: true })).toEqual({ waitlistCount: 0 })
  })
})
