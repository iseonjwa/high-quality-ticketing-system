import type { ReactNode } from 'react'
import { useAssistantWidget } from '@/components/assistant/assistantWidgetContext'

// 예매 문의 위젯을 여는 글자 버튼(헤더 메뉴, 사이트 아래 정보, 공연 상세 "알아 두세요").
// 위젯이 없는 화면이면 그리지 않는다
export function AssistantOpenButton({
  className,
  children,
}: {
  className?: string
  children: ReactNode
}) {
  const { available, open } = useAssistantWidget()
  if (!available) {
    return null
  }
  return (
    <button
      type="button"
      aria-haspopup="dialog"
      onClick={(event) => open(event.currentTarget)}
      className={className}
    >
      {children}
    </button>
  )
}
