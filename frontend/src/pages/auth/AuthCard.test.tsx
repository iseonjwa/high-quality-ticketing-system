import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { AuthCard } from '@/pages/auth/AuthCard'

function renderCard() {
  return render(
    <MemoryRouter>
      <AuthCard title="로그인" description="계정으로 로그인해요" footer={<span>카드 아래</span>}>
        <button type="button">제출</button>
      </AuthCard>
    </MemoryRouter>,
  )
}

describe('AuthCard', () => {
  it('제목은 화면의 h1이고 설명·폼·아래 줄이 함께 나온다', () => {
    renderCard()

    expect(screen.getByRole('heading', { level: 1, name: '로그인' })).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByText('계정으로 로그인해요')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '제출' })).toBeInTheDocument()
    expect(screen.getByText('카드 아래')).toBeInTheDocument()
  })

  it('왼쪽 그림은 꾸밈이라 대체 글이 없고, 좁은 화면에서 받지 않도록 lazy다', () => {
    const { container } = renderCard()

    const img = container.querySelector('img')!
    expect(img).toHaveAttribute('alt', '')
    expect(img).toHaveAttribute('loading', 'lazy')
    expect(img).toHaveAttribute('width')
    expect(img).toHaveAttribute('height')
  })

  it('화면 낭독기가 제목부터 읽도록 폼이 그림 패널보다 DOM에서 먼저 온다', () => {
    renderCard()

    const heading = screen.getByRole('heading', { level: 1 })
    const intro = screen.getByText('대기열로 들어온 순서를 지켜요')
    expect(heading.compareDocumentPosition(intro) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('그림 패널에 서비스 소개 세 줄이 있다', () => {
    renderCard()

    expect(screen.getByText('대기열로 들어온 순서를 지켜요')).toBeInTheDocument()
    expect(screen.getByText('고른 좌석은 결제하는 동안 잡아 둬요')).toBeInTheDocument()
    expect(screen.getByText('일부 좌석은 추첨으로 공정하게 나눠요')).toBeInTheDocument()
  })
})
