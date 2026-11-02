import { useEffect, useMemo, useRef } from 'react'
import { formatCountdown } from '@/lib/format'
import { createServerClock, useNow } from '@/lib/serverClock'

interface CountdownProps {
  // 오픈 시각(ISO)
  targetAt: string
  // 같은 응답의 서버 시각(ISO). 브라우저 시계가 틀려도 오픈 순간을 맞추는 기준이다
  serverTime: string
  onComplete?: () => void
}

export function Countdown({ targetAt, serverTime, onComplete }: CountdownProps) {
  const clock = useMemo(() => createServerClock(serverTime), [serverTime])
  const now = useNow(250, clock)
  const remaining = new Date(targetAt).getTime() - now
  const done = remaining <= 0

  const onCompleteRef = useRef(onComplete)
  useEffect(() => {
    onCompleteRef.current = onComplete
  })
  const firedRef = useRef(false)
  useEffect(() => {
    if (done && !firedRef.current) {
      firedRef.current = true
      onCompleteRef.current?.()
    } else if (!done) {
      firedRef.current = false
    }
  }, [done])

  return (
    <div role="timer" data-slot="countdown" className="text-display tabular-nums text-gray-900">
      {formatCountdown(remaining)}
    </div>
  )
}
