import type { ScheduleSeats } from '@/api/concerts'

// 좌석 조회 응답(`getScheduleSeats`)을 다루는 순수 함수. 화면은 응답 그대로 두고 여기서 계산한다.

type Sections = ScheduleSeats['sections']
type Section = Sections[number]

export interface FoundSeat {
  section: string
  grade: string
  price: number
  row: number
  number: number
  available: boolean
}

export function remainingSeats(section: Section): number {
  return section.seats.filter((seat) => seat.available).length
}

export function findSeat(sections: Sections, seatId: number): FoundSeat | undefined {
  for (const { section, grade, price, seats } of sections) {
    const seat = seats.find((candidate) => candidate.seatId === seatId)
    if (seat !== undefined) {
      return {
        section,
        grade,
        price,
        row: seat.row,
        number: seat.number,
        available: seat.available,
      }
    }
  }
  return undefined
}

// 고른 좌석 중 지금은 고를 수 없는 좌석(다른 사람이 잡았거나 응답에 없다). 고른 순서를 유지한다
export function lostSeatIds(selectedIds: number[], sections: Sections): number[] {
  return selectedIds.filter((seatId) => findSeat(sections, seatId)?.available !== true)
}

// 처음 보일 구역. 남은 좌석이 있는 첫 구역, 모두 매진이면 첫 구역, 구역이 없으면 null
export function firstSectionWithSeats(sections: Sections): string | null {
  const open = sections.find((section) => remainingSeats(section) > 0)
  return (open ?? sections[0])?.section ?? null
}

// 응답에 없는 좌석은 더하지 않는다
export function totalPrice(selectedIds: number[], sections: Sections): number {
  return selectedIds.reduce((sum, seatId) => sum + (findSeat(sections, seatId)?.price ?? 0), 0)
}
