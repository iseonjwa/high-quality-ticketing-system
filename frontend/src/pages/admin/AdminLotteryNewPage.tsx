import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, ExternalLink } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'
import { readLotteryIdOfSchedule, saveLotteryOfSchedule } from '@/admin/adminLotteryRecords'
import {
  LOTTERY_FIELDS,
  lotteryFormSchema,
  toLotteryCreateInput,
  type LotteryFormInput,
  type LotteryFormValues,
} from '@/admin/lotteryFormSchema'
import { createLottery, type AdminLottery } from '@/api/adminLotteries'
import { isApiError, isOutcomeUnknown } from '@/api/apiError'
import { concertQueryKeys, getConcert, type ScheduleSummary } from '@/api/concerts'
import { adminConcertPath, adminLotteryPath, lotteryPath, paths } from '@/app/paths'
import { applyFieldErrors } from '@/components/form/applyFieldErrors'
import { CopyField } from '@/components/ticketing/CopyField'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { LotteryFormError, LotteryWarnings } from '@/pages/admin/LotteryNotices'
import { LotteryFields } from '@/pages/admin/LotteryFields'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'
import { instantMillis } from '@/lib/instant'
import { readRouteId } from '@/lib/routeId'
import { useNow } from '@/lib/serverClock'

// 회차에 추첨을 설정한다(ISE-124). 회차 시각은 공연 상세 조회로 받는다(운영자 화면과 같은 캐시).
// Note: 설정 API에 멱등키가 없다. 결과를 모르면 다시 보내지 말고 이미 있는지부터 보게 한다
export function AdminLotteryNewPage() {
  const params = useParams()
  const concertId = readRouteId(params.concertId)
  const scheduleId = readRouteId(params.scheduleId)
  const concert = useQuery({
    queryKey: concertQueryKeys.detail(concertId ?? 0),
    queryFn: () => getConcert(concertId ?? 0),
    enabled: concertId !== null,
  })
  const schedule = concert.data?.schedules.find((item) => item.scheduleId === scheduleId)
  const missing =
    concertId === null ||
    scheduleId === null ||
    (concert.data !== undefined && schedule === undefined) ||
    (concert.data === undefined && isApiError(concert.error, 'NOT_FOUND'))

  return (
    <main className="mx-auto grid w-full max-w-[720px] gap-6">
      <Link
        to={concertId === null ? paths.admin : adminConcertPath(concertId)}
        className="inline-flex w-fit items-center gap-1 rounded-sm type-body-sm text-gray-500 outline-none hover:text-gray-900 focus-visible:ring-2 focus-visible:ring-ring"
      >
        <ChevronLeft aria-hidden="true" className="size-4" />
        {concert.data?.title ?? '공연'}
      </Link>
      {missing ? (
        <>
          <h1 className="sr-only">추첨 설정</h1>
          <Alert
            variant="warning"
            action={
              <Button asChild variant="outline" size="sm">
                <Link to={paths.admin}>운영으로</Link>
              </Button>
            }
          >
            찾는 회차가 없어요. 공연 화면에서 다시 골라 주세요
          </Alert>
        </>
      ) : schedule === undefined ? (
        concert.isError ? (
          <>
            <h1 className="sr-only">추첨 설정</h1>
            <ErrorNotice error={concert.error} onRetry={() => void concert.refetch()} />
          </>
        ) : (
          <div className="grid gap-3" role="status">
            <span className="sr-only">회차를 불러오고 있어요</span>
            <Skeleton className="h-8 w-2/3" />
            <Skeleton className="h-64 w-full" />
          </div>
        )
      ) : (
        <LotterySetup concertId={concertId as number} schedule={schedule} />
      )}
    </main>
  )
}

function LotterySetup({ concertId, schedule }: { concertId: number; schedule: ScheduleSummary }) {
  const [created, setCreated] = useState<AdminLottery | null>(null)
  // 이 브라우저에서 이미 설정한 추첨(회차마다 하나)
  const knownLotteryId = useMemo(() => readLotteryIdOfSchedule(schedule.scheduleId), [schedule])
  // 화면을 보는 사이 오픈이 지나면 폼을 닫는다. 판정은 서버도 한다(LOTTERY_SETUP_CLOSED)
  const now = useNow(10_000)
  const openPassed = now >= instantMillis(schedule.openAt)

  return (
    <>
      <div className="grid gap-1">
        <h1 className="type-title text-gray-900">추첨 설정</h1>
        <p className="type-body-sm text-gray-500 tabular-nums">
          회차 #{schedule.scheduleId} · {formatDateTime(schedule.startsAt)} 공연 ·{' '}
          {formatDateTime(schedule.openAt)} 예매 오픈
        </p>
      </div>
      {created !== null ? (
        <CreatedLottery concertId={concertId} lottery={created} />
      ) : knownLotteryId !== null ? (
        <Alert
          variant="info"
          action={
            <Button asChild variant="outline" size="sm">
              <Link to={adminLotteryPath(knownLotteryId)}>설정 보기</Link>
            </Button>
          }
        >
          이 회차에는 이 브라우저에서 설정한 추첨 #{knownLotteryId}이 있어요. 회차마다 추첨은
          하나예요
        </Alert>
      ) : openPassed ? (
        <Alert variant="warning">
          예매 오픈이 지난 회차에는 추첨을 설정할 수 없어요. 판매 중인 좌석과 겹치기 때문이에요
        </Alert>
      ) : (
        <LotteryCreateForm
          schedule={schedule}
          onCreated={(lottery) => {
            saveLotteryOfSchedule(schedule.scheduleId, lottery.lotteryId)
            setCreated(lottery)
          }}
        />
      )}
    </>
  )
}

function LotteryCreateForm({
  schedule,
  onCreated,
}: {
  schedule: ScheduleSummary
  onCreated: (lottery: AdminLottery) => void
}) {
  const [formError, setFormError] = useState<unknown>(null)
  const [outcomeUnknown, setOutcomeUnknown] = useState(false)
  const form = useForm<LotteryFormInput, unknown, LotteryFormValues>({
    resolver: zodResolver(
      lotteryFormSchema({ mode: 'create', scheduleStartsAt: schedule.startsAt }),
    ),
    defaultValues: {
      sourceGrade: '',
      seatCount: '',
      waitlistCount: '',
      maxPerEntry: '2',
      entryStartAt: '',
      entryEndAt: '',
      drawAt: '',
    },
  })
  const { isSubmitting } = form.formState

  async function handleCreate(values: LotteryFormValues) {
    setFormError(null)
    setOutcomeUnknown(false)
    try {
      const lottery = await createLottery(schedule.scheduleId, toLotteryCreateInput(values))
      toast('추첨을 설정했어요')
      onCreated(lottery)
    } catch (error) {
      if (isOutcomeUnknown(error)) {
        setOutcomeUnknown(true)
        return
      }
      if (isApiError(error, 'LOTTERY_SEATS_NOT_ENOUGH')) {
        form.setError(
          'seatCount',
          {
            message: `${values.sourceGrade}석이 모자라요. 좌석을 만들었는지, 추첨석 수를 줄일 수 있는지 확인해 주세요`,
          },
          { shouldFocus: true },
        )
        return
      }
      if (!applyFieldErrors(error, form.setError, LOTTERY_FIELDS)) {
        setFormError(error)
      }
    }
  }

  return (
    <Card className="px-5 @min-[40rem]:px-6">
      <form noValidate onSubmit={form.handleSubmit(handleCreate)} className="grid gap-5">
        {outcomeUnknown ? (
          <Alert variant="warning">
            설정됐는지 확인하지 못했어요. 다시 설정하면 이미 있다고 나올 수 있어요. 그때는 공연
            화면에서 이 회차의 추첨을 확인해 주세요
          </Alert>
        ) : null}
        {formError === null ? null : <LotteryFormError error={formError} />}
        <LotteryFields form={form} mode="create" />
        <Button type="submit" className="w-full @min-[40rem]:w-fit" loading={isSubmitting}>
          추첨 설정
        </Button>
      </form>
    </Card>
  )
}

function CreatedLottery({ concertId, lottery }: { concertId: number; lottery: AdminLottery }) {
  const entryUrl = `${window.location.origin}${lotteryPath(lottery.lotteryId)}`
  return (
    <Card className="gap-5 px-5 @min-[40rem]:px-6">
      <div className="grid gap-1">
        <h2 className="type-heading text-gray-900">추첨 #{lottery.lotteryId}을 설정했어요</h2>
        <p className="type-body-sm text-gray-500 tabular-nums">
          {lottery.sourceGrade}석 {lottery.seatCount}석 · 예비 {lottery.waitlistCount}명 · 응모{' '}
          {formatDateTime(lottery.entryStartAt)} ~ {formatDateTime(lottery.entryEndAt)} · 추첨{' '}
          {formatDateTime(lottery.drawAt)}
        </p>
      </div>
      <LotteryWarnings warnings={lottery.warnings} />
      <CopyField label="응모 화면 주소" value={entryUrl} />
      <p className="type-caption text-gray-500">
        공연 화면에서 추첨으로 가는 길이 아직 없어요. 이 주소를 응모할 사람에게 알려 주세요.
        운영자용 추첨 조회가 없어 추첨 번호를 적어 두면 나중에 고칠 때 편해요.
      </p>
      <div className="flex flex-wrap gap-2">
        <Button asChild>
          <Link to={lotteryPath(lottery.lotteryId)}>
            응모 화면 열기
            <ExternalLink aria-hidden="true" />
          </Link>
        </Button>
        <Button asChild variant="outline">
          <Link to={adminLotteryPath(lottery.lotteryId)}>설정 고치기</Link>
        </Button>
        <Button asChild variant="ghost">
          <Link to={adminConcertPath(concertId)}>공연으로 돌아가기</Link>
        </Button>
      </div>
    </Card>
  )
}
