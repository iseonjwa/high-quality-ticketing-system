import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Check, ChevronLeft, ExternalLink } from 'lucide-react'
import { useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'
import {
  SEAT_GRADES,
  scheduleFormSchema,
  seatPriceFormSchema,
  toScheduleInput,
  toSeatPrices,
  type ScheduleFormInput,
  type ScheduleFormValues,
  type SeatPriceFormInput,
  type SeatPriceFormValues,
} from '@/admin/formSchemas'
import { createSchedule, createScheduleSeats } from '@/api/admin'
import { isApiError, isOutcomeUnknown } from '@/api/apiError'
import { concertQueryKeys, getConcert, type ScheduleSummary } from '@/api/concerts'
import { useRecordedLottery } from '@/admin/adminLotteryRecords'
import { adminLotteryNewPath, adminLotteryPath, concertPath, paths } from '@/app/paths'
import { applyFieldErrors } from '@/components/form/applyFieldErrors'
import { TextField } from '@/components/form/TextField'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { SaleStatusBadge } from '@/components/ticketing/SaleStatusBadge'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'
import { readRouteId } from '@/lib/routeId'

const numberFormat = new Intl.NumberFormat('ko-KR')

// 공연 하나의 회차를 보고, 회차를 더하고, 회차마다 좌석을 만든다.
// 회차 목록은 사용자 화면과 같은 공연 상세 조회(GET /concerts/{id})다. 좌석을 만들었는지는 이 응답에 없다
export function AdminConcertPage() {
  const concertId = readRouteId(useParams().concertId)
  const concert = useQuery({
    queryKey: concertQueryKeys.detail(concertId ?? 0),
    queryFn: () => getConcert(concertId ?? 0),
    enabled: concertId !== null,
  })
  // 이 화면에서 좌석을 만든(또는 이미 있다고 확인한) 회차 → 만든 좌석 수(이미 있으면 null)
  const [seatsByScheduleId, setSeatsByScheduleId] = useState<Record<number, number | null>>({})
  // 대화상자가 닫히는 동안(퇴장 애니메이션)에도 내용을 그리도록 대상과 열림을 나눈다
  const [seatTarget, setSeatTarget] = useState<ScheduleSummary | null>(null)
  const [seatDialogOpen, setSeatDialogOpen] = useState(false)
  // 대화상자를 닫으면 그 회차 줄로 초점을 돌려준다(버튼, 만든 뒤에는 결과 글자)
  const rowFocusTargets = useRef(new Map<number, HTMLElement>())

  const missing =
    concertId === null || (concert.data === undefined && isApiError(concert.error, 'NOT_FOUND'))

  return (
    <main className="mx-auto grid w-full max-w-[880px] gap-6">
      <Link
        to={paths.admin}
        className="inline-flex w-fit items-center gap-1 rounded-sm type-body-sm text-gray-500 outline-none hover:text-gray-900 focus-visible:ring-2 focus-visible:ring-ring"
      >
        <ChevronLeft aria-hidden="true" className="size-4" />
        운영
      </Link>

      {missing ? (
        <>
          <h1 className="sr-only">공연 관리</h1>
          <Alert
            variant="warning"
            action={
              <Button asChild variant="outline" size="sm">
                <Link to={paths.admin}>운영으로</Link>
              </Button>
            }
          >
            찾는 공연이 없어요. 운영 목록에서 다시 골라 주세요
          </Alert>
        </>
      ) : concert.data === undefined ? (
        concert.isError ? (
          <>
            <h1 className="sr-only">공연 관리</h1>
            <ErrorNotice error={concert.error} onRetry={() => void concert.refetch()} />
          </>
        ) : (
          <div className="grid gap-3" role="status">
            <span className="sr-only">공연을 불러오고 있어요</span>
            <Skeleton className="h-8 w-2/3" />
            <Skeleton className="h-24 w-full" />
          </div>
        )
      ) : (
        <>
          <div className="grid gap-2">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
              <h1 className="type-title text-gray-900">{concert.data.title}</h1>
              <span className="type-caption text-gray-500 tabular-nums">
                공연 #{concert.data.concertId}
              </span>
            </div>
            {concert.data.description ? (
              <p className="type-body-sm whitespace-pre-line text-gray-500">
                {concert.data.description}
              </p>
            ) : null}
            <Link
              to={concertPath(concert.data.concertId)}
              className="inline-flex w-fit items-center gap-1 rounded-sm type-body-sm text-primary-600 outline-none hover:underline focus-visible:ring-2 focus-visible:ring-ring"
            >
              사용자 화면에서 보기
              <ExternalLink aria-hidden="true" className="size-3.5" />
            </Link>
          </div>

          <section aria-labelledby="schedules-title" className="grid gap-3">
            <h2 id="schedules-title" className="type-heading text-gray-900">
              회차
            </h2>
            {concert.data.schedules.length === 0 ? (
              <p className="type-body-sm text-gray-500">
                아직 회차가 없어요. 아래에서 회차를 추가해 주세요
              </p>
            ) : (
              <ul className="grid gap-3">
                {concert.data.schedules.map((schedule) => (
                  <li key={schedule.scheduleId}>
                    <ScheduleRow
                      concertId={concert.data.concertId}
                      schedule={schedule}
                      seats={seatsByScheduleId[schedule.scheduleId]}
                      focusRef={(node) => {
                        if (node) {
                          rowFocusTargets.current.set(schedule.scheduleId, node)
                        } else {
                          rowFocusTargets.current.delete(schedule.scheduleId)
                        }
                      }}
                      onCreateSeats={() => {
                        setSeatTarget(schedule)
                        setSeatDialogOpen(true)
                      }}
                    />
                  </li>
                ))}
              </ul>
            )}
          </section>

          <ScheduleForm concertId={concert.data.concertId} />

          <SeatDialog
            schedule={seatTarget}
            open={seatDialogOpen}
            onClose={() => setSeatDialogOpen(false)}
            onClosed={(scheduleId) => rowFocusTargets.current.get(scheduleId)?.focus()}
            onDone={(scheduleId, createdSeats) =>
              setSeatsByScheduleId((current) => ({ ...current, [scheduleId]: createdSeats }))
            }
          />
        </>
      )}
    </main>
  )
}

function ScheduleRow({
  concertId,
  schedule,
  seats,
  focusRef,
  onCreateSeats,
}: {
  concertId: number
  schedule: ScheduleSummary
  // undefined: 이 화면에서 모름, number: 방금 만든 좌석 수, null: 이미 있다고 확인함
  seats: number | null | undefined
  focusRef: (node: HTMLElement | null) => void
  onCreateSeats: () => void
}) {
  return (
    <Card className="gap-3 px-5 @min-[40rem]:flex-row @min-[40rem]:items-center @min-[40rem]:justify-between">
      <div className="grid min-w-0 gap-1.5">
        <div className="flex flex-wrap items-center gap-2">
          <SaleStatusBadge status={schedule.saleStatus} />
          <span className="type-caption text-gray-500 tabular-nums">
            회차 #{schedule.scheduleId}
          </span>
        </div>
        <p className="type-heading text-gray-900 tabular-nums">
          {formatDateTime(schedule.startsAt)}
        </p>
        <p className="type-body-sm text-gray-500 tabular-nums">
          {formatDateTime(schedule.openAt)} 오픈 · {schedule.venueName} · 1인 최대{' '}
          {schedule.maxPerUser}매
        </p>
      </div>
      <div className="flex shrink-0 flex-wrap items-center gap-2">
        <LotteryAction concertId={concertId} schedule={schedule} />
        {seats === undefined ? (
          <Button ref={focusRef} variant="outline" onClick={onCreateSeats}>
            좌석 만들기
          </Button>
        ) : (
          // 좌석을 만들면 버튼이 이 글자로 바뀐다. 대화상자를 닫은 초점이 여기로 온다
          <span
            ref={focusRef}
            tabIndex={-1}
            className="inline-flex items-center gap-1.5 type-body-sm font-semibold text-success-600 tabular-nums outline-none"
          >
            <Check aria-hidden="true" className="size-4" strokeWidth={3} />
            {seats === null ? '좌석이 이미 있어요' : `좌석 ${numberFormat.format(seats)}석 만듦`}
          </span>
        )}
      </div>
    </Card>
  )
}

// 추첨은 회차마다 하나다. 이 브라우저에서 설정한 추첨이면 그 설정으로, 아니면 오픈 전 회차에만 설정 버튼을 둔다.
// Note: 운영자용 추첨 조회 API가 없어 다른 브라우저에서 설정한 추첨은 모른다. 설정 화면에서 409로 알게 된다
function LotteryAction({ concertId, schedule }: { concertId: number; schedule: ScheduleSummary }) {
  const recorded = useRecordedLottery(schedule.scheduleId)
  if (recorded.state === 'checking') {
    return null
  }
  if (recorded.state === 'found') {
    return (
      <Button asChild variant="ghost">
        <Link to={adminLotteryPath(recorded.lotteryId)}>추첨 #{recorded.lotteryId}</Link>
      </Button>
    )
  }
  if (schedule.saleStatus !== 'BEFORE_OPEN') {
    return null
  }
  return (
    <Button asChild variant="ghost">
      <Link to={adminLotteryNewPath(concertId, schedule.scheduleId)}>추첨 설정</Link>
    </Button>
  )
}

function ScheduleForm({ concertId }: { concertId: number }) {
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<unknown>(null)
  // 응답을 받지 못해(연결 끊김·504) 등록됐는지 모른다. 등록 API에는 멱등키가 없어 다시 보내면 같은 회차가 둘 생긴다
  const [outcomeUnknown, setOutcomeUnknown] = useState(false)
  const form = useForm<ScheduleFormInput, unknown, ScheduleFormValues>({
    resolver: zodResolver(scheduleFormSchema),
    defaultValues: { venueId: '', startsAt: '', openAt: '', maxPerUser: '', holdMinutes: '' },
  })
  const { errors, isSubmitting } = form.formState

  async function handleCreate(values: ScheduleFormValues) {
    setFormError(null)
    setOutcomeUnknown(false)
    try {
      await createSchedule(concertId, toScheduleInput(values))
      // 등록은 끝났다. 목록 재조회가 늦거나 실패해도 버튼을 붙잡지 않는다
      void queryClient.invalidateQueries({ queryKey: ['concerts'] })
      toast('회차를 등록했어요. 좌석을 만들어야 예매할 수 있어요')
      form.reset()
    } catch (error) {
      if (isOutcomeUnknown(error)) {
        void queryClient.invalidateQueries({ queryKey: ['concerts'] })
        setOutcomeUnknown(true)
        return
      }
      // 공연은 이 화면이 이미 조회했으므로 404는 공연장 번호가 없다는 뜻이다
      if (isApiError(error, 'NOT_FOUND')) {
        form.setError('venueId', { message: '이 번호의 공연장이 없어요' }, { shouldFocus: true })
        return
      }
      if (
        !applyFieldErrors(error, form.setError, [
          'venueId',
          'startsAt',
          'openAt',
          'maxPerUser',
          'holdMinutes',
        ])
      ) {
        setFormError(error)
      }
    }
  }

  return (
    <section aria-labelledby="schedule-form-title" className="grid gap-3">
      <h2 id="schedule-form-title" className="type-heading text-gray-900">
        회차 추가
      </h2>
      <Card className="px-5 @min-[40rem]:px-6">
        <form noValidate onSubmit={form.handleSubmit(handleCreate)} className="grid gap-5">
          {outcomeUnknown ? (
            <Alert variant="warning">
              등록됐는지 확인하지 못했어요. 회차 목록을 다시 불러왔어요. 목록에 없을 때만 다시
              등록해 주세요
            </Alert>
          ) : null}
          {formError === null ? null : <ErrorNotice error={formError} />}
          <TextField
            label="공연장 번호"
            inputMode="numeric"
            error={errors.venueId?.message}
            description="공연장 목록이 아직 없어 번호로 넣어요"
            {...form.register('venueId')}
          />
          <div className="grid gap-5 @min-[40rem]:grid-cols-2">
            <TextField
              label="공연 시작"
              type="datetime-local"
              error={errors.startsAt?.message}
              description="한국 시각"
              {...form.register('startsAt', { deps: ['openAt'] })}
            />
            <TextField
              label="예매 오픈"
              type="datetime-local"
              error={errors.openAt?.message}
              description="한국 시각. 공연 시작보다 앞서야 해요"
              {...form.register('openAt')}
            />
          </div>
          <div className="grid gap-5 @min-[40rem]:grid-cols-2">
            <TextField
              label="1인 최대 매수 (선택)"
              inputMode="numeric"
              placeholder="4"
              error={errors.maxPerUser?.message}
              description="비우면 4매"
              {...form.register('maxPerUser')}
            />
            <TextField
              label="선점 시간(분) (선택)"
              inputMode="numeric"
              placeholder="5"
              error={errors.holdMinutes?.message}
              description="비우면 5분"
              {...form.register('holdMinutes')}
            />
          </div>
          <Button type="submit" className="w-full @min-[40rem]:w-fit" loading={isSubmitting}>
            회차 등록
          </Button>
        </form>
      </Card>
    </section>
  )
}

// 회차 좌석 만들기. 공연장 좌석 배치를 그대로 복사하고 가격은 등급으로 정한다(서버 규칙)
// Note: 요청 중에는 닫지 못하게 한다(Esc·바깥 누름·X·닫기). 좌석 15,000석을 만드는 데 시간이 걸리는데, 그 사이 닫고
// 다른 회차를 열면 앞 요청의 응답이 새로 연 대화상자를 닫거나, 닫힌 뒤 온 실패가 보이지 않는다(셀프 리뷰에서 재현).
// 트리거 없이 여닫아 Radix가 초점을 돌려줄 곳을 모르므로, 닫힌 뒤 그 회차 줄로 직접 돌려준다
function SeatDialog({
  schedule,
  open,
  onClose,
  onClosed,
  onDone,
}: {
  schedule: ScheduleSummary | null
  open: boolean
  onClose: () => void
  onClosed: (scheduleId: number) => void
  onDone: (scheduleId: number, createdSeats: number | null) => void
}) {
  const [submitting, setSubmitting] = useState(false)
  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next && !submitting) {
          onClose()
        }
      }}
    >
      <DialogContent
        showCloseButton={!submitting}
        onCloseAutoFocus={(event) => {
          event.preventDefault()
          if (schedule !== null) {
            onClosed(schedule.scheduleId)
          }
        }}
      >
        {schedule !== null ? (
          // 회차마다 입력 상태를 새로 시작한다
          <SeatForm
            key={schedule.scheduleId}
            schedule={schedule}
            onSubmittingChange={setSubmitting}
            onClose={onClose}
            onDone={onDone}
          />
        ) : null}
      </DialogContent>
    </Dialog>
  )
}

function SeatForm({
  schedule,
  onSubmittingChange,
  onClose,
  onDone,
}: {
  schedule: ScheduleSummary
  onSubmittingChange: (submitting: boolean) => void
  onClose: () => void
  onDone: (scheduleId: number, createdSeats: number | null) => void
}) {
  // 등급이 공연장과 맞지 않을 때의 안내와, 그 밖의 오류
  const [gradeMismatch, setGradeMismatch] = useState(false)
  const [unexpectedError, setUnexpectedError] = useState<unknown>(null)
  const form = useForm<SeatPriceFormInput, unknown, SeatPriceFormValues>({
    resolver: zodResolver(seatPriceFormSchema),
    defaultValues: { VIP: '', R: '', S: '' },
  })
  const { errors, isSubmitting } = form.formState

  async function handleCreate(values: SeatPriceFormValues) {
    setGradeMismatch(false)
    setUnexpectedError(null)
    onSubmittingChange(true)
    try {
      const { createdSeats } = await createScheduleSeats(schedule.scheduleId, toSeatPrices(values))
      toast(`좌석 ${numberFormat.format(createdSeats)}석을 만들었어요`)
      onDone(schedule.scheduleId, createdSeats)
      onSubmittingChange(false)
      onClose()
    } catch (error) {
      onSubmittingChange(false)
      // 좌석 만들기는 다시 보내도 안전하다. 이미 만들었으면 서버가 409로 알려 준다
      if (isApiError(error, 'SCHEDULE_SEATS_ALREADY_CREATED')) {
        toast('이미 좌석을 만든 회차예요')
        onDone(schedule.scheduleId, null)
        onClose()
        return
      }
      // 칸별 오류 없는 400은 가격 등급 모음이 공연장 좌석 등급과 다르다는 뜻이다(서버 규칙)
      if (isApiError(error, 'VALIDATION_FAILED')) {
        setGradeMismatch(true)
        return
      }
      setUnexpectedError(error)
    }
  }

  return (
    <>
      <DialogHeader>
        <DialogTitle>좌석 만들기</DialogTitle>
        <DialogDescription className="tabular-nums">
          {formatDateTime(schedule.startsAt)} · {schedule.venueName}
        </DialogDescription>
      </DialogHeader>
      <form noValidate onSubmit={form.handleSubmit(handleCreate)} className="grid gap-4">
        <p className="type-body-sm text-gray-500">
          공연장에 있는 등급마다 가격을 넣어 주세요. 공연장에 없는 등급은 비워 둬요.
        </p>
        {gradeMismatch ? (
          <Alert variant="warning">
            등급이 공연장 좌석과 맞지 않아요. 공연장에 있는 등급마다 가격을 하나씩 넣어 주세요
          </Alert>
        ) : null}
        {unexpectedError !== null ? <ErrorNotice error={unexpectedError} /> : null}
        {errors.root?.message ? <Alert variant="warning">{errors.root.message}</Alert> : null}
        {SEAT_GRADES.map((grade) => (
          <TextField
            key={grade}
            label={`${grade}석 가격(원)`}
            inputMode="numeric"
            error={errors[grade]?.message}
            {...form.register(grade, { deps: SEAT_GRADES.filter((other) => other !== grade) })}
          />
        ))}
        <div className="mt-1 flex flex-wrap justify-end gap-2">
          <Button type="button" variant="ghost" disabled={isSubmitting} onClick={onClose}>
            닫기
          </Button>
          <Button type="submit" loading={isSubmitting}>
            좌석 만들기
          </Button>
        </div>
      </form>
    </>
  )
}
