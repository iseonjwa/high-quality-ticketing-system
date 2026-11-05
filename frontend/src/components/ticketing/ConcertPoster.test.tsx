import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { concertArt, POSTER_SIZE } from '@/components/ticketing/concertArt'
import { ConcertPoster } from '@/components/ticketing/ConcertPoster'

function posterOf(container: HTMLElement) {
  return container.querySelector<HTMLElement>('[data-slot="concert-poster"]')!
}

describe('ConcertPoster', () => {
  it('제목을 주지 않으면 그림만 그리고 글자는 얹지 않는다', () => {
    const { container } = render(<ConcertPoster concertId={1} />)

    expect(posterOf(container)).toHaveTextContent('')
    expect(screen.queryByText('Live Concert')).not.toBeInTheDocument()
  })

  it('제목을 주면 그림 위에 제목을 얹는다', () => {
    render(<ConcertPoster concertId={1} title="노을섬 단독 콘서트" />)

    expect(screen.getByText('노을섬 단독 콘서트')).toBeInTheDocument()
    expect(screen.getByText('Live Concert')).toBeInTheDocument()
  })

  it('꾸밈이라 화면 낭독기에서 숨기고, 그림은 대체 글이 없다', () => {
    const { container } = render(<ConcertPoster concertId={1} title="제목" />)

    expect(posterOf(container)).toHaveAttribute('aria-hidden', 'true')
    expect(container.querySelector('img')).toHaveAttribute('alt', '')
  })

  it('공연 번호로 고른 포스터 그림을 쓴다', () => {
    const { container } = render(<ConcertPoster concertId={3} />)

    expect(container.querySelector('img')).toHaveAttribute('src', concertArt(3).poster)
  })

  it('img에 크기를 적어 그림을 받기 전에도 자리를 잡는다', () => {
    const { container } = render(<ConcertPoster concertId={1} />)

    const img = container.querySelector('img')!
    expect(img).toHaveAttribute('width', String(POSTER_SIZE.width))
    expect(img).toHaveAttribute('height', String(POSTER_SIZE.height))
  })

  it('loading은 기본이 lazy이고, 위쪽 포스터는 eager로 넘길 수 있다', () => {
    const lazy = render(<ConcertPoster concertId={1} />)
    expect(lazy.container.querySelector('img')).toHaveAttribute('loading', 'lazy')
    lazy.unmount()

    const eager = render(<ConcertPoster concertId={1} loading="eager" />)
    expect(eager.container.querySelector('img')).toHaveAttribute('loading', 'eager')
  })
})
