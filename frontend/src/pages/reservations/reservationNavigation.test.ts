import { describe, expect, it } from 'vitest'
import {
  readReservationHeading,
  reservationNavigationState,
} from '@/pages/reservations/reservationNavigation'

const HEADING = {
  title: '노을섬 단독 콘서트 〈새벽의 좌석〉',
  startsAt: '2026-11-20T19:00:00+09:00',
  venueName: '한강 아트홀',
}

describe('reservationNavigation', () => {
  it('만든 이동 값을 그대로 읽는다', () => {
    const state = reservationNavigationState(HEADING)

    expect(readReservationHeading(state)).toEqual(HEADING)
  })

  it('공연장이 null이어도 왕복한다', () => {
    const heading = { ...HEADING, venueName: null }

    expect(readReservationHeading(reservationNavigationState(heading))).toEqual(heading)
  })

  it('제목 줄이 없으면 값에 담지 않고 읽으면 null이다', () => {
    const state = reservationNavigationState(null)

    expect(state).toEqual({})
    expect(readReservationHeading(state)).toBeNull()
  })

  it.each([
    null,
    undefined,
    'text',
    {},
    { heading: null },
    { heading: { ...HEADING, title: '' } },
    { heading: { ...HEADING, startsAt: '내일' } },
    { heading: { title: HEADING.title, startsAt: HEADING.startsAt } },
    { heading: { ...HEADING, venueName: 3 } },
    { concert: HEADING },
  ])('모양이 틀리면(%j) null이다', (state) => {
    expect(readReservationHeading(state)).toBeNull()
  })
})
