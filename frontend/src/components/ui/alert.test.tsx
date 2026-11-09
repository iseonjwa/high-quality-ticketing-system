import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Alert } from '@/components/ui/alert'

describe('Alert', () => {
  it.each([
    ['danger', 'alert'],
    ['warning', 'alert'],
    ['info', 'status'],
    ['success', 'status'],
  ] as const)('%s 변형은 %s 역할로 알린다', (variant, role) => {
    render(<Alert variant={variant}>안내 문구</Alert>)

    const alert = screen.getByRole(role)
    expect(alert).toHaveTextContent('안내 문구')
    expect(alert).toHaveAttribute('data-variant', variant)
  })

  it('닫기 버튼을 누르면 onClose를 부른다', async () => {
    const onClose = vi.fn()
    render(
      <Alert variant="warning" onClose={onClose}>
        안내 문구
      </Alert>,
    )

    await userEvent.click(screen.getByRole('button', { name: '닫기' }))

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('onClose가 없으면 닫기 버튼이 없고, action은 그 자리에 보인다', () => {
    render(<Alert action={<button type="button">다시 시도</button>}>안내 문구</Alert>)

    expect(screen.queryByRole('button', { name: '닫기' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeInTheDocument()
  })

  it('행동과 닫기가 함께 있으면 문서 순서가 행동 → 닫기다(보이는 순서와 Tab 순서)', () => {
    render(
      <Alert action={<button type="button">다시 시도</button>} onClose={() => {}}>
        안내 문구
      </Alert>,
    )

    const action = screen.getByRole('button', { name: '다시 시도' })
    const close = screen.getByRole('button', { name: '닫기' })
    expect(action.compareDocumentPosition(close) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})
