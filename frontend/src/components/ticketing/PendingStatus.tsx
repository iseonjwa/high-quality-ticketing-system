import { Loader2 } from 'lucide-react'

// 결과를 모르는 동안(접수형 선점, 결제 202·504)에는 실패라고 하지 않고 확인 중이라고 알린다.
export function PendingStatus({ title, description }: { title: string; description?: string }) {
  return (
    <div
      role="status"
      data-slot="pending-status"
      className="grid grid-cols-[auto_1fr] items-start gap-3 rounded-md bg-info-50 p-4 text-info-600"
    >
      <Loader2 className="mt-0.5 size-[18px] animate-spin" aria-hidden="true" />
      <div>
        <b className="block text-[15px]">{title}</b>
        {description ? <span className="text-[13px] leading-[19px]">{description}</span> : null}
      </div>
    </div>
  )
}
