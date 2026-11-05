import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'

interface QuantitySelectorProps {
  value: number
  // 회차의 1인 최대 매수(maxPerUser). 1매부터 이 수까지 고른다
  max: number
  onChange: (value: number) => void
  // 선점 요청 중처럼 매수를 바꾸면 안 될 때
  disabled?: boolean
}

// 매수 세그먼트(설계 3·4절: 매수를 먼저 고르고 그 수만큼 좌석을 고른다).
// Note: 단일 선택 ToggleGroup은 고른 항목을 다시 누르면 선택을 지운다. 매수는 늘 하나가 골라져 있어야 해서
// 빈 값은 무시한다. 화면마다 같은 처리를 반복하지 않게 여기서 감싼다.
export function QuantitySelector({ value, max, onChange, disabled }: QuantitySelectorProps) {
  const options = Array.from({ length: max }, (_, index) => index + 1)

  return (
    <ToggleGroup
      type="single"
      aria-label="매수"
      disabled={disabled}
      value={String(value)}
      onValueChange={(next) => {
        if (next !== '') {
          onChange(Number(next))
        }
      }}
    >
      {options.map((option) => (
        <ToggleGroupItem key={option} value={String(option)}>
          {option}매
        </ToggleGroupItem>
      ))}
    </ToggleGroup>
  )
}
