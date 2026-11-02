import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Button } from '@/components/ui/button'

describe('Button', () => {
  it('처리 중이면 비활성이고 aria-busy를 걸며 눌러도 onClick이 불리지 않는다', async () => {
    const onClick = vi.fn()
    render(
      <Button loading onClick={onClick}>
        결제하기
      </Button>,
    )

    const button = screen.getByRole('button', { name: '결제하기' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')

    await userEvent.click(button)

    expect(onClick).not.toHaveBeenCalled()
  })

  it('처리 중이 아니면 눌린다', async () => {
    const onClick = vi.fn()
    render(<Button onClick={onClick}>결제하기</Button>)

    const button = screen.getByRole('button', { name: '결제하기' })
    expect(button).not.toHaveAttribute('aria-busy')

    await userEvent.click(button)

    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('변형을 data-variant로 드러낸다', () => {
    render(<Button variant="destructive">취소하기</Button>)

    expect(screen.getByRole('button', { name: '취소하기' })).toHaveAttribute(
      'data-variant',
      'destructive',
    )
  })
})
