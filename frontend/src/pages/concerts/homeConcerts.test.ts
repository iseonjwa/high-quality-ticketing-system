import { describe, expect, it } from 'vitest'
import type { ConcertSummary } from '@/api/concerts'
import { FEATURED_LIMIT, groupHomeConcerts } from '@/pages/concerts/homeConcerts'

const HOUR = 60 * 60 * 1000
const DAY = 24 * HOUR
const NOW = Date.parse('2026-11-04T03:00:00Z')

const iso = (millis: number) => new Date(millis).toISOString()

// 다음 회차가 startsIn 뒤에 시작하고 openIn 뒤에 열리는 공연(openIn이 음수면 이미 열렸다)
function concert(
  concertId: number,
  next: { startsIn: number; openIn: number } | null,
): ConcertSummary {
  return {
    concertId,
    title: `공연 ${concertId}`,
    nextStartsAt: next ? iso(NOW + next.startsIn) : null,
    nextOpenAt: next ? iso(NOW + next.openIn) : null,
  }
}

const onSale = (concertId: number, startsInDays: number) =>
  concert(concertId, { startsIn: startsInDays * DAY, openIn: -DAY })
const opening = (concertId: number, openInDays: number) =>
  concert(concertId, { startsIn: 30 * DAY, openIn: openInDays * DAY })

const ids = (items: { concert: ConcertSummary }[]) => items.map((item) => item.concert.concertId)

describe('groupHomeConcerts 묶기', () => {
  it('다음 회차의 상태로 예매 중·오픈 예정·그 밖으로 나눈다', () => {
    const sections = groupHomeConcerts([onSale(1, 5), opening(2, 3), concert(3, null)], NOW)

    expect(ids(sections.onSale)).toEqual([1])
    expect(ids(sections.openingSoon)).toEqual([2])
    expect(ids(sections.others)).toEqual([3])
  })

  it('다음 회차가 없으면 그 밖에 넣고 상태는 비운다', () => {
    const sections = groupHomeConcerts([concert(1, null)], NOW)

    expect(ids(sections.others)).toEqual([1])
    expect(sections.others[0]).toMatchObject({ next: null, status: null })
    expect(sections.featured).toEqual([])
  })

  it('다음 회차가 이미 시작했으면 그 밖에 넣는다', () => {
    const started = concert(1, { startsIn: -HOUR, openIn: -2 * DAY })

    const sections = groupHomeConcerts([started], NOW)

    expect(ids(sections.others)).toEqual([1])
    expect(sections.others[0].status).toBe('CLOSED')
    expect(sections.onSale).toEqual([])
  })

  it('오픈 시각과 같은 순간부터 예매 중이고, 시작 시각과 같은 순간부터 그 밖이다', () => {
    const atOpen = concert(1, { startsIn: DAY, openIn: 0 })
    const atStart = concert(2, { startsIn: 0, openIn: -DAY })

    const sections = groupHomeConcerts([atOpen, atStart], NOW)

    expect(ids(sections.onSale)).toEqual([1])
    expect(ids(sections.others)).toEqual([2])
  })

  it('공연이 없으면 모두 비어 있다', () => {
    expect(groupHomeConcerts([], NOW)).toEqual({
      featured: [],
      openingSoon: [],
      onSale: [],
      others: [],
    })
  })

  it('받은 순서가 달라도 같은 묶음이 나온다', () => {
    const list = [onSale(1, 5), opening(2, 3), concert(3, null), onSale(4, 2)]

    const forward = groupHomeConcerts(list, NOW)
    const backward = groupHomeConcerts([...list].reverse(), NOW)

    expect(ids(backward.onSale)).toEqual(ids(forward.onSale))
    expect(ids(backward.featured)).toEqual(ids(forward.featured))
  })
})

describe('groupHomeConcerts 정렬', () => {
  it('예매 중은 공연이 가까운 순이다', () => {
    const sections = groupHomeConcerts([onSale(1, 9), onSale(2, 2), onSale(3, 5)], NOW)

    expect(ids(sections.onSale)).toEqual([2, 3, 1])
  })

  it('오픈 예정은 오픈이 가까운 순이다(공연 시작이 아니라 오픈 기준)', () => {
    const lateOpenEarlyShow = concert(1, { startsIn: 10 * DAY, openIn: 6 * DAY })
    const earlyOpenLateShow = concert(2, { startsIn: 40 * DAY, openIn: 1 * DAY })
    const middle = concert(3, { startsIn: 20 * DAY, openIn: 3 * DAY })

    const sections = groupHomeConcerts([lateOpenEarlyShow, earlyOpenLateShow, middle], NOW)

    expect(ids(sections.openingSoon)).toEqual([2, 3, 1])
  })

  it('같은 시각이면 받은 순서를 지킨다', () => {
    const sections = groupHomeConcerts([onSale(5, 3), onSale(4, 3), onSale(6, 3)], NOW)

    expect(ids(sections.onSale)).toEqual([5, 4, 6])
  })
})

describe('groupHomeConcerts 대표 배너', () => {
  it('예매 중을 먼저, 그다음 오픈 예정을 담는다', () => {
    const sections = groupHomeConcerts(
      [opening(1, 1), onSale(2, 4), onSale(3, 2), opening(4, 3)],
      NOW,
    )

    expect(ids(sections.featured)).toEqual([3, 2, 1, 4])
  })

  it(`최대 ${FEATURED_LIMIT}개까지만 담고, 넘치면 오픈 예정이 먼저 잘린다`, () => {
    const many = [
      ...[1, 2, 3].map((id) => onSale(id, id)),
      ...[4, 5, 6].map((id) => opening(id, id - 3)),
    ]

    const sections = groupHomeConcerts(many, NOW)

    expect(FEATURED_LIMIT).toBe(4)
    expect(ids(sections.featured)).toEqual([1, 2, 3, 4])
    // 묶음 자체는 자르지 않는다
    expect(ids(sections.openingSoon)).toEqual([4, 5, 6])
  })

  it('예매 중이 한도를 넘으면 오픈 예정은 한 개도 담기지 않는다', () => {
    const many = [...[1, 2, 3, 4, 5].map((id) => onSale(id, id)), opening(9, 1)]

    const sections = groupHomeConcerts(many, NOW)

    expect(ids(sections.featured)).toEqual([1, 2, 3, 4])
  })

  it('그 밖의 공연은 담지 않는다', () => {
    const sections = groupHomeConcerts([concert(1, null)], NOW)

    expect(sections.featured).toEqual([])
  })
})
