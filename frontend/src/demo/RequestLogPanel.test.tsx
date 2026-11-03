import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  DEMO_PANEL_DEFAULT_WIDTH,
  demoModeStore,
  enableDemoMode,
  setDemoPanelWidth,
} from '@/demo/demoMode'
import { requestLogDashboardUrl } from '@/demo/grafana'
import { recordRequest, REQUEST_LOG_LIMIT, type RequestLogEntry } from '@/demo/requestLog'
import { RequestLogPanel } from '@/demo/RequestLogPanel'

const TRACE_ID = '4bf92f3577b34da6a3ce929d0e0e4736'
const PANEL_NAME = '요청 로그 (시연 모드)'
const WIDTH_VARIABLE = '--demo-panel-width'

const root = document.documentElement
const originalClientWidth = Object.getOwnPropertyDescriptor(root, 'clientWidth')

function setViewportWidth(width: number) {
  Object.defineProperty(root, 'clientWidth', { configurable: true, value: width })
}

function entry(overrides: Partial<Omit<RequestLogEntry, 'id'>> = {}): Omit<RequestLogEntry, 'id'> {
  return {
    startedAt: 1_800_000_000_000,
    method: 'GET',
    path: '/api/v1/concerts',
    status: 200,
    durationMs: 12,
    traceId: TRACE_ID,
    ...overrides,
  }
}

const separator = () => screen.getByRole('separator', { name: '요청 로그 너비' })
const storedWidth = () => demoModeStore.getState().panelWidth

// jsdom에는 포인터 캡처가 없어 끌기 테스트에서 호출만 받아 준다
beforeEach(() => {
  Object.assign(HTMLElement.prototype, {
    setPointerCapture: () => {},
    releasePointerCapture: () => {},
    hasPointerCapture: () => false,
  })
  setViewportWidth(1440)
  enableDemoMode()
})

afterEach(() => {
  if (originalClientWidth === undefined) {
    Reflect.deleteProperty(root, 'clientWidth')
  } else {
    Object.defineProperty(root, 'clientWidth', originalClientWidth)
  }
  root.style.removeProperty(WIDTH_VARIABLE)
})

describe('RequestLogPanel 목록', () => {
  it('기록이 없으면 안내와 0건을 보이고 비우기 버튼은 비활성이다', () => {
    render(<RequestLogPanel />)

    expect(screen.getByText('아직 보낸 요청이 없어요')).toBeInTheDocument()
    expect(screen.getByText('0건')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '기록 비우기' })).toBeDisabled()
  })

  it('최신 기록이 맨 위에 오고 상태 칩과 traceId 없음을 보인다', () => {
    recordRequest(entry({ path: '/api/v1/first', status: 200 }))
    recordRequest(entry({ path: '/api/v1/second', status: 401 }))
    recordRequest(entry({ path: '/api/v1/third', status: 0, traceId: null }))
    render(<RequestLogPanel />)

    const items = screen.getAllByRole('listitem')
    expect(items.map((item) => item.querySelector('p')?.textContent)).toEqual([
      '/api/v1/third',
      '/api/v1/second',
      '/api/v1/first',
    ])
    expect(items[0]).toHaveTextContent('응답 없음')
    expect(items[0]).toHaveTextContent('traceId 없음')
    expect(items[1]).toHaveTextContent('401')
    expect(items[2]).toHaveTextContent('200')
  })

  it('traceId는 Grafana 요청 로그를 새 탭으로 여는 링크다', () => {
    recordRequest(entry())
    render(<RequestLogPanel />)

    const link = screen.getByRole('link', {
      name: new RegExp(`${TRACE_ID}.*Grafana 요청 로그를 새 탭으로 열기`),
    })
    expect(link).toHaveAttribute('href', requestLogDashboardUrl(TRACE_ID, entry().startedAt)!)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('16진수가 아닌 traceId는 링크 없이 글자만 보인다', () => {
    recordRequest(entry({ traceId: 'trace-abc-123' }))
    render(<RequestLogPanel />)

    expect(screen.getByText('trace-abc-123')).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it(`${REQUEST_LOG_LIMIT}건이 쌓이면 최근 ${REQUEST_LOG_LIMIT}건이라고 보인다`, () => {
    for (let i = 0; i < REQUEST_LOG_LIMIT; i++) {
      recordRequest(entry())
    }
    render(<RequestLogPanel />)

    expect(screen.getByText(`최근 ${REQUEST_LOG_LIMIT}건`)).toBeInTheDocument()
  })

  it('기록 비우기를 누르면 빈 상태가 된다', async () => {
    recordRequest(entry())
    render(<RequestLogPanel />)

    await userEvent.click(screen.getByRole('button', { name: '기록 비우기' }))

    expect(screen.getByText('아직 보낸 요청이 없어요')).toBeInTheDocument()
    expect(screen.getByText('0건')).toBeInTheDocument()
  })
})

describe('RequestLogPanel 접기와 끄기', () => {
  it('접으면 좁은 막대가 되고 펼치기 버튼으로 초점이 옮겨 가며 펼치면 접기로 돌아온다', async () => {
    render(<RequestLogPanel />)

    await userEvent.click(screen.getByRole('button', { name: '요청 로그 접기' }))

    const expand = screen.getByRole('button', { name: '요청 로그 펼치기' })
    expect(expand).toHaveFocus()
    expect(expand).toHaveAttribute('aria-expanded', 'false')
    expect(demoModeStore.getState().panelCollapsed).toBe(true)
    expect(root.style.getPropertyValue(WIDTH_VARIABLE)).toBe('44px')
    expect(screen.queryByRole('separator')).not.toBeInTheDocument()

    await userEvent.click(expand)

    expect(screen.getByRole('button', { name: '요청 로그 접기' })).toHaveFocus()
    expect(demoModeStore.getState().panelCollapsed).toBe(false)
  })

  it('시연 모드 끄기를 누르면 시연 모드가 꺼진다', async () => {
    render(<RequestLogPanel />)

    await userEvent.click(screen.getByRole('button', { name: '시연 모드 끄기' }))

    expect(demoModeStore.getState().enabled).toBe(false)
  })
})

describe('RequestLogPanel 너비 막대', () => {
  it('너비 값과 범위를 알린다', () => {
    render(<RequestLogPanel />)

    expect(separator()).toHaveAttribute('aria-valuenow', '380')
    expect(separator()).toHaveAttribute('aria-valuemin', '280')
    expect(separator()).toHaveAttribute('aria-valuemax', '720')
  })

  it('방향키·Home·End로 너비를 바꾸고 Enter로 접는다', async () => {
    const user = userEvent.setup()
    render(<RequestLogPanel />)
    separator().focus()

    await user.keyboard('{ArrowLeft}')
    expect(separator()).toHaveAttribute('aria-valuenow', '396')
    expect(storedWidth()).toBe(396)

    await user.keyboard('{ArrowRight}')
    expect(separator()).toHaveAttribute('aria-valuenow', '380')

    await user.keyboard('{Home}')
    expect(separator()).toHaveAttribute('aria-valuenow', '280')

    await user.keyboard('{End}')
    expect(separator()).toHaveAttribute('aria-valuenow', '720')

    await user.keyboard('{Enter}')
    expect(demoModeStore.getState().panelCollapsed).toBe(true)
  })

  it('화면이 좁으면 상한이 줄고 저장값이 더 커도 보이는 너비는 상한이다', () => {
    setViewportWidth(1000)
    setDemoPanelWidth(900)
    render(<RequestLogPanel />)

    expect(separator()).toHaveAttribute('aria-valuemax', '640')
    expect(separator()).toHaveAttribute('aria-valuenow', '640')
  })

  it('끄는 동안은 보이는 너비만 바뀌고 손을 떼면 저장한다', () => {
    render(<RequestLogPanel />)

    fireEvent.pointerDown(separator(), { button: 0, clientX: 1000, pointerId: 1 })
    fireEvent.pointerMove(separator(), { clientX: 900, pointerId: 1 })

    expect(separator()).toHaveAttribute('aria-valuenow', '480')
    expect(storedWidth()).toBe(380)

    fireEvent.pointerUp(separator(), { pointerId: 1 })

    expect(storedWidth()).toBe(480)
  })

  it('두 번 누르면 기본 너비로 돌아간다', () => {
    setDemoPanelWidth(500)
    render(<RequestLogPanel />)

    fireEvent.doubleClick(separator())

    expect(storedWidth()).toBe(DEMO_PANEL_DEFAULT_WIDTH)
    expect(separator()).toHaveAttribute('aria-valuenow', '380')
  })

  it('창 너비가 바뀌면 상한이 따라 바뀐다', () => {
    render(<RequestLogPanel />)

    setViewportWidth(1000)
    act(() => {
      window.dispatchEvent(new Event('resize'))
    })

    expect(separator()).toHaveAttribute('aria-valuemax', '640')
  })
})

describe('RequestLogPanel 화면 배치', () => {
  it('휴대폰 너비에서는 패널을 그리지 않고 너비 변수도 비운다', () => {
    setViewportWidth(600)
    render(<RequestLogPanel />)

    expect(screen.queryByRole('complementary', { name: PANEL_NAME })).not.toBeInTheDocument()
    expect(root.style.getPropertyValue(WIDTH_VARIABLE)).toBe('')
  })

  it('그려지면 너비 변수를 정하고 언마운트하면 지운다', () => {
    const { unmount } = render(<RequestLogPanel />)

    expect(screen.getByRole('complementary', { name: PANEL_NAME })).toBeInTheDocument()
    expect(root.style.getPropertyValue(WIDTH_VARIABLE)).toBe('380px')

    unmount()

    expect(root.style.getPropertyValue(WIDTH_VARIABLE)).toBe('')
  })
})

describe('RequestLogPanel traceId 복사', () => {
  it('복사 버튼을 누르면 클립보드에 traceId가 들어가고 버튼 이름이 바뀐다', async () => {
    const user = userEvent.setup()
    recordRequest(entry())
    render(<RequestLogPanel />)

    await user.click(screen.getByRole('button', { name: 'traceId 복사' }))

    expect(await navigator.clipboard.readText()).toBe(TRACE_ID)
    expect(screen.getByRole('button', { name: 'traceId 복사함' })).toBeInTheDocument()
  })

  it('클립보드를 쓸 수 없으면 복사 버튼이 없다', () => {
    const original = Object.getOwnPropertyDescriptor(navigator, 'clipboard')
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: undefined })
    try {
      recordRequest(entry())
      render(<RequestLogPanel />)

      expect(screen.queryByRole('button', { name: /traceId 복사/ })).not.toBeInTheDocument()
      expect(screen.getByRole('link')).toBeInTheDocument()
    } finally {
      if (original === undefined) {
        Reflect.deleteProperty(navigator, 'clipboard')
      } else {
        Object.defineProperty(navigator, 'clipboard', original)
      }
    }
  })
})
