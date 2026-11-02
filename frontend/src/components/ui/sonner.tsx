import { Toaster as Sonner, type ToasterProps } from 'sonner'
import {
  CircleCheckIcon,
  InfoIcon,
  TriangleAlertIcon,
  OctagonXIcon,
  Loader2Icon,
} from 'lucide-react'

// 설계 4절: 한 줄, 3초. 샘플(.toast)처럼 gray-900 바탕·흰 글자로 화면 아래 가운데에 띄운다.
// 다크 모드를 만들지 않으므로 next-themes 없이 테마를 고정한다.
const Toaster = ({ ...props }: ToasterProps) => {
  return (
    <Sonner
      theme="light"
      className="toaster group"
      duration={3000}
      position="bottom-center"
      icons={{
        success: <CircleCheckIcon className="size-4" />,
        info: <InfoIcon className="size-4" />,
        warning: <TriangleAlertIcon className="size-4" />,
        error: <OctagonXIcon className="size-4" />,
        loading: <Loader2Icon className="size-4 animate-spin" />,
      }}
      style={
        {
          '--normal-bg': 'var(--gray-900)',
          '--normal-text': '#ffffff',
          '--normal-border': 'var(--gray-900)',
          '--border-radius': 'var(--radius-md)',
        } as React.CSSProperties
      }
      toastOptions={{
        classNames: { toast: 'cn-toast shadow-lg' },
      }}
      {...props}
    />
  )
}

export { Toaster }
