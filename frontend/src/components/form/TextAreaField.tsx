import { useId, type ComponentProps, type ReactNode } from 'react'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'

export interface TextAreaFieldProps extends Omit<ComponentProps<'textarea'>, 'className'> {
  label: string
  error?: string
  description?: ReactNode
  className?: string
}

// 여러 줄 입력 칸. 라벨·오류·도움말을 잇는 방식과 모양은 TextField와 같다
export function TextAreaField({
  label,
  error,
  description,
  className,
  id,
  ...textareaProps
}: TextAreaFieldProps) {
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
      <textarea
        id={inputId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className="min-h-24 w-full min-w-0 resize-y rounded-md border border-input bg-gray-0 px-3 py-2 type-body text-gray-900 transition-colors duration-(--dur-fast) outline-none placeholder:text-gray-400 focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/30 aria-invalid:border-danger-600 aria-invalid:ring-2 aria-invalid:ring-danger-600/20"
        {...textareaProps}
      />
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
