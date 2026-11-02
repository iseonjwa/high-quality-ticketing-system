import { useEffect, useRef } from 'react'
import { instantMillis } from '@/lib/instant'
import { formatCountdown } from '@/lib/format'
import { cn } from '@/lib/utils'
import { useNow, type ServerClock } from '@/lib/serverClock'

interface CountdownProps {
  // 오픈 시각(ISO)
  targetAt: string
  // 응답을 받은 시각 기준의 서버 시계. 화면이 createServerClock(serverTime, dataUpdatedAt)으로 만들어
  // useMemo로 고정해 넘긴다. 컴포넌트 안에서 만들면 다시 그릴 때마다 차이를 새로 재 시계가 늦어진다
  clock: ServerClock
  // 화면 낭독기가 읽는 이름
  label?: string
  onComplete?: () => void
  // 글자 크기를 바꿀 때 `type-*`를 넘긴다(기본 type-display)
  className?: string
}

export function Countdown({
  targetAt,
  clock,
  label = '오픈까지 남은 시간',
  onComplete,
  className,
}: CountdownProps) {
  const now = useNow(250, clock)
  const remaining = instantMillis(targetAt) - now
  const done = remaining <= 0

  const onCompleteRef = useRef(onComplete)
  useEffect(() => {
    onCompleteRef.current = onComplete
  })

  // Note: "이미 불렀다"는 기록은 목표 시각이 바뀔 때만 지운다. 남은 시간으로 지우면, 다시 조회한 서버 시각 차이가
  // 조금 작아져 0을 막 넘긴 값이 다시 양수가 될 때 onComplete가 두 번 불린다(오픈 순간 응답 지연이 튀는 경우).
  const firedForRef = useRef<string | null>(null)
  useEffect(() => {
    if (done && firedForRef.current !== targetAt) {
      firedForRef.current = targetAt
      onCompleteRef.current?.()
    }
  }, [done, targetAt])

  return (
    <div
      role="timer"
      aria-label={label}
      data-slot="countdown"
      className={cn('type-display tabular-nums text-gray-900', className)}
    >
      {formatCountdown(remaining)}
    </div>
  )
}
