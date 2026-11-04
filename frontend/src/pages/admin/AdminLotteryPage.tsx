import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, ExternalLink } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'
import {
  LOTTERY_FIELDS,
  lotteryFormSchema,
  toLotteryUpdateInput,
  type LotteryFormInput,
  type LotteryFormValues,
} from '@/admin/lotteryFormSchema'
import { updateLottery, type AdminLottery } from '@/api/adminLotteries'
import { isApiError, isOutcomeUnknown } from '@/api/apiError'
import { getLottery, lotteryQueryKeys, type Lottery } from '@/api/lotteries'
import { lotteryPath, paths } from '@/app/paths'
import { applyFieldErrors } from '@/components/form/applyFieldErrors'
import { CopyField } from '@/components/ticketing/CopyField'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'
import { instantMillis } from '@/lib/instant'
import { isoToKstLocal } from '@/lib/kstDateTime'
import { readRouteId } from '@/lib/routeId'
import { useNow } from '@/lib/serverClock'
import { LotteryFields } from '@/pages/admin/LotteryFields'
import { LotteryFormError, LotteryWarnings } from '@/pages/admin/LotteryNotices'

const STATUS_LABELS: Record<Lottery['status'], string> = {
  OPEN: '설정됨',
  CLOSED: '응모 마감',
  DRAWN: '추첨 완료',
  DONE: '종료',
}

// 추첨 설정 보기·고치기(ISE-124). 운영자용 조회 API가 없어 공개 조회(GET /lotteries/{id})로 현재 값을 채운다.
// Note: 공개 조회에 뗀 등급·예비 순번 수가 없다. 두 칸은 비워 두고, 비우면 보내지 않는다(서버는 보낸 필드만 고친다).
// 회차 시작·오픈 시각도 없어 "추첨은 공연 1시간 전까지"와 "오픈 전" 규칙은 서버가 확인한다.
export function AdminLotteryPage() {
  const lotteryId = readRouteId(useParams().lotteryId)
  const lottery = useQuery({
    queryKey: lotteryQueryKeys.info(lotteryId ?? 0),
    queryFn: () => getLottery(lotteryId ?? 0),
    enabled: lotteryId !== null,
  })
  const missing =
    lotteryId === null || (lottery.data === undefined && isApiError(lottery.error, 'NOT_FOUND'))

  return (
    <main className="mx-auto grid w-full max-w-[720px] gap-6">
      <Link
        to={paths.admin}
        className="inline-flex w-fit items-center gap-1 rounded-sm type-body-sm text-gray-500 outline-none hover:text-gray-900 focus-visible:ring-2 focus-visible:ring-ring"
      >
        <ChevronLeft aria-hidden="true" className="size-4" />
        운영
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
            찾는 추첨이 없어요. 추첨 번호를 다시 확인해 주세요
          </Alert>
        </>
      ) : lottery.data === undefined ? (
        lottery.isError ? (
          <>
            <h1 className="sr-only">추첨 설정</h1>
            <ErrorNotice error={lottery.error} onRetry={() => void lottery.refetch()} />
          </>
        ) : (
          <div className="grid gap-3" role="status">
            <span className="sr-only">추첨을 불러오고 있어요</span>
            <Skeleton className="h-8 w-2/3" />
            <Skeleton className="h-64 w-full" />
          </div>
        )
      ) : (
        <LotteryDetail lottery={lottery.data} />
      )}
    </main>
  )
}

function LotteryDetail({ lottery }: { lottery: Lottery }) {
  const entryUrl = `${window.location.origin}${lotteryPath(lottery.lotteryId)}`
  // 응모가 시작됐으면 서버가 409 LOTTERY_SETUP_CLOSED로 막는다. 화면도 폼 대신 값만 보인다
  const now = useNow(10_000)
  const closed = lottery.status !== 'OPEN' || now >= instantMillis(lottery.entryStartAt)

  return (
    <>
      <div className="grid gap-1">
        <h1 className="type-title text-gray-900">추첨 #{lottery.lotteryId}</h1>
        <p className="type-body-sm text-gray-500 tabular-nums">
          회차 #{lottery.scheduleId} · {STATUS_LABELS[lottery.status]}
        </p>
      </div>
      <Card className="gap-4 px-5 @min-[40rem]:px-6">
        <CopyField label="응모 화면 주소" value={entryUrl} />
        <Button asChild variant="outline" className="w-fit">
          <Link to={lotteryPath(lottery.lotteryId)}>
            응모 화면 열기
            <ExternalLink aria-hidden="true" />
          </Link>
        </Button>
      </Card>
      {closed ? (
        <Card className="gap-3 px-5 @min-[40rem]:px-6">
          <Alert variant="info">
            응모가 시작돼 설정을 바꿀 수 없어요. 응모 조건이 응모 중에 바뀌지 않게 막아 둬요
          </Alert>
          <dl className="grid grid-cols-[auto_1fr] gap-x-5 gap-y-2 type-body-sm">
            <dt className="text-gray-500">추첨석</dt>
            <dd className="text-gray-900 tabular-nums">{lottery.seatCount}석</dd>
            <dt className="text-gray-500">한 번에</dt>
            <dd className="text-gray-900">{lottery.rules.maxPerEntry}매까지</dd>
            <dt className="text-gray-500">응모 기간</dt>
            <dd className="text-gray-900 tabular-nums">
              {formatDateTime(lottery.entryStartAt)} ~ {formatDateTime(lottery.entryEndAt)}
            </dd>
            <dt className="text-gray-500">추첨 시각</dt>
            <dd className="text-gray-900 tabular-nums">{formatDateTime(lottery.drawAt)}</dd>
          </dl>
        </Card>
      ) : (
        <LotteryEditForm lottery={lottery} />
      )}
    </>
  )
}

function valuesOf(lottery: Lottery, saved: AdminLottery | null): LotteryFormInput {
  return {
    // 고친 뒤에는 응답에 등급·예비 순번 수가 있어 채운다
    sourceGrade: saved && saved.sourceGrade !== 'LOTTERY' ? saved.sourceGrade : '',
    waitlistCount: saved ? String(saved.waitlistCount) : '',
    seatCount: String(saved?.seatCount ?? lottery.seatCount),
    maxPerEntry: String(saved?.maxPerEntry ?? lottery.rules.maxPerEntry) as '1' | '2',
    entryStartAt: isoToKstLocal(saved?.entryStartAt ?? lottery.entryStartAt),
    entryEndAt: isoToKstLocal(saved?.entryEndAt ?? lottery.entryEndAt),
    drawAt: isoToKstLocal(saved?.drawAt ?? lottery.drawAt),
  }
}

function LotteryEditForm({ lottery }: { lottery: Lottery }) {
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<unknown>(null)
  const [outcomeUnknown, setOutcomeUnknown] = useState(false)
  const [noChange, setNoChange] = useState(false)
  const [saved, setSaved] = useState<AdminLottery | null>(null)
  const form = useForm<LotteryFormInput, unknown, LotteryFormValues>({
    resolver: zodResolver(lotteryFormSchema({ mode: 'edit', scheduleStartsAt: null })),
    defaultValues: valuesOf(lottery, null),
  })
  const { isSubmitting, dirtyFields } = form.formState

  async function handleSave(values: LotteryFormValues) {
    setFormError(null)
    setOutcomeUnknown(false)
    setNoChange(false)
    const input = toLotteryUpdateInput(values, dirtyFields)
    if (Object.keys(input).length === 0) {
      setNoChange(true)
      return
    }
    try {
      const result = await updateLottery(lottery.lotteryId, input)
      setSaved(result)
      // 저장한 값이 새 기준이다. 바꾼 칸 표시를 지우고 응답 값으로 채운다
      form.reset(valuesOf(lottery, result))
      void queryClient.invalidateQueries({ queryKey: lotteryQueryKeys.info(lottery.lotteryId) })
      toast('추첨 설정을 고쳤어요')
    } catch (error) {
      if (isOutcomeUnknown(error)) {
        // 같은 값을 다시 보내도 결과가 같다(보낸 필드를 그 값으로 바꿀 뿐이다)
        setOutcomeUnknown(true)
        return
      }
      if (isApiError(error, 'LOTTERY_SEATS_NOT_ENOUGH')) {
        form.setError(
          'seatCount',
          { message: '추첨석으로 뗄 좌석이 모자라요. 추첨석 수나 등급을 확인해 주세요' },
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
      <form noValidate onSubmit={form.handleSubmit(handleSave)} className="grid gap-5">
        <h2 className="type-heading text-gray-900">설정 고치기</h2>
        {saved ? <LotteryWarnings warnings={saved.warnings} /> : null}
        {outcomeUnknown ? (
          <Alert variant="warning">
            저장됐는지 확인하지 못했어요. 같은 값으로 다시 저장해도 괜찮아요
          </Alert>
        ) : null}
        {noChange ? <Alert variant="info">바꾼 칸이 없어요</Alert> : null}
        {formError === null ? null : <LotteryFormError error={formError} />}
        <LotteryFields form={form} mode="edit" />
        <Button type="submit" className="w-full @min-[40rem]:w-fit" loading={isSubmitting}>
          저장
        </Button>
      </form>
    </Card>
  )
}
