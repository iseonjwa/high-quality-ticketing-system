import { createContext, useContext } from 'react'

// 예매 문의 위젯을 여는 곳(헤더 메뉴, 사이트 아래 정보, 공연 상세 "알아 두세요")이 쓰는 값.
// available이 false인 화면(예매 흐름·로그인·운영자)에서는 위젯이 없으므로 여는 버튼도 두지 않는다
export interface AssistantWidgetControls {
  available: boolean
  // trigger: 누른 버튼. 닫으면 그리로 초점을 돌려준다
  open: (trigger?: HTMLElement | null) => void
}

export const AssistantWidgetContext = createContext<AssistantWidgetControls>({
  available: false,
  open: () => {},
})

export function useAssistantWidget(): AssistantWidgetControls {
  return useContext(AssistantWidgetContext)
}
