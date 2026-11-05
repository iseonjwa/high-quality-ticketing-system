import { createContext } from 'react'

// AppLayout이 헤더에 만든 빈 자리. 화면은 HeaderSlot으로 그 자리를 채운다
export const HeaderSlotContext = createContext<HTMLElement | null>(null)
