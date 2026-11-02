import { Link, useRouteError } from 'react-router'
import { describeError } from '@/api/errorMessages'
import { paths } from '@/app/paths'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'

// 레이아웃 경로의 errorElement. 헤더는 레이아웃이 그대로 그리므로 본문 자리만 채운다.
export function RouteErrorPage() {
  const notice = describeError(useRouteError())

  return (
    <main className="mx-auto flex max-w-xl flex-col gap-4 py-12">
      <h1 className="type-title text-gray-900">문제가 생겼어요</h1>
      {/* field는 입력 칸 아래에 쓰는 톤이라 화면 단위 오류에서는 위험으로 보인다 */}
      <Alert variant={notice.tone === 'field' ? 'danger' : notice.tone}>
        <p>{notice.message}</p>
        {notice.traceId ? (
          <p className="mt-1 type-caption text-gray-500">
            문의 번호 <span className="tabular-nums">{notice.traceId}</span>
          </p>
        ) : null}
      </Alert>
      <div className="flex gap-2">
        <Button onClick={() => window.location.reload()}>다시 시도</Button>
        <Button asChild variant="outline">
          <Link to={paths.home}>처음으로</Link>
        </Button>
      </div>
    </main>
  )
}
