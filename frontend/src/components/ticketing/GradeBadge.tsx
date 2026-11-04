import type { CSSProperties } from 'react'
import { cn } from '@/lib/utils'
import { gradeColor } from '@/components/ticketing/grade'

// 등급 코드 대신 보이는 글자. 추첨으로 배정된 좌석(LOTTERY)은 당첨 예매에만 나온다(ISE-121)
const GRADE_TEXT: Record<string, string> = { LOTTERY: '추첨' }

// 색만으로 등급을 구분하지 않도록 등급 글자를 함께 쓴다(design-system.md 2-1).
export function GradeBadge({ grade, className }: { grade: string; className?: string }) {
  return (
    <span
      data-slot="grade-badge"
      data-grade={grade}
      style={{ '--c': gradeColor(grade) } as CSSProperties}
      className={cn(
        'inline-grid h-5 min-w-9 place-items-center rounded-sm bg-(--c) px-1.5 text-[11px] font-extrabold text-white',
        className,
      )}
    >
      {GRADE_TEXT[grade] ?? grade}
    </span>
  )
}
