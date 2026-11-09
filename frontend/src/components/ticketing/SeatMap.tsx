import type { ScheduleSeats } from '@/api/concerts'
import { gradeColor } from '@/components/ticketing/grade'
import { SeatSectionGrid } from '@/components/ticketing/SeatSectionGrid'
import { SeatLegend, type LegendGrade } from '@/components/ticketing/SeatLegend'

type Section = ScheduleSeats['sections'][number]

export interface SeatMapProps {
  sections: ScheduleSeats['sections']
  selectedSeatIds: number[]
  maxSelectable: number
  onChange: (next: number[]) => void
  // 한도에 닿은 채 다른 좌석을 눌렀을 때. 화면이 "먼저 해제해 주세요" 토스트를 띄운다
  onLimitReached?: () => void
  justTakenSeatIds?: number[]
  lotterySeatIds?: number[]
  // 입장 시간이 끝나면 좌석표를 잠근다(설계 7절 "0이면 좌석표 잠금"). 모든 좌석을 누를 수 없고 흐리게 보인다
  locked?: boolean
}

function legendGrades(sections: Section[]): LegendGrade[] {
  const byGrade = new Map<string, number>()
  for (const { grade, price } of sections) {
    if (!byGrade.has(grade)) {
      byGrade.set(grade, price)
    }
  }
  return [...byGrade].map(([grade, price]) => ({ grade, price }))
}

export function SeatMap({
  sections,
  selectedSeatIds,
  maxSelectable,
  onChange,
  onLimitReached,
  justTakenSeatIds = [],
  lotterySeatIds = [],
  locked = false,
}: SeatMapProps) {
  // 409 뒤 화면이 선택 목록에서 아직 빼지 않은 좌석처럼, 고를 수 없게 된 좌석은 한도에 세지 않는다.
  // 세면 해제도 못 하고 새로 고르지도 못하는 상태가 된다.
  const selectableSeatIds = new Set(
    sections.flatMap((section) =>
      section.seats
        .filter(
          (seat) =>
            seat.available &&
            !justTakenSeatIds.includes(seat.seatId) &&
            !lotterySeatIds.includes(seat.seatId),
        )
        .map((seat) => seat.seatId),
    ),
  )
  const selectableSelectedCount = selectedSeatIds.filter((id) => selectableSeatIds.has(id)).length

  function toggle(seatId: number) {
    if (locked) {
      return
    }
    if (selectedSeatIds.includes(seatId)) {
      onChange(selectedSeatIds.filter((id) => id !== seatId))
    } else if (selectableSelectedCount >= maxSelectable) {
      onLimitReached?.()
    } else {
      onChange([...selectedSeatIds, seatId])
    }
  }

  const stageWidth = 'min(60%, 480px)'

  return (
    <div data-slot="seat-map" data-locked={locked ? '' : undefined}>
      <div
        className="mx-auto mb-6 grid h-9 place-items-center rounded-b-[40px] bg-gray-800 text-[13px] font-semibold tracking-[0.3em] text-gray-0"
        style={{ width: stageWidth }}
      >
        무대
      </div>
      <div className="overflow-x-auto p-1">
        <div className="flex w-max min-w-full flex-wrap justify-center gap-x-8 gap-y-5">
          {sections.map((section, sectionIndex) => {
            const label = `${section.section}구역 · ${section.grade}`
            return (
              <div
                key={`${section.section}-${sectionIndex}`}
                role="group"
                aria-label={label}
                data-section={section.section}
              >
                <p className="mb-2 flex items-center gap-2 text-[13px] font-semibold text-gray-500">
                  <span
                    aria-hidden="true"
                    className="size-2.5 rounded-sm"
                    style={{ background: gradeColor(section.grade) }}
                  />
                  {label}
                </p>
                <SeatSectionGrid
                  section={section}
                  selectedSeatIds={selectedSeatIds}
                  justTakenSeatIds={justTakenSeatIds}
                  lotterySeatIds={lotterySeatIds}
                  locked={locked}
                  onToggle={toggle}
                />
              </div>
            )
          })}
        </div>
      </div>
      <SeatLegend grades={legendGrades(sections)} />
    </div>
  )
}
