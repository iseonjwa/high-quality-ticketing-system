import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { QuantitySelector } from '@/components/ticketing/QuantitySelector'

function Harness({ onChange }: { onChange?: (value: number) => void }) {
  const [value, setValue] = useState(2)
  return (
    <QuantitySelector
      value={value}
      max={4}
      onChange={(next) => {
        setValue(next)
        onChange?.(next)
      }}
    />
  )
}

describe('QuantitySelector', () => {
  it('1매부터 최대 매수까지 보이고 고른 매수를 숫자로 알린다', async () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    expect(screen.getByRole('radiogroup', { name: '매수' })).toBeInTheDocument()
    expect(screen.getAllByRole('radio')).toHaveLength(4)

    await userEvent.click(screen.getByRole('radio', { name: '3매' }))

    expect(onChange).toHaveBeenCalledWith(3)
    expect(screen.getByRole('radio', { name: '3매' })).toBeChecked()
  })

  it('고른 매수를 다시 눌러도 선택이 지워지지 않는다', async () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)

    await userEvent.click(screen.getByRole('radio', { name: '2매' }))

    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByRole('radio', { name: '2매' })).toBeChecked()
  })

  it('disabled면 매수를 바꿀 수 없다', async () => {
    const onChange = vi.fn()
    render(<QuantitySelector value={2} max={4} onChange={onChange} disabled />)

    const radios = screen.getAllByRole('radio')
    radios.forEach((radio) => expect(radio).toBeDisabled())
    await userEvent.click(screen.getByRole('radio', { name: '3매' }))

    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByRole('radio', { name: '2매' })).toBeChecked()
  })
})
