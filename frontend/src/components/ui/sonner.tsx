import { Toaster as Sonner, type ToasterProps } from 'sonner'
import {
  CircleCheckIcon,
  InfoIcon,
  TriangleAlertIcon,
  OctagonXIcon,
  Loader2Icon,
} from 'lucide-react'

// 설계 4절: 한 줄, 3초. 샘플(.toast)처럼 gray-900 바탕·gray-0 글자로 화면 아래 가운데에 띄운다.
// 다크 모드에서는 같은 변수가 뒤집혀 밝은 바탕·어두운 글자가 된다(design-system.md 2-5절). sonner의 테마 기능은 쓰지 않는다.
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
          '--normal-text': 'var(--gray-0)',
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
