import { formatWaitTime } from '@/lib/format'

const numberFormat = new Intl.NumberFormat('ko-KR')

interface QueuePositionProps {
  position: number
  estimatedWaitSeconds: number
  // 줄에 처음 섰을 때의 순번. 있으면 진행 막대를 보인다
  initialPosition?: number
}

// 대기열 화면의 주인공 숫자라 글자 토큰(display 32px)보다 크게 쓴다(samples/queue.html).
// Note: 순번 글자 크기는 이 상자 폭(cqw)을 따르고 줄바꿈하지 않는다. 전역 overflow-wrap: anywhere 때문에 칸을 넘으면
// "123,45 / 6번"처럼 숫자 중간에서 끊겼다(셀프 리뷰). 상자가 좁으면(26rem 미만) 두 칸을 위아래로 쌓는다
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
    <div data-slot="queue-position" className="@container grid gap-5 text-left">
      <div className="grid grid-cols-[minmax(0,1.4fr)_minmax(0,1fr)] items-end gap-4 @max-[26rem]:grid-cols-1">
        {/* Note: 순번은 max(0, seq - admitted)라 바로 다음 차례가 1이다(SA 12-8). "내 앞에 n명"으로 쓰면 1명 어긋난다.
            조회마다 읽히므로 화면 낭독기에는 순번 한 줄만 읽힌다(aria-live를 이 줄에만 둔다). */}
        <p aria-live="polite" aria-atomic="true" className="grid gap-1">
          <span className="type-body-sm text-gray-500">내 순번</span>{' '}
          <span className="text-[clamp(32px,10cqw,60px)] leading-none font-bold tracking-[-0.04em] whitespace-nowrap text-gray-900 tabular-nums">
            {numberFormat.format(position)}
            <span className="ml-0.5 text-[24px] font-bold">번</span>
          </span>
        </p>
        <p className="grid gap-1">
          <span className="type-body-sm text-gray-500">예상 대기</span>{' '}
          <b className="text-[28px] leading-9 font-bold tracking-[-0.02em] whitespace-nowrap text-gray-900 tabular-nums">
            {formatWaitTime(estimatedWaitSeconds)}
          </b>
        </p>
      </div>
      {progress !== null ? (
        <div
          role="progressbar"
          aria-label="대기 진행률"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={progress}
          className="h-2.5 overflow-hidden rounded-pill bg-gray-100"
        >
          <div
            className="h-full rounded-pill bg-primary-600 transition-[width] duration-(--dur-base) ease-out"
            style={{ width: `${progress}%` }}
          />
        </div>
      ) : null}
      {initialPosition !== undefined && initialPosition > position ? (
        <p className="-mt-2 type-caption text-gray-500 tabular-nums">
          처음보다 {numberFormat.format(initialPosition - position)}번 앞으로 왔어요
        </p>
      ) : null}
    </div>
  )
}
