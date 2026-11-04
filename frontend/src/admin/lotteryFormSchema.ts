import { z } from 'zod'
import { SEAT_GRADES } from '@/admin/formSchemas'
import type { LotteryCreateInput, LotteryUpdateInput } from '@/api/adminLotteries'
import { kstLocalToIso } from '@/lib/kstDateTime'

// 추첨 설정 입력 검사(reservation-service LotteryCreateRequest·LotteryUpdateRequest와 Lottery.LotterySettings.validate).
// 서버도 다시 확인하므로 여기가 정본은 아니다. 규칙이 바뀌면 함께 고친다.

// 추첨 뒤 배정·자동결제·승계가 끝날 시간. 추첨은 회차 시작 1시간 전까지여야 한다(서버 MAX_SETTLE_AFTER_DRAW)
const SETTLE_AFTER_DRAW_MILLIS = 60 * 60_000
// lotteries.seat_count·waitlist_count는 INT다
const MAX_INT = 2_147_483_647

// 추첨 권장 시간대(한국 시간 03:00 이상 05:00 미만, 서버 DrawTimeWindow). 밖이어도 막지 않고 알린다
export function isLowTrafficDrawTime(local: string): boolean {
  const iso = kstLocalToIso(local)
  if (iso === null) {
    return true
  }
  const hour = Number(local.slice(11, 13))
  return hour >= 3 && hour < 5
}

const wholeNumber = (topic: string, min: number) =>
  z
    .string()
    .trim()
    .refine((value) => value === '' || /^\d+$/.test(value), `${topic} 숫자로 입력해 주세요`)
    .refine(
      (value) => value === '' || (Number(value) >= min && Number(value) <= MAX_INT),
      min === 0 ? `${topic} 0 이상으로 입력해 주세요` : `${topic} ${min} 이상으로 입력해 주세요`,
    )

const localDateTime = (requiredMessage: string) =>
  z
    .string()
    .min(1, requiredMessage)
    .refine((value) => kstLocalToIso(value) !== null, '시각 형식을 확인해 주세요')

const millisOf = (local: string) => {
  const iso = kstLocalToIso(local)
  return iso === null ? null : Date.parse(iso)
}

export interface LotteryFormContext {
  // 설정(create)은 등급·예비 순번 수가 필수다. 수정(edit)은 비우면 바꾸지 않는다(공개 조회에 현재 값이 없다)
  mode: 'create' | 'edit'
  // 회차 시작. 알면(설정 화면) 추첨이 1시간 전까지인지 먼저 본다. 수정 화면은 회차 시각을 모른다
  scheduleStartsAt: string | null
}

export function lotteryFormSchema({ mode, scheduleStartsAt }: LotteryFormContext) {
  const required = mode === 'create'
  return z
    .object({
      sourceGrade: z
        .enum(['', ...SEAT_GRADES])
        .refine((value) => !required || value !== '', '추첨석으로 뗄 등급을 골라 주세요'),
      seatCount: wholeNumber('추첨석 수는', 1).refine(
        (value) => value !== '',
        '추첨석 수를 입력해 주세요',
      ),
      waitlistCount: wholeNumber('예비 순번 수는', 0).refine(
        (value) => !required || value !== '',
        '예비 순번 수를 입력해 주세요',
      ),
      maxPerEntry: z.enum(['1', '2']),
      entryStartAt: localDateTime('응모 시작 시각을 입력해 주세요'),
      entryEndAt: localDateTime('응모 마감 시각을 입력해 주세요'),
      drawAt: localDateTime('추첨 시각을 입력해 주세요'),
    })
    .superRefine((values, context) => {
      const start = millisOf(values.entryStartAt)
      const end = millisOf(values.entryEndAt)
      const draw = millisOf(values.drawAt)
      // 형식이 틀린 칸은 위에서 이미 안내한다
      if (start !== null && start <= Date.now()) {
        context.addIssue({
          code: 'custom',
          path: ['entryStartAt'],
          message: '응모 시작은 지금보다 뒤여야 해요. 응모가 시작되면 설정을 바꿀 수 없어요',
        })
      }
      if (start !== null && end !== null && end <= start) {
        context.addIssue({
          code: 'custom',
          path: ['entryEndAt'],
          message: '응모 마감은 응모 시작보다 뒤여야 해요',
        })
      }
      if (end !== null && draw !== null && draw < end) {
        context.addIssue({
          code: 'custom',
          path: ['drawAt'],
          message: '추첨은 응모 마감과 같거나 뒤여야 해요',
        })
      }
      const startsAt = scheduleStartsAt === null ? null : Date.parse(scheduleStartsAt)
      if (draw !== null && startsAt !== null && draw + SETTLE_AFTER_DRAW_MILLIS > startsAt) {
        context.addIssue({
          code: 'custom',
          path: ['drawAt'],
          message: '추첨은 공연 시작 1시간 전까지여야 해요(배정·자동결제 시간)',
        })
      }
    })
}

export type LotteryFormInput = z.input<ReturnType<typeof lotteryFormSchema>>
export type LotteryFormValues = z.output<ReturnType<typeof lotteryFormSchema>>
type LotteryField = keyof LotteryFormValues

export const LOTTERY_FIELDS: LotteryField[] = [
  'sourceGrade',
  'seatCount',
  'waitlistCount',
  'maxPerEntry',
  'entryStartAt',
  'entryEndAt',
  'drawAt',
]

export function toLotteryCreateInput(values: LotteryFormValues): LotteryCreateInput {
  // 검사를 통과한 값이라 변환이 실패할 수 없다
  return {
    sourceGrade: values.sourceGrade as LotteryCreateInput['sourceGrade'],
    seatCount: Number(values.seatCount),
    waitlistCount: Number(values.waitlistCount),
    maxPerEntry: Number(values.maxPerEntry),
    entryStartAt: kstLocalToIso(values.entryStartAt) as string,
    entryEndAt: kstLocalToIso(values.entryEndAt) as string,
    drawAt: kstLocalToIso(values.drawAt) as string,
  }
}

// 바꾼 칸만 보낸다(서버는 보낸 필드만 고친다). 등급·예비 순번 수는 비우면 보내지 않는다
export function toLotteryUpdateInput(
  values: LotteryFormValues,
  changed: Partial<Record<LotteryField, boolean | undefined>>,
): LotteryUpdateInput {
  const full = {
    ...toLotteryCreateInput({
      ...values,
      sourceGrade: values.sourceGrade || 'VIP',
      waitlistCount: values.waitlistCount || '0',
    }),
  }
  const input: LotteryUpdateInput = {}
  for (const field of LOTTERY_FIELDS) {
    if (!changed[field]) {
      continue
    }
    if ((field === 'sourceGrade' || field === 'waitlistCount') && values[field] === '') {
      continue
    }
    Object.assign(input, { [field]: full[field] })
  }
  return input
}
