import { useContext, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { HeaderSlotContext } from '@/components/layout/headerSlotContext'

// 예매 흐름 화면이 헤더 오른쪽(메뉴 앞)에 남은 시간 칩을 둔다(samples/seat-sections.html 상단 바).
// Note: 앱 화면이 56rem보다 좁으면 헤더가 이 자리를 숨긴다(단계 표시·메뉴와 한 줄에 들어가지 않는다). 그 폭에서는
// 화면이 본문 맨 위에 같은 칩을 따로 둔다(`@min-[56rem]:hidden`). 헤더 밖(AppLayout 없이 그린 테스트 등)에서는 아무것도 그리지 않는다.
export function HeaderSlot({ children }: { children: ReactNode }) {
  const target = useContext(HeaderSlotContext)
  return target === null ? null : createPortal(children, target)
}
