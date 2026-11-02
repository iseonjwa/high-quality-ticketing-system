import { formatWaitTime } from '@/lib/format'

const numberFormat = new Intl.NumberFormat('ko-KR')

interface QueuePositionProps {
  position: number
  estimatedWaitSeconds: number
  // 줄에 처음 섰을 때의 순번. 있으면 진행 막대를 보인다
  initialPosition?: number
}

export function QueuePosition({
  position,
  estimatedWaitSeconds,
  initialPosition,
}: QueuePositionProps) {
  const progress =
    initialPosition !== undefined && initialPosition > 0
      ? Math.round(Math.min(1, Math.max(0, (initialPosition - position) / initialPosition)) * 100)
      : null

  return (
    <div data-slot="queue-position" className="grid gap-3">
      <div className="grid gap-1">
        {/* Note: 순번은 max(0, seq - admitted)라 바로 다음 차례가 1이다(SA 12-8). "내 앞에 n명"으로 쓰면 1명 어긋난다.
            조회마다 읽히므로 화면 낭독기에는 순번 한 줄만 읽힌다(aria-live를 이 줄에만 둔다). */}
        <p aria-live="polite" aria-atomic="true" className="type-title tabular-nums text-gray-900">
          내 순번 {numberFormat.format(position)}번
        </p>
        <p className="type-body-sm text-gray-500">
          예상 대기 <span className="tabular-nums">{formatWaitTime(estimatedWaitSeconds)}</span>
        </p>
      </div>
      {progress !== null ? (
        <div
          role="progressbar"
          aria-label="대기 진행률"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={progress}
          className="h-2 overflow-hidden rounded-pill bg-gray-100"
        >
          <div
            className="h-full rounded-pill bg-primary-600 transition-[width] duration-(--dur-base) ease-out"
            style={{ width: `${progress}%` }}
          />
        </div>
      ) : null}
    </div>
  )
}
