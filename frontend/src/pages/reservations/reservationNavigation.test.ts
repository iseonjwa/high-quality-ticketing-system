import { describe, expect, it } from 'vitest'
import {
  readReservationNavigation,
  reservationNavigationState,
} from '@/pages/reservations/reservationNavigation'

const CONCERT = {
  concertId: 7,
  title: '노을섬 단독 콘서트 〈새벽의 좌석〉',
  startsAt: '2026-11-20T19:00:00+09:00',
  venueName: '한강 아트홀',
}

describe('reservationNavigation', () => {
  it('만든 이동 값을 그대로 읽는다', () => {
    const state = reservationNavigationState({ concert: CONCERT, fromPayment: true })

    expect(readReservationNavigation(state)).toEqual({ concert: CONCERT, fromPayment: true })
  })

  it('공연 요약이 없으면 값에 담지 않고 읽으면 null이다', () => {
    const state = reservationNavigationState({ concert: null, fromPayment: true })

    expect(state).toEqual({ fromPayment: true })
    expect(readReservationNavigation(state)).toEqual({ concert: null, fromPayment: true })
  })

  it('결제에서 오지 않았다고 넘기면 false다', () => {
    const state = reservationNavigationState({ concert: CONCERT, fromPayment: false })

    expect(readReservationNavigation(state).fromPayment).toBe(false)
  })

  it.each([null, undefined, {}, { concert: CONCERT }, { fromPayment: 'yes' }, 'text'])(
    'fromPayment가 없거나 잘못된 값(%j)이면 false다',
    (state) => {
      expect(readReservationNavigation(state).fromPayment).toBe(false)
    },
  )

  it('공연 요약만 있고 fromPayment가 없으면 공연은 읽고 fromPayment는 false다', () => {
    expect(readReservationNavigation({ concert: CONCERT })).toEqual({
      concert: CONCERT,
      fromPayment: false,
    })
  })

  it('공연 요약이 올바르지 않으면 null이다', () => {
    expect(readReservationNavigation({ fromPayment: true, concert: { title: '' } })).toEqual({
      concert: null,
      fromPayment: true,
    })
  })
})
