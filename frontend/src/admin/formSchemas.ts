import { z } from 'zod'
import type { ConcertCreateInput, ScheduleCreateInput, SeatPriceInput } from '@/api/admin'
import { kstLocalToIso } from '@/lib/kstDateTime'

// reservation-service의 검증 규칙(ConcertCreateRequest, ScheduleCreateRequest, SeatPriceRequest)을
// 화면에서 먼저 확인한다. 서버도 다시 확인하므로 여기가 정본은 아니다. 규칙이 바뀌면 둘을 함께 고친다.

const MAX_TITLE_LENGTH = 200
// schedules.max_per_user·hold_minutes 컬럼이 SMALLINT라 서버 상한이 32767이다
const MAX_SMALLINT = 32767
// schedule_seats.price NUMERIC(15, 0)
const MAX_PRICE = 999_999_999_999_999

export const SEAT_GRADES = ['VIP', 'R', 'S'] as const

export const concertFormSchema = z.object({
  title: z
    .string()
    .trim()
    .min(1, '공연 제목을 입력해 주세요')
    .max(MAX_TITLE_LENGTH, `제목은 ${MAX_TITLE_LENGTH}자까지 쓸 수 있어요`),
  description: z.string(),
})
export type ConcertFormInput = z.input<typeof concertFormSchema>
export type ConcertFormValues = z.output<typeof concertFormSchema>

const requiredInteger = (requiredMessage: string, invalidMessage: string) =>
  z.string().trim().min(1, requiredMessage).regex(/^\d+$/, invalidMessage).transform(Number)

// 비우면 서버 기본값을 쓰는 선택 칸. 넣으면 1~32767 정수다. topic은 조사까지 붙인 칸 이름이다("선점 시간은")
const optionalSmallInt = (topic: string) =>
  z
    .string()
    .trim()
    .refine((value) => value === '' || /^\d+$/.test(value), `${topic} 숫자로 입력해 주세요`)
    .refine(
      (value) => value === '' || (Number(value) >= 1 && Number(value) <= MAX_SMALLINT),
      `${topic} 1~${MAX_SMALLINT} 사이로 입력해 주세요`,
    )

export const scheduleFormSchema = z
  .object({
    venueId: requiredInteger('공연장 번호를 입력해 주세요', '공연장 번호는 숫자로 입력해 주세요')
      .refine((value) => value > 0, '공연장 번호는 1 이상이어야 해요')
      // 안전 범위를 넘으면 JSON 숫자가 반올림되거나(2^53 이상) 서버 Long으로 읽히지 않는다
      .refine((value) => Number.isSafeInteger(value), '공연장 번호를 확인해 주세요'),
    startsAt: z
      .string()
      .min(1, '공연 시작 시각을 입력해 주세요')
      .refine((value) => kstLocalToIso(value) !== null, '시각 형식을 확인해 주세요'),
    openAt: z
      .string()
      .min(1, '오픈 시각을 입력해 주세요')
      .refine((value) => kstLocalToIso(value) !== null, '시각 형식을 확인해 주세요'),
    maxPerUser: optionalSmallInt('1인 최대 매수는'),
    holdMinutes: optionalSmallInt('선점 시간은'),
  })
  .refine(
    (values) => {
      const startsAt = kstLocalToIso(values.startsAt)
      const openAt = kstLocalToIso(values.openAt)
      // 형식이 틀린 칸은 위에서 이미 안내한다
      return startsAt === null || openAt === null || Date.parse(openAt) < Date.parse(startsAt)
    },
    { path: ['openAt'], message: '오픈 시각은 공연 시작보다 앞서야 해요' },
  )
export type ScheduleFormInput = z.input<typeof scheduleFormSchema>
export type ScheduleFormValues = z.output<typeof scheduleFormSchema>

const optionalPrice = z
  .string()
  .trim()
  .refine((value) => value === '' || /^\d+$/.test(value), '가격은 숫자로 입력해 주세요')
  .refine((value) => value === '' || Number(value) >= 1, '가격은 1원 이상으로 입력해 주세요')
  .refine(
    (value) => value === '' || Number(value) <= MAX_PRICE,
    '가격이 너무 커요. 다시 확인해 주세요',
  )

export const seatPriceFormSchema = z
  .object({ VIP: optionalPrice, R: optionalPrice, S: optionalPrice })
  // 특정 칸이 아니라 폼 전체의 규칙이라 root 오류로 둔다. VIP 칸에 붙이면 VIP가 없는 공연장에서 틀린 칸을 가리킨다
  .refine((values) => SEAT_GRADES.some((grade) => values[grade] !== ''), {
    path: ['root'],
    message: '적어도 한 등급의 가격을 입력해 주세요',
  })
export type SeatPriceFormInput = z.input<typeof seatPriceFormSchema>
export type SeatPriceFormValues = z.output<typeof seatPriceFormSchema>

export function toConcertInput(values: ConcertFormValues): ConcertCreateInput {
  const description = values.description.trim()
  return description === ''
    ? { title: values.title }
    : { title: values.title, description: values.description }
}

export function toScheduleInput(values: ScheduleFormValues): ScheduleCreateInput {
  // 검사를 통과한 값이라 변환이 실패할 수 없다
  const startsAt = kstLocalToIso(values.startsAt) as string
  const openAt = kstLocalToIso(values.openAt) as string
  const input: ScheduleCreateInput = { venueId: values.venueId, startsAt, openAt }
  if (values.maxPerUser !== '') {
    input.maxPerUser = Number(values.maxPerUser)
  }
  if (values.holdMinutes !== '') {
    input.holdMinutes = Number(values.holdMinutes)
  }
  return input
}

export function toSeatPrices(values: SeatPriceFormValues): SeatPriceInput[] {
  return SEAT_GRADES.filter((grade) => values[grade] !== '').map((grade) => ({
    grade,
    price: Number(values[grade]),
  }))
}
