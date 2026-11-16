import { describe, expect, it } from 'vitest'
import { concertArt, FEATURED_ART } from '@/components/ticketing/concertArt'
import showcase from '@/data/showcase.json'

describe('concertArt', () => {
  it('공연 1번이 첫 그림이고, 8장을 돌아 9번이 다시 첫 그림이다', () => {
    expect(concertArt(1)).toBe(concertArt(9))
    expect(concertArt(2)).toBe(concertArt(10))
    expect(concertArt(1)).not.toBe(concertArt(2))
  })

  it('1~8번은 서로 다른 포스터와 배너를 쓴다', () => {
    const arts = Array.from({ length: 8 }, (_, index) => concertArt(index + 1))
    expect(new Set(arts.map((art) => art.poster)).size).toBe(8)
    expect(new Set(arts.map((art) => art.wide)).size).toBe(8)
  })

  it('6번 그림만 밝은 그림이라 어두운 글자를 쓴다', () => {
    expect(concertArt(6).tone).toBe('light')
    expect([1, 2, 3, 4, 5, 7, 8].map((concertId) => concertArt(concertId).tone)).toEqual(
      Array(7).fill('dark'),
    )
  })

  it('0이나 음수 번호도 그림 범위 안에서 고른다', () => {
    const known = new Set(Array.from({ length: 8 }, (_, index) => concertArt(index + 1)))
    for (const concertId of [0, -1, -7, -8, -9, 1_000_000]) {
      expect(known.has(concertArt(concertId))).toBe(true)
    }
    expect(concertArt(0)).toBe(concertArt(8))
    expect(concertArt(-7)).toBe(concertArt(1))
  })

  it('배포 공연은 제목으로 공연마다 다른 사진을 쓴다', () => {
    const arts = showcase.concerts.map((concert) => concertArt(1, concert.title))
    expect(new Set(arts.map((art) => art.poster)).size).toBe(showcase.concerts.length)
    expect(new Set(arts.map((art) => art.wide)).size).toBe(showcase.concerts.length)
    expect(arts.every((art) => art !== concertArt(1))).toBe(true)
  })

  it('제목이 배포 공연과 다르면 공연 번호로 고른 그림을 쓴다', () => {
    expect(concertArt(3, '처음 보는 공연')).toBe(concertArt(3))
  })

  it('대표 그림은 시연 공연의 사진이다', () => {
    const demo = showcase.concerts.find((concert) => 'demo' in concert && concert.demo)
    expect(demo).toBeDefined()
    expect(FEATURED_ART).toEqual(concertArt(1, demo?.title))
  })
})
