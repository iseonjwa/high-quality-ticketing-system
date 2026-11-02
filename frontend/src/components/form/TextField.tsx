import { useId, type ComponentProps, type ReactNode } from 'react'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'

export interface TextFieldProps extends Omit<ComponentProps<'input'>, 'className'> {
  label: string
  // 입력 칸 아래 오류 문구(design-system.md 6절 "입력 오류")
  error?: string
  // 오류 아래에 함께 보이는 도움말. 오류가 있어도 지우지 않으므로 오류와 겹치는 도움말은 화면이 빼고 넘긴다
  description?: ReactNode
  // 입력 칸 안 오른쪽에 겹쳐 놓는 버튼 자리(비밀번호 보기)
  trailing?: ReactNode
  className?: string
}

// Note: 오류와 도움말은 aria-describedby로 입력 칸에 잇는다. 제출에 실패하면 react-hook-form이 첫 오류 칸으로
// 초점을 옮기므로 화면 낭독기가 칸 이름과 함께 오류를 읽는다. 문구마다 role="alert"를 달면 칸이 여럿일 때 한꺼번에 읽혀 시끄럽다.
export function TextField({
  label,
  error,
  description,
  trailing,
  className,
  id,
  ...inputProps
}: TextFieldProps) {
  const generatedId = useId()
  const inputId = id ?? generatedId
  const errorId = `${inputId}-error`
  const descriptionId = `${inputId}-description`
  const describedBy =
    [error ? errorId : null, description ? descriptionId : null].filter(Boolean).join(' ') ||
    undefined

  return (
    <div className={cn('grid gap-2', className)}>
      <Label htmlFor={inputId} className="text-gray-900">
        {label}
      </Label>
      <div className="relative">
        <Input
          id={inputId}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
          className={trailing ? 'pr-11' : undefined}
          {...inputProps}
        />
        {trailing}
      </div>
      {error ? (
        <p id={errorId} className="type-caption text-danger-600">
          {error}
        </p>
      ) : null}
      {description ? (
        <p id={descriptionId} className="type-caption text-gray-500">
          {description}
        </p>
      ) : null}
    </div>
  )
}
