import type { CSSProperties } from 'react'
import { Check } from 'lucide-react'
import { gradeColor } from '@/components/ticketing/grade'
import { formatWon } from '@/lib/format'

export interface LegendGrade {
  grade: string
  price: number
}

function Swatch({ state, grade }: { state: string; grade?: string }) {
  return (
    <span
      aria-hidden="true"
      data-state={state}
      style={grade ? ({ '--c': gradeColor(grade) } as CSSProperties) : undefined}
      className="seat pointer-events-none grid place-items-center"
    >
      {state === 'selected' ? <Check className="size-3.5 text-white" strokeWidth={3} /> : null}
    </span>
  )
}

export function SeatLegend({ grades }: { grades: LegendGrade[] }) {
  return (
    <ul
      aria-label="좌석 범례"
      className="mt-6 flex flex-wrap gap-x-5 gap-y-3 border-t border-gray-100 pt-4 text-[13px]"
    >
      {grades.map(({ grade, price }) => (
        <li key={grade} className="flex items-center gap-2">
          <Swatch state="available" grade={grade} />
          <span>
            {grade} {formatWon(price)}
          </span>
        </li>
      ))}
      <li className="flex items-center gap-2">
        <Swatch state="selected" />
        선택함
      </li>
      <li className="flex items-center gap-2">
        <Swatch state="unavailable" />
        선택할 수 없음
      </li>
      <li className="flex items-center gap-2">
        <Swatch state="lottery" />
        추첨석
      </li>
    </ul>
  )
}
