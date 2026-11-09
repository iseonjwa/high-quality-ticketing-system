import { Moon, Sun } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useTheme } from '@/lib/theme'
import { cn } from '@/lib/utils'

// 헤더의 화면 모드 버튼. 버튼 이름은 누르면 바뀔 모드로 읽힌다(아이콘도 바뀔 모드를 보인다)
export function ThemeToggle({ className }: { className?: string }) {
  const { theme, toggle } = useTheme()
  const next = theme === 'dark' ? '라이트' : '다크'
  return (
    <Button
      type="button"
      variant="ghost"
      size="icon-sm"
      aria-label={`${next} 모드로 바꾸기`}
      onClick={toggle}
      className={cn('text-gray-700', className)}
    >
      {theme === 'dark' ? <Sun aria-hidden="true" /> : <Moon aria-hidden="true" />}
    </Button>
  )
}
