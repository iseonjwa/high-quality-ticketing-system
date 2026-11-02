import { useEffect, useState, type CSSProperties, type ComponentProps } from 'react'
import { Check } from 'lucide-react'
import { cn } from '@/lib/utils'
import { gradeColor } from '@/components/ticketing/grade'
import { formatWon } from '@/lib/format'

export type SeatState = 'available' | 'selected' | 'unavailable' | 'lottery'

// 409 뒤 방금 선택됨 표시가 깜빡이는 시간(design-system.md 3절). index.css의 seat-taken 애니메이션과 같다.
const JUST_TAKEN_MILLIS = 1600

export interface SeatProps extends Omit<ComponentProps<'button'>, 'onClick' | 'children'> {
  section: string
  grade: string
  price: number
  row: number
  number: number
  state: SeatState
  // 409로 방금 남에게 넘어간 좌석. 1.6초 동안 위험색으로 깜빡인 뒤 선택할 수 없음 모양으로 남는다
  justTaken?: boolean
  onToggle?: () => void
}

function accessibleName(props: SeatProps, state: SeatState): string {
  const base = `${props.grade}석 ${props.section}구역 ${props.row}열 ${props.number}번, ${formatWon(props.price)}`
  if (state === 'lottery') {
    return `${base}, 추첨석이라 일반 예매로 고를 수 없음`
  }
  return state === 'unavailable' ? `${base}, 선택할 수 없음` : base
}

export function Seat({
  section,
  grade,
  price,
  row,
  number,
  state,
  justTaken = false,
  onToggle,
  className,
  style,
  ...props
}: SeatProps) {
  const [flashing, setFlashing] = useState(justTaken)
  useEffect(() => {
    if (!justTaken) {
      return
    }
    const timer = setTimeout(() => setFlashing(false), JUST_TAKEN_MILLIS)
    return () => clearTimeout(timer)
  }, [justTaken])
  // justTaken이 꺼졌다 다시 켜지면(같은 좌석이 또 겹칠 때) 깜빡임을 다시 시작한다
  const [seenJustTaken, setSeenJustTaken] = useState(justTaken)
  if (seenJustTaken !== justTaken) {
    setSeenJustTaken(justTaken)
    setFlashing(justTaken)
  }

  const effective: SeatState = justTaken ? 'unavailable' : state
  const disabled = effective === 'unavailable' || effective === 'lottery'

  return (
    <button
      type="button"
      data-slot="seat"
      data-state={effective}
      data-flashing={justTaken && flashing ? '' : undefined}
      aria-label={accessibleName(
        { section, grade, price, row, number, state: effective },
        effective,
      )}
      aria-pressed={disabled ? undefined : effective === 'selected'}
      disabled={disabled}
      onClick={onToggle}
      style={{ '--c': gradeColor(grade), ...style } as CSSProperties}
      className={cn('seat', className)}
      {...props}
    >
      {effective === 'selected' ? (
        <Check className="size-3.5 text-white" strokeWidth={3} aria-hidden="true" />
      ) : null}
    </button>
  )
}
