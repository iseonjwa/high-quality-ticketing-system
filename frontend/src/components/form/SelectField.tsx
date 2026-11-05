import { useId, type ComponentProps, type ReactNode } from 'react'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'

export interface SelectFieldProps extends Omit<ComponentProps<'select'>, 'className'> {
  label: string
  options: { value: string; label: string }[]
  error?: string
  description?: ReactNode
  className?: string
}

// 운영자 화면의 고르기 칸. 오류·도움말을 aria-describedby로 잇는 규칙은 TextField와 같다
export function SelectField({
  label,
  options,
  error,
  description,
  className,
  id,
  ...selectProps
}: SelectFieldProps) {
  const generatedId = useId()
  const selectId = id ?? generatedId
  const errorId = `${selectId}-error`
  const descriptionId = `${selectId}-description`
  const describedBy =
    [error ? errorId : null, description ? descriptionId : null].filter(Boolean).join(' ') ||
    undefined

  return (
    <div className={cn('grid gap-2', className)}>
      <Label htmlFor={selectId} className="text-gray-900">
        {label}
      </Label>
      <select
        id={selectId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className="h-11 w-full min-w-0 rounded-md border border-input bg-gray-0 px-3 type-body text-gray-900 outline-none focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/30 disabled:cursor-not-allowed disabled:bg-gray-50 disabled:text-gray-400 aria-invalid:border-danger-600 aria-invalid:ring-2 aria-invalid:ring-danger-600/20"
        {...selectProps}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
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
