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
      {/* 순번이 바뀔 때 화면 낭독기가 읽는다 */}
      <div aria-live="polite" className="grid gap-1">
        <p className="text-title tabular-nums text-gray-900">
          내 앞에 {numberFormat.format(position)}명
        </p>
        <p className="text-body-sm text-gray-500">
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
