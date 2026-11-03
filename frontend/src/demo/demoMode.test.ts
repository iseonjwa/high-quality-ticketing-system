import { describe, expect, it } from 'vitest'
import {
  applyDemoModeParam,
  DEMO_PANEL_DEFAULT_WIDTH,
  demoModeStore,
  disableDemoMode,
  enableDemoMode,
  isDemoModeEnabled,
  setDemoPanelCollapsed,
  setDemoPanelWidth,
} from '@/demo/demoMode'

function openAt(url: string) {
  window.history.replaceState(null, '', url)
}

describe('시연 모드', () => {
  it('기본은 꺼져 있고 패널은 기본 너비로 펼쳐져 있다', () => {
    expect(isDemoModeEnabled()).toBe(false)
    expect(demoModeStore.getState().panelWidth).toBe(DEMO_PANEL_DEFAULT_WIDTH)
    expect(demoModeStore.getState().panelCollapsed).toBe(false)
  })

  it('?demo=on이면 켜고 주소에서 demo만 지운다', () => {
    openAt('/concerts?page=2&demo=on&sort=new#top')

    applyDemoModeParam()

    expect(isDemoModeEnabled()).toBe(true)
    expect(window.location.pathname + window.location.search + window.location.hash).toBe(
      '/concerts?page=2&sort=new#top',
    )
  })

  it('?demo=off면 끄고 주소에서 지운다', () => {
    enableDemoMode()
    openAt('/?demo=off')

    applyDemoModeParam()

    expect(isDemoModeEnabled()).toBe(false)
    expect(window.location.search).toBe('')
  })

  it('모르는 값이나 파라미터가 없으면 상태와 주소를 그대로 둔다', () => {
    enableDemoMode()
    openAt('/?demo=maybe')
    applyDemoModeParam()
    expect(isDemoModeEnabled()).toBe(true)
    expect(window.location.search).toBe('?demo=maybe')

    openAt('/?a=1')
    applyDemoModeParam()
    expect(window.location.search).toBe('?a=1')
  })

  it('켜짐·너비·접힘을 sessionStorage에 저장한다', () => {
    enableDemoMode()
    setDemoPanelWidth(412.6)
    setDemoPanelCollapsed(true)

    const saved = JSON.parse(sessionStorage.getItem('iseonjwa.demo') ?? 'null')
    expect(saved.state).toEqual({ enabled: true, panelWidth: 413, panelCollapsed: true })

    disableDemoMode()
    expect(isDemoModeEnabled()).toBe(false)
  })
})
