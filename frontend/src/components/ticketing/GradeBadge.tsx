import type { CSSProperties } from 'react'
import { cn } from 'cn'
import { gradeColor } from '@/components/ticketing/grade'

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
      {grade}
    </span>
  )
}
