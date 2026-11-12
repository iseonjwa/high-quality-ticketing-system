import { screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/routes'
import { renderCharmPng } from '@/pages/charm/charmImage'
import { charmHash } from '@/pages/charm/charmLink'
import { petTitle } from '@/pages/charm/charmPets'
import { pickCharm } from '@/pages/charm/charmTicket'
import { renderRoutesReady } from '@/test/render'

// jsdom에는 canvas가 없어 PNG 만들기는 대신한다. 실제 PNG는 Chrome에서 확인했다(frontend README 발권 이벤트 절)
vi.mock('@/pages/charm/charmImage', () => ({ renderCharmPng: vi.fn() }))
const renderPng = vi.mocked(renderCharmPng)

const TICKET = {
  r: 2002,
  i: 0,
  n: '꿈꾸는개발자',
  t: '노을섬 단독 콘서트 〈새벽의 좌석〉',
  s: '2026-11-28T19:00:00+09:00',
  z: 'VIP석 · A구역 3열 12번',
  k: 'T20261112-7K3QX9MA',
}

beforeEach(() => {
  URL.createObjectURL = vi.fn(() => 'blob:charm')
  URL.revokeObjectURL = vi.fn()
  renderPng.mockResolvedValue(new Blob(['png'], { type: 'image/png' }))
})

afterEach(() => {
  renderPng.mockReset()
})

const open = (ticket: Record<string, unknown>) =>
  renderRoutesReady(routes, `/charm${charmHash(ticket as typeof TICKET)}`)

describe('CharmPage', () => {
  it('주소 조각의 부적을 그리고, PNG가 준비되면 길게 눌러 저장할 수 있는 이미지와 저장 링크를 보인다', async () => {
    await open(TICKET)
    const { pet, nameIndex } = pickCharm(TICKET.r, TICKET.i)

    expect(
      screen.getByRole('heading', { level: 1, name: '발권 기념 행운 부적' }),
    ).toBeInTheDocument()
    // 저장 링크는 PNG가 준비된 뒤에 생긴다. 그 전에는 같은 이름의 SVG가 그림 자리에 있다
    const save = await screen.findByRole('link', { name: '이미지 저장' })
    const image = screen.getByRole('img', {
      name: new RegExp(`^꿈꾸는개발자님의 부적. 수호펫 ${petTitle(pet, nameIndex)}`),
    })
    expect(image).toHaveAttribute('src', 'blob:charm')
    expect(save).toHaveAttribute('href', 'blob:charm')
    expect(save).toHaveAttribute('download', 'iseonjwa-charm-T20261112-7K3QX9MA.png')
    // jsdom에는 Web Share API가 없다. HTTP 배포 서버처럼 공유 버튼이 보이지 않는다
    expect(screen.queryByRole('button', { name: '공유하기' })).not.toBeInTheDocument()
  })

  it('PNG를 만드는 동안 SVG로 먼저 보이고, 공연 일시가 없으면 티켓 번호의 발권일을 쓴다', async () => {
    renderPng.mockReturnValue(new Promise(() => {}))
    const withoutHeading = { r: TICKET.r, i: TICKET.i, n: TICKET.n, z: TICKET.z, k: TICKET.k }
    await open(withoutHeading)

    const drawing = screen.getByRole('img', { name: /^꿈꾸는개발자님의 부적/ })
    expect(drawing.innerHTML).toContain('이선좌 공연 티켓')
    expect(drawing.innerHTML).toContain('11.12')
    expect(drawing.innerHTML).toContain('발권일')
    expect(screen.getByRole('button', { name: '이미지 만드는 중' })).toBeDisabled()
  })

  it('반짝이로 뽑힌 티켓은 레어 부적이다', async () => {
    // 반짝이는 (5s + 3) mod 16 = 0인 예매, 첫 번째가 9번이다
    expect(pickCharm(9, 0).shiny).toBe(true)
    await open({ ...TICKET, r: 9 })

    expect(
      screen.getByRole('heading', { level: 1, name: '발권 기념 레어 부적' }),
    ).toBeInTheDocument()
    // PNG가 준비되면 SVG가 이미지로 바뀐다. 바뀐 뒤에 본다
    await screen.findByRole('link', { name: '이미지 저장' })
    expect(screen.getByRole('img', { name: /님의 레어 부적/ })).toHaveAttribute('src', 'blob:charm')
  })

  it('PNG를 만들지 못하면 화면 캡처를 안내하고 저장 링크를 두지 않는다', async () => {
    renderPng.mockRejectedValue(new Error('canvas'))
    await open(TICKET)

    expect(await screen.findByRole('alert')).toHaveTextContent('이미지를 만들지 못했어요')
    expect(screen.queryByRole('link', { name: '이미지 저장' })).not.toBeInTheDocument()
  })

  it('조각이 깨진 주소는 QR을 다시 찍으라고 안내한다', async () => {
    await renderRoutesReady(routes, '/charm#broken')

    expect(
      screen.getByRole('heading', { level: 1, name: '부적을 찾지 못했어요' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '이선좌 홈으로' })).toHaveAttribute('href', '/')
    expect(renderPng).not.toHaveBeenCalled()
  })
})
