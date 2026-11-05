import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { HomeHero } from '@/pages/concerts/HomeHero'
import type { HomeConcert } from '@/pages/concerts/homeConcerts'

const DAY = 24 * 60 * 60 * 1000
const ROTATE = 7000
// 한국 11월 4일 12:00
const NOW = Date.parse('2026-11-04T03:00:00Z')

const iso = (millis: number) => new Date(millis).toISOString()

function onSale(concertId: number, startsIn: number): HomeConcert {
  const next = { startsAt: iso(NOW + startsIn), openAt: iso(NOW - DAY) }
  return {
    concert: {
      concertId,
      title: `예매 중 ${concertId}`,
      nextStartsAt: next.startsAt,
      nextOpenAt: next.openAt,
    },
    next,
    status: 'ON_SALE',
  }
}

function opening(concertId: number, openIn: number): HomeConcert {
  const next = { startsAt: iso(NOW + 30 * DAY), openAt: iso(NOW + openIn) }
  return {
    concert: {
      concertId,
      title: `오픈 예정 ${concertId}`,
      nextStartsAt: next.startsAt,
      nextOpenAt: next.openAt,
    },
    next,
    status: 'BEFORE_OPEN',
  }
}

const THREE = [onSale(1, 5 * DAY), onSale(2, 2 * DAY), opening(3, 3 * DAY)]

function renderHero(items: HomeConcert[]) {
  render(
    <MemoryRouter>
      <HomeHero items={items} nowMillis={NOW} />
    </MemoryRouter>,
  )
  return userEvent.setup()
}

const slides = () =>
  Array.from(document.querySelectorAll<HTMLElement>('[aria-roledescription="slide"]'))
const visibleSlide = () => screen.getByRole('group', { name: /^\d \/ \d$/ })
const advance = (millis: number) => act(() => vi.advanceTimersByTime(millis))

// 버튼을 누르면 마우스와 초점이 캐러셀 안에 남아 그것만으로 멈춘다. 둘 다 빼서 멈춤 설정만 본다
async function leaveCarousel(user: ReturnType<typeof userEvent.setup>) {
  await user.unhover(screen.getByRole('region', { name: '추천 공연' }))
  act(() => (document.activeElement as HTMLElement).blur())
}

function stubReducedMotion(reduce: boolean) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: reduce && query.includes('prefers-reduced-motion'),
      media: query,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  )
}

beforeEach(() => {
  // 자동 넘김은 setInterval이다. setTimeout까지 가짜로 두면 testing-library의 비동기 대기가 멈춘다
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
  stubReducedMotion(false)
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('HomeHero 공연이 없을 때', () => {
  it('공연 대신 서비스 소개 배너를 보이고 넘김 버튼은 없다', () => {
    renderHero([])

    expect(screen.getByRole('region', { name: '이선좌 소개' })).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 2, name: '오픈 순간에도 흔들리지 않는 예매' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '추천 공연' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})

describe('HomeHero 공연이 하나일 때', () => {
  it('넘김·멈춤 버튼과 쪽 표시 없이 그 공연만 보이고, 시간이 지나도 그대로다', () => {
    renderHero([onSale(1, 5 * DAY)])

    expect(screen.getByRole('region', { name: '추천 공연' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: '예매 중 1' })).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByText(/\d \/ \d/)).not.toBeInTheDocument()

    advance(ROTATE * 4)

    expect(slides()).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 2, name: '예매 중 1' })).toBeInTheDocument()
  })
})

describe('HomeHero 넘기기', () => {
  it('처음에는 첫 장만 보이고 나머지 장은 낭독·초점에서 뺀다', () => {
    renderHero(THREE)

    expect(screen.getByText('1 / 3')).toBeInTheDocument()
    const [first, second, third] = slides()
    expect(first).not.toHaveAttribute('aria-hidden')
    expect(first).not.toHaveAttribute('inert')
    for (const hidden of [second, third]) {
      expect(hidden).toHaveAttribute('aria-hidden', 'true')
      expect(hidden).toHaveAttribute('inert')
    }
    expect(visibleSlide()).toHaveAccessibleName('1 / 3')
    expect(within(visibleSlide()).getByRole('heading', { name: '예매 중 1' })).toBeInTheDocument()
    // 낭독에서 빠진 장의 링크는 접근 가능한 링크로 잡히지 않는다
    expect(screen.getAllByRole('link')).toHaveLength(1)
  })

  it('[다음 공연]·[이전 공연]으로 장을 옮기고 보이는 장만 낭독·초점에 둔다', async () => {
    const user = renderHero(THREE)

    await user.click(screen.getByRole('button', { name: '다음 공연' }))

    expect(screen.getByText('2 / 3')).toBeInTheDocument()
    expect(within(visibleSlide()).getByRole('heading', { name: '예매 중 2' })).toBeInTheDocument()
    const [first, second] = slides()
    expect(second).not.toHaveAttribute('aria-hidden')
    expect(second).not.toHaveAttribute('inert')
    expect(first).toHaveAttribute('aria-hidden', 'true')
    expect(first).toHaveAttribute('inert')

    await user.click(screen.getByRole('button', { name: '이전 공연' }))

    expect(screen.getByText('1 / 3')).toBeInTheDocument()
    expect(within(visibleSlide()).getByRole('heading', { name: '예매 중 1' })).toBeInTheDocument()
  })

  it('끝에서 다음은 처음으로, 처음에서 이전은 끝으로 돌아온다', async () => {
    const user = renderHero(THREE)

    await user.click(screen.getByRole('button', { name: '이전 공연' }))
    expect(screen.getByText('3 / 3')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '다음 공연' }))
    expect(screen.getByText('1 / 3')).toBeInTheDocument()
  })
})

describe('HomeHero 배너 그림 받기', () => {
  // 장 안의 배너 그림(포스터 그림은 data-slot="concert-poster" 안에 있어 뺀다)
  const artOf = (slide: HTMLElement) =>
    Array.from(slide.querySelectorAll('img')).filter(
      (img) => img.closest('[data-slot="concert-poster"]') === null,
    )

  it('처음에는 보이는 장과 다음 장의 배너 그림만 받고, 넘기면 그다음 장 그림을 받는다', async () => {
    const user = renderHero([...THREE, onSale(4, 6 * DAY)])
    expect(slides().map((slide) => artOf(slide).length)).toEqual([1, 1, 0, 0])
    // 첫 장 그림만 먼저 받는다
    expect(artOf(slides()[0])[0]).toHaveAttribute('fetchpriority', 'high')

    await user.click(screen.getByRole('button', { name: '다음 공연' }))
    expect(slides().map((slide) => artOf(slide).length)).toEqual([1, 1, 1, 0])

    // 한 번 받은 그림은 장을 떠나도 남긴다(사라졌다 다시 받지 않는다)
    await user.click(screen.getByRole('button', { name: '이전 공연' }))
    expect(slides().map((slide) => artOf(slide).length)).toEqual([1, 1, 1, 0])
  })
})

describe('HomeHero 자동 넘김', () => {
  it('7초마다 다음 장으로 넘기고 끝에서는 처음으로 돌아온다', () => {
    renderHero(THREE)

    advance(ROTATE - 1)
    expect(screen.getByText('1 / 3')).toBeInTheDocument()
    advance(1)
    expect(screen.getByText('2 / 3')).toBeInTheDocument()
    advance(ROTATE)
    expect(screen.getByText('3 / 3')).toBeInTheDocument()
    advance(ROTATE)
    expect(screen.getByText('1 / 3')).toBeInTheDocument()
  })

  it('[자동 넘김 멈추기]를 누르면 넘어가지 않고, 다시 시작하면 이어서 넘긴다', async () => {
    const user = renderHero(THREE)

    await user.click(screen.getByRole('button', { name: '자동 넘김 멈추기' }))
    await leaveCarousel(user)
    advance(ROTATE * 4)

    expect(screen.getByText('1 / 3')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '자동 넘김 시작' }))
    await leaveCarousel(user)
    advance(ROTATE)

    expect(screen.getByText('2 / 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '자동 넘김 멈추기' })).toBeInTheDocument()
  })

  it('마우스를 올리면 멈추고 내리면 다시 넘긴다', async () => {
    const user = renderHero(THREE)
    const carousel = screen.getByRole('region', { name: '추천 공연' })

    await user.hover(carousel)
    advance(ROTATE * 4)
    expect(screen.getByText('1 / 3')).toBeInTheDocument()

    await user.unhover(carousel)
    advance(ROTATE)
    expect(screen.getByText('2 / 3')).toBeInTheDocument()
  })

  it('키보드 초점이 들어오면 멈추고, 나가도 [자동 넘김 시작]을 누르기 전에는 다시 넘기지 않는다', async () => {
    const user = renderHero(THREE)

    await user.tab()
    expect(screen.getByRole('button', { name: '자동 넘김 시작' })).toHaveFocus()
    advance(ROTATE * 4)
    expect(screen.getByText('1 / 3')).toBeInTheDocument()

    act(() => (document.activeElement as HTMLElement).blur())
    advance(ROTATE * 4)
    expect(screen.getByText('1 / 3')).toBeInTheDocument()

    act(() => screen.getByRole('button', { name: '자동 넘김 시작' }).focus())
    await user.keyboard('{Enter}')
    expect(screen.getByRole('button', { name: '자동 넘김 멈추기' })).toBeInTheDocument()
    await leaveCarousel(user)
    advance(ROTATE)
    expect(screen.getByText('2 / 3')).toBeInTheDocument()
  })

  it('마우스로 [자동 넘김 멈추기]를 누르면 멈추고, 누를 때 생기는 초점으로 다시 켜지지 않는다', async () => {
    const user = renderHero(THREE)

    await user.click(screen.getByRole('button', { name: '자동 넘김 멈추기' }))

    expect(screen.getByRole('button', { name: '자동 넘김 시작' })).toBeInTheDocument()
    await leaveCarousel(user)
    advance(ROTATE * 4)
    expect(screen.getByText('1 / 3')).toBeInTheDocument()
  })

  it('직접 넘기면 자동 넘김이 꺼지고 버튼은 [자동 넘김 시작]이 된다', async () => {
    const user = renderHero(THREE)

    await user.click(screen.getByRole('button', { name: '다음 공연' }))
    await leaveCarousel(user)
    advance(ROTATE * 4)

    expect(screen.getByText('2 / 3')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '자동 넘김 시작' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '자동 넘김 멈추기' })).not.toBeInTheDocument()
  })

  it('동작 줄이기 설정이면 처음부터 멈춰 있다', () => {
    stubReducedMotion(true)
    renderHero(THREE)

    expect(screen.getByRole('button', { name: '자동 넘김 시작' })).toBeInTheDocument()
    advance(ROTATE * 4)

    expect(screen.getByText('1 / 3')).toBeInTheDocument()
  })

  it('넘김 알림 영역은 자동으로 넘기는 동안은 읽지 않고, 멈추면 읽는다', async () => {
    const user = renderHero(THREE)
    const live = () => document.querySelector('[aria-live]')!

    expect(live()).toHaveAttribute('aria-live', 'off')

    await user.click(screen.getByRole('button', { name: '자동 넘김 멈추기' }))

    expect(live()).toHaveAttribute('aria-live', 'polite')
  })
})

describe('HomeHero 장의 내용', () => {
  it('오픈 예정은 "오픈 D-n"과 [공연 보기], 공연 상세 링크를 보인다', async () => {
    const user = renderHero(THREE)
    await user.click(screen.getByRole('button', { name: '이전 공연' }))

    const slide = visibleSlide()
    expect(within(slide).getByText('오픈 예정')).toBeInTheDocument()
    expect(within(slide).getByText('오픈 D-3')).toBeInTheDocument()
    expect(within(slide).getByRole('link', { name: '공연 보기' })).toHaveAttribute(
      'href',
      '/concerts/3',
    )
    expect(within(slide).queryByRole('link', { name: '예매하러 가기' })).not.toBeInTheDocument()
  })

  it('예매 중은 "공연 D-n"과 [예매하러 가기], 공연 상세 링크를 보인다', () => {
    renderHero(THREE)

    const slide = visibleSlide()
    expect(within(slide).getByText('예매 중')).toBeInTheDocument()
    expect(within(slide).getByText('공연 D-5')).toBeInTheDocument()
    expect(within(slide).getByRole('link', { name: '예매하러 가기' })).toHaveAttribute(
      'href',
      '/concerts/1',
    )
    expect(within(slide).queryByRole('link', { name: '공연 보기' })).not.toBeInTheDocument()
  })

  it('공연이 오늘이면 "오늘 공연", 오픈이 오늘이면 "오늘 오픈"이라고 쓴다', async () => {
    const user = renderHero([onSale(1, 2 * 60 * 60 * 1000), opening(2, 60 * 60 * 1000)])

    expect(within(visibleSlide()).getByText('오늘 공연')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '다음 공연' }))

    expect(within(visibleSlide()).getByText('오늘 오픈')).toBeInTheDocument()
  })

  it('공연·오픈 일시를 한국 시각으로 보인다', () => {
    renderHero([onSale(1, 5 * DAY)])

    const slide = visibleSlide()
    expect(within(slide).getByText('2026.11.09 (월) 12:00')).toBeInTheDocument()
    expect(within(slide).getByText('2026.11.03 (화) 12:00')).toBeInTheDocument()
  })
})
