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
      {state === 'selected' ? <Check className="size-3.5 text-gray-0" strokeWidth={3} /> : null}
    </span>
  )
}

interface SeatLegendProps {
  grades: LegendGrade[]
  // 좌석 조회 응답은 추첨석을 따로 알려 주지 않아(선택할 수 없음으로 온다) 좌석 화면은 추첨석 칸을 뺀다
  showLottery?: boolean
}

export function SeatLegend({ grades, showLottery = true }: SeatLegendProps) {
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
      {showLottery ? (
        <li className="flex items-center gap-2">
          <Swatch state="lottery" />
          추첨석
        </li>
      ) : null}
    </ul>
  )
}
