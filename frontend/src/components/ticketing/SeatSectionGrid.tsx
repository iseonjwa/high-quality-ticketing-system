import { useRef, useState, type KeyboardEvent } from 'react'
import type { ScheduleSeats } from '@/api/concerts'
import { Seat, type SeatState } from '@/components/ticketing/Seat'

type Section = ScheduleSeats['sections'][number]

export interface SeatSectionGridProps {
  section: Section
  selectedSeatIds: number[]
  // 409로 방금 남에게 넘어간 좌석. 깜빡인 뒤 선택할 수 없음으로 남는다
  justTakenSeatIds?: number[]
  lotterySeatIds?: number[]
  // 모든 좌석을 누를 수 없게 하고 Tab 정지점도 없앤다. 흐리게 보이는 것은 감싼 요소의 data-locked가 정한다
  locked?: boolean
  onToggle: (seatId: number) => void
}

interface Cell {
  seatId: number
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

// 화살표 방향의 다음 좌석. 위아래는 번호가 가장 가까운 좌석으로 간다
function findNext(cells: Cell[], from: Cell, direction: Direction): Cell | undefined {
  const enabled = cells.filter(isEnabled)
  if (direction === 'left' || direction === 'right') {
    const inRow = enabled.filter((c) => c.row === from.row).sort((a, b) => a.number - b.number)
    const index = inRow.findIndex((c) => c.seatId === from.seatId)
    return inRow[direction === 'left' ? index - 1 : index + 1]
  }
  const rows = [...new Set(enabled.map((c) => c.row))].sort((a, b) => a - b)
  const candidates =
    direction === 'up'
      ? rows.filter((r) => r < from.row).reverse()
      : rows.filter((r) => r > from.row)
  const targetRow = candidates[0]
  if (targetRow === undefined) {
    return undefined
  }
  return enabled
    .filter((c) => c.row === targetRow)
    .sort(
      (a, b) =>
        Math.abs(a.number - from.number) - Math.abs(b.number - from.number) || a.number - b.number,
    )[0]
}

// 한 구역의 좌석 격자. 좌석표(SeatMap)는 구역마다 하나씩, 좌석 화면은 고른 구역 하나만 그린다.
// Note: Tab 정지점은 격자마다 하나다(roving tabindex). Tab으로 격자에 들어와 화살표로 움직인다.
// 정지점은 마지막으로 초점이 간 좌석, 없으면 첫 선택 좌석, 없으면 첫 선택 가능 좌석이다.
export function SeatSectionGrid({
  section,
  selectedSeatIds,
  justTakenSeatIds = [],
  lotterySeatIds = [],
  locked = false,
  onToggle,
}: SeatSectionGridProps) {
  const [activeSeatId, setActiveSeatId] = useState<number | null>(null)
  const buttons = useRef(new Map<number, HTMLButtonElement>())

  const cells: Cell[] = section.seats.map((seat): Cell => {
    const justTaken = justTakenSeatIds.includes(seat.seatId)
    let state: SeatState = 'available'
    if (lotterySeatIds.includes(seat.seatId)) {
      state = 'lottery'
    } else if (!seat.available || justTaken) {
      state = 'unavailable'
    } else if (selectedSeatIds.includes(seat.seatId)) {
      state = 'selected'
    }
    return { seatId: seat.seatId, row: seat.row, number: seat.number, state, justTaken }
  })
  const cellById = new Map(cells.map((c) => [c.seatId, c]))

  let tabStopId: number | undefined
  if (!locked) {
    const active = activeSeatId === null ? undefined : cellById.get(activeSeatId)
    tabStopId = (
      active && isEnabled(active)
        ? active
        : (cells.find((c) => c.state === 'selected') ?? cells.find(isEnabled))
    )?.seatId
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

  const rows = [...new Set(section.seats.map((s) => s.row))].sort((a, b) => a - b)
  const columns = Math.max(...section.seats.map((s) => s.number), 0)

  return (
    <div
      className="grid items-center gap-1"
      style={{ gridTemplateColumns: `14px repeat(${columns}, ${SEAT_PX}px)` }}
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
            tabIndex={tabStopId === seat.seatId ? 0 : -1}
            {...(locked ? { disabled: true } : {})}
            style={{ gridRow: rows.indexOf(seat.row) + 1, gridColumn: seat.number + 1 }}
            onToggle={() => onToggle(seat.seatId)}
            onFocus={() => setActiveSeatId(seat.seatId)}
            onKeyDown={(event) => handleKeyDown(event, cell)}
          />
        )
      })}
    </div>
  )
}
