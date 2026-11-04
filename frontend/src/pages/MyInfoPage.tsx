import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { authQueryKeys, getMe, type Me } from '@/api/auth'
import { paths } from '@/app/paths'
import type { AuthNavigationState } from '@/auth/navigation'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

const ROLE_LABELS: Record<Me['role'], string> = {
  USER: '일반 회원',
  ADMIN: '운영자',
}

export function MyInfoPage() {
  const me = useQuery({ queryKey: authQueryKeys.me, queryFn: getMe })
  const passwordChangeState: AuthNavigationState = { from: paths.me, email: me.data?.email }

  return (
    <main className="mx-auto flex w-full max-w-[560px] flex-col gap-5">
      <h1 className="type-title text-gray-900">내 정보</h1>
      {me.isError ? (
        <ErrorNotice error={me.error} onRetry={() => void me.refetch()} />
      ) : (
        <Card>
          <CardContent>
            {me.isPending ? (
              <div className="grid gap-4" role="status">
                <span className="sr-only">내 정보를 불러오고 있어요</span>
                <Skeleton className="h-5 w-3/4" />
                <Skeleton className="h-5 w-1/2" />
                <Skeleton className="h-5 w-1/3" />
              </div>
            ) : (
              <dl className="grid grid-cols-[88px_1fr] gap-x-4 gap-y-4 type-body">
                <dt className="text-gray-500">이메일</dt>
                <dd className="min-w-0 break-all text-gray-900">{me.data.email}</dd>
                <dt className="text-gray-500">닉네임</dt>
                <dd className="min-w-0 break-all text-gray-900">{me.data.nickname}</dd>
                <dt className="text-gray-500">회원 구분</dt>
                <dd className="text-gray-900">{ROLE_LABELS[me.data.role]}</dd>
              </dl>
            )}
          </CardContent>
        </Card>
      )}
      <div className="flex flex-wrap gap-2">
        <Button asChild variant="outline">
          <Link to={paths.passwordChange} state={passwordChangeState}>
            비밀번호 변경
          </Link>
        </Button>
        {/* 운영 화면으로 가는 길은 내 정보 조회가 이미 있는 이 화면에 둔다. 헤더에 두면 모든 화면이 내 정보를 한 번씩 더 조회한다 */}
        {me.data?.role === 'ADMIN' ? (
          <Button asChild variant="outline">
            <Link to={paths.admin}>운영 화면</Link>
          </Button>
        ) : null}
      </div>
    </main>
  )
}
