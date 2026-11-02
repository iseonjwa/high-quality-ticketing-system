import { useRef, useState, type KeyboardEvent } from 'react'
import type { ScheduleSeats } from '@/api/concerts'
import { gradeColor } from '@/components/ticketing/grade'
import { Seat, type SeatState } from '@/components/ticketing/Seat'
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

interface Cell {
  seatId: number
  sectionIndex: number
  row: number
  number: number
  state: SeatState
  justTaken: boolean
}

type Direction = 'left' | 'right' | 'up' | 'down'

const ARROW_DIRECTIONS: Record<string, Direction> = {
  ArrowLeft: 'left',
  ArrowRight: 'right',
  ArrowUp: 'up',
  ArrowDown: 'down',
}

const SEAT_PX = 18

// 선택할 수 없는 좌석(남이 선점·판매, 추첨석, 방금 겹친 좌석)은 키보드로도 건너뛴다
function isEnabled(cell: Cell): boolean {
  return cell.state === 'available' || cell.state === 'selected'
}

// 같은 구역 안에서 화살표 방향의 다음 좌석. 위아래는 번호가 가장 가까운 좌석으로 간다.
// 구역 사이는 Tab으로 움직인다. 구역이 줄바꿈되며 놓여 화살표로 넘을 2차원 이웃을 정하기 어렵다.
function findNext(cells: Cell[], from: Cell, direction: Direction): Cell | undefined {
  const sameSection = cells.filter((c) => c.sectionIndex === from.sectionIndex && isEnabled(c))
  if (direction === 'left' || direction === 'right') {
    const inRow = sameSection.filter((c) => c.row === from.row).sort((a, b) => a.number - b.number)
    const index = inRow.findIndex((c) => c.seatId === from.seatId)
    return inRow[direction === 'left' ? index - 1 : index + 1]
  }
  const rows = [...new Set(sameSection.map((c) => c.row))].sort((a, b) => a - b)
  const candidates =
    direction === 'up'
      ? rows.filter((r) => r < from.row).reverse()
      : rows.filter((r) => r > from.row)
  const targetRow = candidates[0]
  if (targetRow === undefined) {
    return undefined
  }
  return sameSection
    .filter((c) => c.row === targetRow)
    .sort(
      (a, b) =>
        Math.abs(a.number - from.number) - Math.abs(b.number - from.number) || a.number - b.number,
    )[0]
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
  // 구역마다 마지막으로 초점이 간 좌석
  const [activeSeatBySection, setActiveSeatBySection] = useState<Record<number, number>>({})
  const buttons = useRef(new Map<number, HTMLButtonElement>())

  const cells: Cell[] = sections.flatMap((section, sectionIndex) =>
    section.seats.map((seat): Cell => {
      const justTaken = justTakenSeatIds.includes(seat.seatId)
      let state: SeatState = 'available'
      if (lotterySeatIds.includes(seat.seatId)) {
        state = 'lottery'
      } else if (!seat.available || justTaken) {
        state = 'unavailable'
      } else if (selectedSeatIds.includes(seat.seatId)) {
        state = 'selected'
      }
      return {
        seatId: seat.seatId,
        sectionIndex,
        row: seat.row,
        number: seat.number,
        state,
        justTaken,
      }
    }),
  )
  const cellById = new Map(cells.map((c) => [c.seatId, c]))

  // Note: Tab 정지점은 구역마다 하나다(roving tabindex). Tab으로 구역을 옮기고 화살표로 구역 안을 움직인다.
  // 좌석표 전체에 하나만 두면 화살표가 구역을 넘지 못해 키보드로는 첫 구역 좌석밖에 고를 수 없다.
  // 정지점은 그 구역에서 마지막으로 초점이 간 좌석, 없으면 첫 선택 좌석, 없으면 첫 선택 가능 좌석이다.
  const tabStopIds = new Set<number>()
  if (!locked) {
    sections.forEach((_, sectionIndex) => {
      const inSection = cells.filter((c) => c.sectionIndex === sectionIndex)
      const active = cellById.get(activeSeatBySection[sectionIndex] ?? -1)
      const stop =
        active && isEnabled(active)
          ? active
          : (inSection.find((c) => c.state === 'selected') ?? inSection.find(isEnabled))
      if (stop) {
        tabStopIds.add(stop.seatId)
      }
    })
  }

  // 409 뒤 화면이 선택 목록에서 아직 빼지 않은 좌석처럼, 고를 수 없게 된 좌석은 한도에 세지 않는다.
  // 세면 해제도 못 하고 새로 고르지도 못하는 상태가 된다.
  const selectableSelectedCount = cells.filter((c) => c.state === 'selected').length

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

  function handleKeyDown(event: KeyboardEvent<HTMLButtonElement>, cell: Cell) {
    const direction = ARROW_DIRECTIONS[event.key]
    if (!direction) {
      return
    }
    event.preventDefault()
    const next = findNext(cells, cell, direction)
    if (next) {
      buttons.current.get(next.seatId)?.focus()
    }
  }

  const stageWidth = 'min(60%, 480px)'

  return (
    <div data-slot="seat-map" data-locked={locked ? '' : undefined}>
      <div
        className="mx-auto mb-6 grid h-9 place-items-center rounded-b-[40px] bg-gray-800 text-[13px] font-semibold tracking-[0.3em] text-white"
        style={{ width: stageWidth }}
      >
        무대
      </div>
      <div className="overflow-x-auto p-1">
        <div className="flex w-max min-w-full flex-wrap justify-center gap-x-8 gap-y-5">
          {sections.map((section, sectionIndex) => {
            const rows = [...new Set(section.seats.map((s) => s.row))].sort((a, b) => a - b)
            const label = `${section.section}구역 · ${section.grade}`
            return (
              <div
                key={`${section.section}-${sectionIndex}`}
                role="group"
                aria-label={label}
                data-section={section.section}
              >
                <p className="mb-2 flex items-center gap-2 text-[13px] font-bold text-gray-500">
                  <span
                    aria-hidden="true"
                    className="size-2.5 rounded-sm"
                    style={{ background: gradeColor(section.grade) }}
                  />
                  {label}
                </p>
                <div
                  className="grid items-center gap-1"
                  style={{
                    gridTemplateColumns: `14px repeat(${Math.max(...section.seats.map((s) => s.number), 0)}, ${SEAT_PX}px)`,
                  }}
                >
                  {rows.map((row, rowIndex) => (
                    <span
                      key={`row-${row}`}
                      aria-hidden="true"
                      className="mr-0.5 text-right text-[10px] text-gray-400"
                      style={{ gridRow: rowIndex + 1, gridColumn: 1 }}
                    >
                      {row}
                    </span>
                  ))}
                  {section.seats.map((seat) => {
                    const cell = cellById.get(seat.seatId)!
                    return (
                      <Seat
                        key={seat.seatId}
                        ref={(node) => {
                          if (node) {
                            buttons.current.set(seat.seatId, node)
                          } else {
                            buttons.current.delete(seat.seatId)
                          }
                        }}
                        section={section.section}
                        grade={section.grade}
                        price={section.price}
                        row={seat.row}
                        number={seat.number}
                        state={cell.state}
                        justTaken={cell.justTaken}
                        tabIndex={tabStopIds.has(seat.seatId) ? 0 : -1}
                        {...(locked ? { disabled: true } : {})}
                        style={{ gridRow: rows.indexOf(seat.row) + 1, gridColumn: seat.number + 1 }}
                        onToggle={() => toggle(seat.seatId)}
                        onFocus={() =>
                          setActiveSeatBySection((current) =>
                            current[sectionIndex] === seat.seatId
                              ? current
                              : { ...current, [sectionIndex]: seat.seatId },
                          )
                        }
                        onKeyDown={(event) => handleKeyDown(event, cell)}
                      />
                    )
                  })}
                </div>
              </div>
            )
          })}
        </div>
      </div>
      <SeatLegend grades={legendGrades(sections)} />
    </div>
  )
}
