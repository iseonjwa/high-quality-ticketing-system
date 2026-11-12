import { describe, expect, it } from 'vitest'
import { NICKNAME_SHOWN } from './charmLink'
import { HEADWEAR, PET_NAMES, SPECIES, petTitle } from './charmPets'
import {
  CHEERS,
  SHINY_EVERY,
  STAMPS,
  charmNickname,
  charmSvg,
  estimateEm,
  pickCharm,
} from './charmTicket'

const petKey = (id: number, ticketIndex = 0) => {
  const { pet } = pickCharm(id, ticketIndex)
  return `${pet.species}-${pet.palette}-${pet.headwear}-${pet.item}`
}

describe('pickCharm', () => {
  it('같은 티켓은 몇 번을 열어도 같은 부적이다', () => {
    expect(pickCharm(4821, 0)).toEqual(pickCharm(4821, 0))
  })

  it('연속한 예매 1200건 안에서는 펫이 겹치지 않는다', () => {
    const keys = new Set(Array.from({ length: 1200 }, (_, i) => petKey(73_000 + i)))
    expect(keys.size).toBe(1200)
  })

  it('참관 50명이 연달아 받으면 펫 종류 10가지가 다섯 번씩 나온다', () => {
    const counts = new Map<number, number>()
    for (let id = 9_150; id < 9_200; id++) {
      const { species } = pickCharm(id, 0).pet
      counts.set(species, (counts.get(species) ?? 0) + 1)
    }
    expect([...counts.values()]).toEqual(Array(10).fill(5))
  })

  it(`반짝이 카드는 연속한 ${SHINY_EVERY}건마다 정확히 한 장이다`, () => {
    for (const start of [1, 500, 12_345]) {
      const shiny = Array.from({ length: SHINY_EVERY }, (_, i) => pickCharm(start + i, 0).shiny)
      expect(shiny.filter(Boolean)).toHaveLength(1)
    }
  })

  it(`도장 ${STAMPS.length}가지는 연속한 ${STAMPS.length}건에 한 번씩 나온다`, () => {
    const stamps = new Set(
      Array.from({ length: STAMPS.length }, (_, i) => pickCharm(640 + i, 0).stampIndex),
    )
    expect(stamps.size).toBe(STAMPS.length)
  })

  it('한 예매의 티켓 여러 장은 서로 다른 펫이다', () => {
    expect(new Set([0, 1, 2, 3].map((index) => petKey(3_210, index))).size).toBe(4)
  })

  it('전투력은 일반 12,000~98,999, 반짝이 100,000 이상이다', () => {
    for (let id = 1; id <= 200; id++) {
      const { power, shiny } = pickCharm(id, 0)
      if (shiny) expect(power).toBeGreaterThanOrEqual(100_000)
      else expect(power).toBeGreaterThanOrEqual(12_000)
      expect(power).toBeLessThan(shiny ? 190_000 : 99_000)
    }
  })
})

describe('부적 글자', () => {
  it('응원 문장은 한 줄이 문장 칸(폭 792, 50px)을 넘지 않는다', () => {
    for (const lines of CHEERS)
      for (const line of lines) expect(estimateEm(line) * 50, line).toBeLessThanOrEqual(792)
  })

  it('가장 긴 펫 이름도 한 줄(폭 792)에 들어간다', () => {
    const longest = Math.max(
      ...SPECIES.flatMap((_, species) =>
        HEADWEAR.flatMap((__, headwear) =>
          PET_NAMES.map((___, name) =>
            estimateEm(petTitle({ species, palette: 0, headwear, item: 0 }, name)),
          ),
        ),
      ),
    )
    expect(longest * 34 + 14 + estimateEm('나의 수호펫') * 26).toBeLessThanOrEqual(792)
  })

  it('닉네임은 9자부터 앞 8자만 줄임표 없이 보이고, 8자는 가장 큰 글자(46px)로 들어간다', () => {
    expect(charmNickname('꿈꾸는개발자')).toEqual({ size: 46, text: '꿈꾸는개발자' })
    expect(charmNickname('티켓팅은내가제일잘해요')).toEqual({ size: 46, text: '티켓팅은내가제일' })
    expect(charmNickname('가'.repeat(30)).text).toBe('가'.repeat(NICKNAME_SHOWN))
    expect(charmNickname('WWWWWWWWWW').text).toBe('WWWWWWWW')
  })

  it('닉네임의 꺾쇠와 따옴표는 글자로 들어가고 태그가 되지 않는다', () => {
    const svg = charmSvg({
      nickname: `<a>"'&`,
      pick: pickCharm(1, 0),
      concertTitle: 'A & B',
      dateLabel: '11.28',
      timeLabel: '토요일 19:00',
      seatLabel: 'VIP석',
      barcodeValue: 'T20261128-ABCDEFGH',
    })
    expect(svg).not.toContain('<a>')
    expect(svg).toContain('&lt;a&gt;&quot;&#39;&amp;')
    expect(svg).toContain('A &amp; B')
  })
})
