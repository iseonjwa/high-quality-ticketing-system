import { describe, expect, it } from 'vitest'
import { queueNavigationState, readQueueConcert } from '@/pages/queue/queueNavigation'

const concert = {
  concertId: 7,
  title: '노을섬 단독 콘서트 〈새벽의 좌석〉',
  startsAt: '2026-12-24T19:00:00+09:00',
  venueName: '한강 아트홀',
}

describe('readQueueConcert', () => {
  it('queueNavigationState가 만든 값은 그대로 읽는다', () => {
    expect(readQueueConcert(queueNavigationState(concert))).toEqual(concert)
  })

  it.each([
    ['null', null],
    ['undefined', undefined],
    ['문자열', 'concert'],
    ['concert 키가 없는 객체', { title: '제목' }],
    ['필드가 모자란 공연', { concert: { concertId: 7, title: '제목' } }],
    ['잘못된 시각 문자열', { concert: { ...concert, startsAt: '내일 저녁' } }],
    ['0인 공연 ID', { concert: { ...concert, concertId: 0 } }],
    ['음수 공연 ID', { concert: { ...concert, concertId: -3 } }],
    ['빈 제목', { concert: { ...concert, title: '' } }],
  ])('%s이면 null이다', (_name, state) => {
    expect(readQueueConcert(state)).toBeNull()
  })
})
