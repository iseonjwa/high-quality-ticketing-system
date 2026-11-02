import { Link, useRouteError } from 'react-router'
import { describeError } from '@/api/errorMessages'
import { paths } from '@/app/paths'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'

// 경로의 errorElement. 화면이 던진 오류는 안쪽 경로가 받아 헤더 아래 본문 자리만 채우고,
// 레이아웃 자체가 던진 오류는 바깥 경로가 받아 헤더 없이 이 화면만 보인다(그래서 가로 여백을 스스로 둔다).
export function RouteErrorPage() {
  const notice = describeError(useRouteError())

  return (
    <main className="mx-auto flex w-full max-w-xl flex-col gap-4 px-4 py-12 sm:px-0">
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
