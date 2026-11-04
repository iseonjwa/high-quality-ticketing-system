import type { UseFormReturn } from 'react-hook-form'
import {
  isLowTrafficDrawTime,
  type LotteryFormInput,
  type LotteryFormValues,
} from '@/admin/lotteryFormSchema'
import { SEAT_GRADES } from '@/admin/formSchemas'
import { SelectField } from '@/components/form/SelectField'
import { TextField } from '@/components/form/TextField'
import { Alert } from '@/components/ui/alert'

// 추첨 설정 칸 묶음. 설정 화면과 수정 화면이 같이 쓴다
export function LotteryFields({
  form,
  mode,
}: {
  form: UseFormReturn<LotteryFormInput, unknown, LotteryFormValues>
  mode: 'create' | 'edit'
}) {
  const { errors } = form.formState
  const drawAt = form.watch('drawAt')
  const gradeOptions = [
    { value: '', label: mode === 'create' ? '등급 고르기' : '바꾸지 않음' },
    ...SEAT_GRADES.map((grade) => ({ value: grade, label: `${grade}석` })),
  ]

  return (
    <>
      <div className="grid gap-5 @min-[40rem]:grid-cols-2">
        <SelectField
          label="추첨석으로 뗄 등급"
          options={gradeOptions}
          error={errors.sourceGrade?.message}
          description={
            mode === 'create'
              ? '이 등급 좌석 일부를 추첨석으로 바꿔요. 좌석을 먼저 만들어야 해요'
              : '지금 등급은 조회할 수 없어요. 고르면 그 등급에서 다시 떼요'
          }
          {...form.register('sourceGrade')}
        />
        <SelectField
          label="한 번에 응모할 수 있는 매수"
          options={[
            { value: '1', label: '1매' },
            { value: '2', label: '2매까지' },
          ]}
          error={errors.maxPerEntry?.message}
          {...form.register('maxPerEntry')}
        />
      </div>
      <div className="grid gap-5 @min-[40rem]:grid-cols-2">
        <TextField
          label="추첨석 수"
          inputMode="numeric"
          error={errors.seatCount?.message}
          {...form.register('seatCount')}
        />
        <TextField
          label="예비 순번 수"
          inputMode="numeric"
          error={errors.waitlistCount?.message}
          description={
            mode === 'create'
              ? '당첨자 결제가 되지 않으면 이 순번까지 넘겨요. 추첨석의 절반 정도를 권해요'
              : '비우면 바꾸지 않아요. 지금 값은 조회할 수 없어요'
          }
          {...form.register('waitlistCount')}
        />
      </div>
      <div className="grid gap-5 @min-[40rem]:grid-cols-2">
        <TextField
          label="응모 시작"
          type="datetime-local"
          error={errors.entryStartAt?.message}
          description="한국 시각. 응모가 시작되면 설정을 바꿀 수 없어요"
          {...form.register('entryStartAt', { deps: ['entryEndAt'] })}
        />
        <TextField
          label="응모 마감"
          type="datetime-local"
          error={errors.entryEndAt?.message}
          description="한국 시각"
          {...form.register('entryEndAt', { deps: ['drawAt'] })}
        />
      </div>
      <TextField
        label="추첨 시각"
        type="datetime-local"
        error={errors.drawAt?.message}
        description="한국 시각. 응모 마감과 같거나 뒤, 공연 시작 1시간 전까지"
        {...form.register('drawAt')}
      />
      {drawAt !== '' && !isLowTrafficDrawTime(drawAt) ? (
        <Alert variant="info">
          추첨 시각이 새벽 3~5시 밖이에요. 추첨 직후 자동결제가 몰려 일반 결제와 결제사 자리를
          다투므로 새벽을 권해요. 그대로 저장할 수 있어요.
        </Alert>
      ) : null}
    </>
  )
}
