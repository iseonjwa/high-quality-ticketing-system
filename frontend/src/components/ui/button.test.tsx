import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Button } from '@/components/ui/button'

describe('Button', () => {
  it('처리 중이면 aria-disabled·aria-busy를 걸고 눌러도(Enter 포함) onClick이 불리지 않는다', async () => {
    const onClick = vi.fn()
    render(
      <Button loading onClick={onClick}>
        결제하기
      </Button>,
    )

    const button = screen.getByRole('button', { name: '결제하기' })
    expect(button).toHaveAttribute('aria-disabled', 'true')
    expect(button).toHaveAttribute('aria-busy', 'true')

    await userEvent.click(button)
    button.focus()
    await userEvent.keyboard('{Enter}')

    expect(onClick).not.toHaveBeenCalled()
  })

  it('처리 중으로 바뀌어도 초점을 잃지 않는다', () => {
    const { rerender } = render(<Button>선택 완료</Button>)
    const button = screen.getByRole('button', { name: '선택 완료' })
    button.focus()

    rerender(<Button loading>선택 완료</Button>)

    expect(button).toHaveFocus()
  })

  it('disabled면 누를 수 없다', async () => {
    const onClick = vi.fn()
    render(
      <Button disabled onClick={onClick}>
        예매하기
      </Button>,
    )

    const button = screen.getByRole('button', { name: '예매하기' })
    expect(button).toBeDisabled()
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
