import { useMatches } from 'react-router'

// 화면 폭을 화면이 직접 정하는 경로. 홈과 공연 상세는 대표 배너를 화면 끝까지 그려야 해서
// 레이아웃의 가운데 정렬 상자(최대 1200px, 위아래 여백)를 쓰지 않는다. 경로 handle에 { layout: 'bleed' }를 둔다.

interface PageLayoutHandle {
  layout: 'bleed'
}

export const bleedLayoutHandle: PageLayoutHandle = { layout: 'bleed' }

export function useIsBleedLayout(): boolean {
  return useMatches().some((match) => isPageLayoutHandle(match.handle))
}

function isPageLayoutHandle(handle: unknown): handle is PageLayoutHandle {
  return (
    typeof handle === 'object' &&
    handle !== null &&
    (handle as Partial<PageLayoutHandle>).layout === 'bleed'
  )
}
