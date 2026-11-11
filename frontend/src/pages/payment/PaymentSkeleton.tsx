import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

// 결제 화면을 불러오는 동안의 자리 표시. 토스에서 돌아온 결과를 확인하는 동안에도 쓴다
export function PaymentSkeleton({ label = '결제 정보를 불러오고 있어요' }: { label?: string }) {
  return (
    <main>
      <div
        role="status"
        className="grid items-start gap-5 @min-[60rem]:grid-cols-[minmax(0,1fr)_340px]"
      >
        <span className="sr-only">{label}</span>
        <Card className="gap-4 px-4 py-5 @min-[40rem]:p-6">
          <Skeleton className="h-7 w-24" />
          <Skeleton className="h-20 w-full" />
          <Skeleton className="h-12 w-2/3" />
        </Card>
        <Card className="gap-4 px-4 py-5 @min-[40rem]:p-6">
          <Skeleton className="h-7 w-28" />
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-[52px] w-full" />
        </Card>
      </div>
    </main>
  )
}
