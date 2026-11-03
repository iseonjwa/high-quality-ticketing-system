import { useStore } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import { createStore } from 'zustand/vanilla'

export const DEMO_PANEL_DEFAULT_WIDTH = 380

interface DemoModeState {
  enabled: boolean
  panelWidth: number
  panelCollapsed: boolean
}

const DEMO_STORAGE_KEY = 'iseonjwa.demo'
const DEMO_PARAM = 'demo'

// Note: 시연 모드는 주소 `?demo=on`으로 그 탭에서만 켠다. 탭 단위 sessionStorage에 저장해
// 새로고침해도 유지되고 탭을 닫으면 사라지며, 빌드 설정과 무관하게 기본은 꺼져 있다.
export const demoModeStore = createStore<DemoModeState>()(
  persist(
    (): DemoModeState => ({
      enabled: false,
      panelWidth: DEMO_PANEL_DEFAULT_WIDTH,
      panelCollapsed: false,
    }),
    { name: DEMO_STORAGE_KEY, storage: createJSONStorage(() => sessionStorage) },
  ),
)

export function enableDemoMode() {
  demoModeStore.setState({ enabled: true })
}

export function disableDemoMode() {
  demoModeStore.setState({ enabled: false })
}

// 범위 제한은 화면 폭을 아는 패널이 한다.
export function setDemoPanelWidth(width: number) {
  demoModeStore.setState({ panelWidth: Math.round(width) })
}

export function setDemoPanelCollapsed(collapsed: boolean) {
  demoModeStore.setState({ panelCollapsed: collapsed })
}

// 훅을 쓸 수 없는 transport가 쓴다.
export function isDemoModeEnabled(): boolean {
  return demoModeStore.getState().enabled
}

export function useIsDemoMode(): boolean {
  return useStore(demoModeStore, (state) => state.enabled)
}

export function useDemoPanelWidth(): number {
  return useStore(demoModeStore, (state) => state.panelWidth)
}

export function useDemoPanelCollapsed(): boolean {
  return useStore(demoModeStore, (state) => state.panelCollapsed)
}

// 첫 주소의 `demo=on|off`를 적용하고 주소에서 지운다. 라우터가 첫 주소를 읽기 전에 불러야 한다.
// 모르는 값은 건드리지 않는다.
export function applyDemoModeParam() {
  const url = new URL(window.location.href)
  const value = url.searchParams.get(DEMO_PARAM)
  if (value !== 'on' && value !== 'off') {
    return
  }

  if (value === 'on') {
    enableDemoMode()
  } else {
    disableDemoMode()
  }
  url.searchParams.delete(DEMO_PARAM)
  window.history.replaceState(window.history.state, '', url)
}
