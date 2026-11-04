import * as React from 'react'
import { cn } from '@/lib/utils'

function Input({ className, type, ...props }: React.ComponentProps<'input'>) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(
        'h-11 w-full min-w-0 rounded-md border border-input bg-gray-0 px-3 py-2 type-body text-gray-900 transition-colors duration-(--dur-fast) outline-none file:inline-flex file:h-6 file:border-0 file:bg-transparent file:type-body-sm file:font-medium file:text-foreground placeholder:text-gray-400 focus-visible:border-ring focus-visible:ring-2 focus-visible:ring-ring/30 disabled:cursor-not-allowed disabled:bg-gray-50 disabled:text-gray-400 aria-invalid:border-danger-600 aria-invalid:ring-2 aria-invalid:ring-danger-600/20',
        className,
      )}
      {...props}
    />
  )
}

export { Input }
