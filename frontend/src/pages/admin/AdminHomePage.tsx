import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { ChevronRight, KeyRound, Plus, Zap } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { getConcerts } from '@/api/concerts'
import { adminConcertPath, adminLotteryPath, paths } from '@/app/paths'
import { TextField } from '@/components/form/TextField'
import { EmptyState } from '@/components/ticketing/EmptyState'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'

// 운영 첫 화면. 공연을 고르면 회차·좌석·추첨을 만드는 화면으로 간다. 추첨 번호로 추첨 설정을 열고, 회원 비밀번호를 초기화한다.
// 시연 장애 패널(ISE-134)로 Mock PG 장애를 건다.
// 공연 목록 API(최근 등록순)를 그대로 쓴다. 운영자 전용 목록 API는 없다
const PAGE_SIZE = 50

export function AdminHomePage() {
  // Note: 사용자 화면 목록(concertQueryKeys.list)과 쪽 크기가 달라 키를 나눈다. 같은 키면 서로의 캐시를 덮는다.
  // 키 첫 칸은 공개 조회와 같은 'concerts'라, 등록 뒤 'concerts' 전체를 새로 받게 하면 두 목록이 함께 바뀐다
  const concerts = useQuery({
    queryKey: ['concerts', 'adminList'] as const,
    queryFn: () => getConcerts({ page: 0, size: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  })

  return (
    <main className="mx-auto grid w-full max-w-[880px] gap-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="grid gap-1">
          <h1 className="type-title text-gray-900">운영</h1>
          <p className="type-body-sm text-gray-500">
            공연·회차·좌석·추첨을 만들고 회원 비밀번호를 초기화해요
          </p>
        </div>
        <Button asChild>
          <Link to={paths.adminConcertNew}>
            <Plus aria-hidden="true" />
            공연 등록
          </Link>
        </Button>
      </div>

      <div className="grid gap-3 @min-[40rem]:grid-cols-2">
        <LotteryLookup />
        <Card className="justify-center px-5 py-4">
          <Link
            to={paths.adminPasswordReset}
            className="flex items-center gap-3 rounded-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <KeyRound aria-hidden="true" className="size-5 text-gray-500" />
            <span className="grid flex-1 gap-0.5">
              <span className="type-body font-semibold text-gray-900">비밀번호 초기화</span>
              <span className="type-body-sm text-gray-500">
                회원 번호로 임시 비밀번호를 만들어요
              </span>
            </span>
            <ChevronRight aria-hidden="true" className="size-4 text-gray-400" />
          </Link>
        </Card>
        <Card className="justify-center px-5 py-4">
          <Link
            to={paths.adminDemoFaults}
            className="flex items-center gap-3 rounded-sm outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <Zap aria-hidden="true" className="size-5 text-gray-500" />
            <span className="grid flex-1 gap-0.5">
              <span className="type-body font-semibold text-gray-900">시연 장애 패널</span>
              <span className="type-body-sm text-gray-500">
                Mock PG에 장애를 걸어 결제 실패 흐름을 보여 줘요
              </span>
            </span>
            <ChevronRight aria-hidden="true" className="size-4 text-gray-400" />
          </Link>
        </Card>
      </div>

      {concerts.isPending ? (
        <Card className="gap-3 px-5" role="status">
          <span className="sr-only">공연 목록을 불러오고 있어요</span>
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </Card>
      ) : concerts.isError ? (
        <ErrorNotice error={concerts.error} onRetry={() => void concerts.refetch()} />
      ) : concerts.data.content.length === 0 ? (
        <EmptyState
          title="아직 등록한 공연이 없어요"
          action={
            <Button asChild variant="outline">
              <Link to={paths.adminConcertNew}>공연 등록</Link>
            </Button>
          }
        />
      ) : (
        <Card className="gap-0 py-0">
          <ul className="divide-y divide-gray-100">
            {concerts.data.content.map((concert) => (
              <li key={concert.concertId}>
                <Link
                  to={adminConcertPath(concert.concertId)}
                  className="flex items-center gap-3 px-5 py-4 outline-none hover:bg-gray-50 focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                >
                  <div className="grid min-w-0 flex-1 gap-0.5">
                    <span className="type-body font-semibold text-gray-900">{concert.title}</span>
                    <span className="type-body-sm text-gray-500 tabular-nums">
                      {concert.nextStartsAt
                        ? `다음 회차 ${formatDateTime(concert.nextStartsAt)}`
                        : '예정된 회차가 없어요'}
                    </span>
                  </div>
                  <span className="type-caption text-gray-500 tabular-nums">
                    #{concert.concertId}
                  </span>
                  <ChevronRight aria-hidden="true" className="size-4 text-gray-400" />
                </Link>
              </li>
            ))}
          </ul>
        </Card>
      )}
      {concerts.data && concerts.data.totalElements > PAGE_SIZE ? (
        <p className="type-caption text-gray-500">
          최근 등록한 {PAGE_SIZE}개만 보여요. 더 오래된 공연은 주소의 공연 번호로 열어 주세요
        </p>
      ) : null}
    </main>
  )
}

// 추첨 번호로 추첨 설정을 연다. 운영자용 추첨 목록 API가 없어 설정할 때 받은 번호로 찾는다
function LotteryLookup() {
  const navigate = useNavigate()
  const [value, setValue] = useState('')
  const [error, setError] = useState<string | undefined>(undefined)

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    const trimmed = value.trim()
    if (!/^\d+$/.test(trimmed) || Number(trimmed) < 1 || !Number.isSafeInteger(Number(trimmed))) {
      setError('추첨 번호를 숫자로 입력해 주세요')
      return
    }
    void navigate(adminLotteryPath(Number(trimmed)))
  }

  return (
    <Card className="px-5 py-4">
      <form noValidate onSubmit={handleSubmit} className="flex items-end gap-2">
        <TextField
          className="flex-1"
          label="추첨 번호로 열기"
          inputMode="numeric"
          value={value}
          error={error}
          onChange={(event) => {
            setValue(event.target.value)
            setError(undefined)
          }}
        />
        <Button type="submit" variant="outline" className={error ? 'mb-6' : undefined}>
          열기
        </Button>
      </form>
    </Card>
  )
}
