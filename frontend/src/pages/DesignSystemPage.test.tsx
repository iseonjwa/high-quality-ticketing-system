import { screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DesignSystemPage } from '@/pages/DesignSystemPage'
import { renderRoutes } from '@/test/render'

afterEach(() => {
  vi.restoreAllMocks()
})

// 확인 화면은 모든 컴포넌트를 상태별로 쓴다. 오류 없이 그려지는지만 봐도 컴포넌트 조합의 회귀를 잡는다.
describe('DesignSystemPage', () => {
  it('콘솔 오류 없이 모든 구역을 그린다', () => {
    const consoleError = vi.spyOn(console, 'error')

    renderRoutes([{ path: '/', element: <DesignSystemPage /> }], '/')

    expect(screen.getByRole('heading', { name: '디자인 시스템 확인 화면' })).toBeInTheDocument()
    expect(screen.getByRole('radiogroup', { name: '매수' })).toBeInTheDocument()
    expect(screen.getByRole('timer', { name: '오픈까지 남은 시간' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /석 .구역 .열 .번/ }).length).toBeGreaterThan(20)
    expect(consoleError).not.toHaveBeenCalled()
  })
})
