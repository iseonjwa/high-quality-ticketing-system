import type { ReactNode } from 'react'

interface EmptyStateProps {
  title: string
  description?: string
  action?: ReactNode
}

export function EmptyState({ title, description, action }: EmptyStateProps) {
  return (
    <div
      data-slot="empty-state"
      className="grid justify-items-center gap-2 rounded-md border border-dashed border-gray-200 px-4 py-6 text-center"
    >
      <p className="text-body font-semibold text-gray-900">{title}</p>
      {description ? <p className="text-body-sm text-gray-500">{description}</p> : null}
      {action ? <div className="mt-2">{action}</div> : null}
    </div>
  )
}
