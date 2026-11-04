import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CopyField } from '@/components/ticketing/CopyField'
import { Toaster } from '@/components/ui/sonner'

const original = Object.getOwnPropertyDescriptor(navigator, 'clipboard')

function setClipboard(value: unknown) {
  Object.defineProperty(navigator, 'clipboard', { value, configurable: true })
}

afterEach(() => {
  if (original) {
    Object.defineProperty(navigator, 'clipboard', original)
  } else {
    Reflect.deleteProperty(navigator, 'clipboard')
  }
})

function renderField(value = 'https://example.test/lotteries/7') {
  render(
    <>
      <CopyField label="응모 화면 주소" value={value} />
      <Toaster />
    </>,
  )
}

describe('CopyField', () => {
  it('값을 읽기 전용 칸에 보인다', () => {
    setClipboard({ writeText: vi.fn() })
    renderField()

    const input = screen.getByLabelText('응모 화면 주소')
    expect(input).toHaveValue('https://example.test/lotteries/7')
    expect(input).toHaveAttribute('readonly')
  })

  it('클립보드가 없으면(http 접속) 복사 버튼 없이 칸만 보인다', () => {
    setClipboard(undefined)
    renderField()

    expect(screen.getByLabelText('응모 화면 주소')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /복사/ })).not.toBeInTheDocument()
  })

  it('복사 버튼을 누르면 값을 클립보드에 쓰고 "복사함"으로 바뀐다', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    setClipboard({ writeText })
    renderField('임시 비밀번호 값')

    await userEvent.click(screen.getByRole('button', { name: '복사' }))

    expect(writeText).toHaveBeenCalledExactlyOnceWith('임시 비밀번호 값')
    expect(await screen.findByRole('button', { name: '복사함' })).toBeInTheDocument()
  })

  it('복사에 실패하면 직접 복사하라는 토스트를 띄우고 버튼은 그대로다', async () => {
    setClipboard({ writeText: vi.fn().mockRejectedValue(new Error('denied')) })
    renderField()

    await userEvent.click(screen.getByRole('button', { name: '복사' }))

    expect(
      await screen.findByText('복사하지 못했어요. 칸을 눌러 직접 복사해 주세요'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '복사' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '복사함' })).not.toBeInTheDocument()
  })

  it('칸을 누르면 값 전체를 고른다', async () => {
    setClipboard(undefined)
    renderField('abcDEF123')

    const input = screen.getByLabelText<HTMLInputElement>('응모 화면 주소')
    await userEvent.click(input)

    await waitFor(() => {
      expect(input.selectionStart).toBe(0)
      expect(input.selectionEnd).toBe('abcDEF123'.length)
    })
  })

  it('monospace를 켜면 고정폭 글꼴을 쓴다', () => {
    setClipboard(undefined)
    render(<CopyField label="임시 비밀번호" value="x" monospace />)

    expect(screen.getByLabelText('임시 비밀번호')).toHaveClass('font-mono')
  })
})
