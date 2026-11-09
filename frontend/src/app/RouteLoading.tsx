import { Skeleton } from '@/components/ui/skeleton'

// 주소로 바로 들어오면 화면 코드를 받는 동안 라우터가 아무것도 그리지 못한다. 받는 동안 보일 자리표시다.
export function RouteLoading() {
  return (
    <div
      className="mx-auto w-full max-w-5xl space-y-4 p-6"
      role="status"
      aria-label="화면을 불러오는 중"
    >
      <Skeleton className="h-8 w-1/3" />
      <Skeleton className="h-64 w-full" />
    </div>
  )
}
