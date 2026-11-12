import { describe, expect, it } from 'vitest'
import { charmHash, charmUrl, readCharmPayload } from '@/pages/charm/charmLink'

const TICKET = {
  r: 2002,
  i: 1,
  n: '티켓팅은내가제일잘해요',
  t: '노을섬 단독 콘서트 〈새벽의 좌석〉',
  s: '2026-11-28T19:00:00+09:00',
  z: 'VIP석 · A구역 3열 12번',
  k: 'T20261112-7K3QX9MA',
}

describe('charmLink', () => {
  it('한글이 든 값을 주소 조각으로 만들고 그대로 되읽는다. 닉네임은 앞 8자만 담는다', () => {
    const hash = charmHash(TICKET)
    expect(hash).toMatch(/^#[A-Za-z0-9_-]+$/)
    expect(readCharmPayload(hash)).toEqual({ ...TICKET, v: 1, n: '티켓팅은내가제일' })
  })

  it('공연 제목·시각이 없는 티켓(주소로 연 예매 상세)도 담는다', () => {
    const withoutHeading = { r: TICKET.r, i: TICKET.i, n: TICKET.n, z: TICKET.z, k: TICKET.k }
    expect(readCharmPayload(charmHash(withoutHeading))).toEqual({
      ...withoutHeading,
      v: 1,
      n: '티켓팅은내가제일',
    })
  })

  it('QR 주소는 오리진과 부적 경로, 조각을 잇는다', () => {
    expect(charmUrl('http://52.63.10.174', '#abc')).toBe('http://52.63.10.174/charm#abc')
  })

  it('조각이 없거나 깨졌거나 모양이 다르면 null이다', () => {
    expect(readCharmPayload('')).toBeNull()
    expect(readCharmPayload('#')).toBeNull()
    expect(readCharmPayload('#%%%')).toBeNull()
    expect(readCharmPayload(`#${btoa('not json')}`)).toBeNull()
    const wrongShape = charmHash(TICKET).slice(1)
    expect(readCharmPayload(`#${wrongShape.slice(0, -6)}`)).toBeNull()
    const negativeId = { v: 1, r: -1, i: 0, n: 'a', z: 'A1', k: 'T20261112-AAAAAAAA' }
    expect(readCharmPayload(`#${btoa(JSON.stringify(negativeId))}`)).toBeNull()
  })
})
