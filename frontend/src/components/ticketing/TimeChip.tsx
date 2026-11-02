import { useEffect, useRef } from 'react'
import { instantMillis } from '@/lib/instant'
import { cn } from '@/lib/utils'
import { formatRemaining } from '@/lib/format'
import { useNow, type ServerClock } from '@/lib/serverClock'

const WARNING_MILLIS = 60_000

type TimeChipLevel = 'default' | 'warning' | 'danger'

function timeChipLevel(remainingMillis: number): TimeChipLevel {
  if (remainingMillis <= 0) {
    return 'danger'
  }
  return remainingMillis <= WARNING_MILLIS ? 'warning' : 'default'
}

const LEVEL_CLASSES: Record<TimeChipLevel, { chip: string; strong: string; dot: string }> = {
  default: { chip: 'bg-gray-50 text-gray-700', strong: 'text-gray-900', dot: 'bg-success-600' },
  warning: {
    chip: 'bg-warning-50 text-warning-700',
    strong: 'text-warning-700',
    dot: 'bg-warning-500 motion-safe:animate-pulse',
  },
  danger: { chip: 'bg-danger-50 text-danger-600', strong: 'text-danger-600', dot: 'bg-danger-600' },
}

interface TimeChipProps {
  label: string
  // 만료 시각(ISO)
  expiresAt: string
  clock?: ServerClock
  // 남은 시간이 0이 되는 순간 한 번만 부른다
  onExpire?: () => void
}

// 입장 유효 시간·결제 가능 시간처럼 끝나는 시각이 정해진 시간을 보인다(design-system.md 7절).
// 1초보다 짧은 주기로 다시 계산해 "00:00"과 만료 처리 사이의 지연을 줄인다.
export function TimeChip({ label, expiresAt, clock, onExpire }: TimeChipProps) {
  const now = useNow(250, clock)
  const remaining = instantMillis(expiresAt) - now
  const level = timeChipLevel(remaining)
  const expired = remaining <= 0

  const onExpireRef = useRef(onExpire)
  useEffect(() => {
    onExpireRef.current = onExpire
  })
  // 만료 시각이 바뀌면(재발급 등) 다시 부를 수 있게 기록을 만료 시각 단위로 둔다(Countdown과 같은 이유)
  const firedForRef = useRef<string | null>(null)
  useEffect(() => {
    if (expired && firedForRef.current !== expiresAt) {
      firedForRef.current = expiresAt
      onExpireRef.current?.()
    }
  }, [expired, expiresAt])

  const classes = LEVEL_CLASSES[level]
  return (
    <span
      role="timer"
      data-slot="time-chip"
      data-level={level}
      className={cn(
        'inline-flex h-8 items-center gap-2 rounded-pill px-3 type-body-sm',
        classes.chip,
      )}
    >
      <span aria-hidden="true" className={cn('size-2 rounded-full', classes.dot)} />
      {label}
      <b className={cn('font-bold tabular-nums', classes.strong)}>{formatRemaining(remaining)}</b>
    </span>
  )
}
