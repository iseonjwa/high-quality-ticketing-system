import { useQuery } from '@tanstack/react-query'
import { Link, Outlet } from 'react-router'
import { authQueryKeys, getMe } from '@/api/auth'
import { paths } from '@/app/paths'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'

// 운영자 화면을 감싸는 경로. RequireAuth 아래에 둔다(로그인은 이미 확인됐다).
// Note: 화면에서 막는 것은 안내용이다. 실제로 막는 곳은 Gateway(USER에게 403)와 서비스의 @RequireRole이다.
export function RequireAdmin() {
  const me = useQuery({ queryKey: authQueryKeys.me, queryFn: getMe })

  if (me.isPending) {
    return (
      <main className="mx-auto w-full max-w-[880px]">
        <div className="grid gap-4" role="status">
          <span className="sr-only">권한을 확인하고 있어요</span>
          <Skeleton className="h-8 w-40" />
          <Skeleton className="h-32 w-full" />
        </div>
      </main>
    )
  }
  if (me.isError) {
    return (
      <main className="mx-auto w-full max-w-[880px]">
        <h1 className="sr-only">운영</h1>
        <ErrorNotice error={me.error} onRetry={() => void me.refetch()} />
      </main>
    )
  }
  if (me.data.role !== 'ADMIN') {
    return (
      <main className="mx-auto w-full max-w-[560px]">
        <h1 className="sr-only">운영</h1>
        <Alert
          variant="warning"
          action={
            <Button asChild variant="outline" size="sm">
              <Link to={paths.home}>공연 목록으로</Link>
            </Button>
          }
        >
          운영자만 볼 수 있는 화면이에요
        </Alert>
      </main>
    )
  }
  return <Outlet />
}
