import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { routes } from '@/app/routes'
import { readEntryToken, saveEntryToken } from '@/auth/entryTokens'
import { readHeldSeats, saveHeldSeats, type StoredHold } from '@/auth/heldSeats'
import { mockDb, toKstIso } from '@/mocks/db'
import { fail, ok } from '@/mocks/respond'
import { setMockScenario } from '@/mocks/scenario'
import { mockServer } from '@/mocks/server'
import { readPaymentNavigation } from '@/pages/payment/paymentNavigation'
import { queueNavigationState, readQueueConcert } from '@/pages/queue/queueNavigation'
import {
  CONCERT_ID,
  ON_SALE_SCHEDULE_ID,
  recordRequests,
  SEAT_A1,
  SEAT_A2,
  SEAT_A3,
  signInAsDemo,
} from '@/test/api'

const CONCERT_TITLE = '노을섬 단독 콘서트 〈새벽의 좌석〉'
const SEATS_PATH = `/schedules/${ON_SALE_SCHEDULE_ID}/seats`
const QUEUE_PATH = `/schedules/${ON_SALE_SCHEDULE_ID}/queue`
const SEATS_API = `/api/v1/schedules/${ON_SALE_SCHEDULE_ID}/seats`
const HOLD_API = `/api/v1/schedules/${ON_SALE_SCHEDULE_ID}/reservations`
const DEMO_USER_ID = 42
// 회차 31: A구역 VIP 16석(1001~1016, 2열 8석), B구역 R 30석(1101~1130, 3열 10석), C구역 S 매진(1201~1206)
const SEAT_A_ROW2_NUMBER8 = 1016
const SEAT_B1 = 1101

const CONCERT = {
  concertId: CONCERT_ID,
  title: CONCERT_TITLE,
  startsAt: '2026-11-20T19:00:00+09:00',
  venueName: '한강 아트홀',
}

beforeEach(async () => {
  await signInAsDemo()
})

afterEach(() => {
  vi.useRealTimers()
  Reflect.deleteProperty(document, 'visibilityState')
})

// Gateway처럼 토큰만으로 통하는 목 입장 토큰을 저장한다
function storeEntryToken(validMillis = 10 * 60_000) {
  const expiresAtMillis = Date.now() + validMillis
  const entry = {
    token: `mock-entry.${ON_SALE_SCHEDULE_ID}.${DEMO_USER_ID}.${expiresAtMillis}`,
    expiresAt: new Date(expiresAtMillis).toISOString(),
  }
  saveEntryToken(ON_SALE_SCHEDULE_ID, entry)
  return entry
}

// 조회 재시도를 끈 클라이언트로 그린다. 앱 설정(1초·2초 간격 재시도)을 기다리지 않고 실패 화면을 바로 본다
function renderSeats({ concert = true }: { concert?: boolean } = {}) {
  const router = createMemoryRouter(routes, {
    initialEntries: [
      { pathname: SEATS_PATH, state: concert ? queueNavigationState(CONCERT) : null },
    ],
  })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router }
}

async function renderLoaded(options?: { concert?: boolean }) {
  storeEntryToken()
  const rendered = renderSeats(options)
  await screen.findByRole('radio', { name: /^A구역 VIP/ })
  return rendered
}

const seatButton = (section: string, row: number, number: number) =>
  screen.getByRole('button', { name: new RegExp(`${section}구역 ${row}열 ${number}번,`) })

// 목 좌석 ID로 고른다. 1001~1016은 A구역(1열 1~8번, 2열 1~8번)
function pickA(...ids: number[]) {
  return ids.reduce(async (previous, id) => {
    await previous
    const row = id <= 1008 ? 1 : 2
    const number = ((id - 1001) % 8) + 1
    await userEvent.click(seatButton('A', row, number))
  }, Promise.resolve())
}

const submitButton = (quantity = 2) =>
  screen.getByRole('button', { name: `${quantity}매 선택 완료` })

const summary = () => screen.getByRole('complementary')

function holdKeys(requests: Request[]) {
  return requests.map((request) => request.headers.get('Idempotency-Key'))
}

const unknown504 = () => fail(504, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다')
const conflict409 = () => fail(409, 'SEAT_ALREADY_HELD', '이미 선택된 좌석입니다')
const entryRejected403 = () => fail(403, 'ENTRY_TOKEN_INVALID', '입장 토큰이 올바르지 않습니다')

// 선점 요청에 앞에서부터 하나씩 답하고, 다 쓰면 기본 목 핸들러(정상 선점)로 넘긴다
function scriptHoldThenDefault(...responders: (() => Response)[]) {
  const queue = [...responders]
  mockServer.use(http.post(HOLD_API, () => queue.shift()?.()))
}

// 선점 요청에 앞에서부터 하나씩 답하고, 다 쓰면 마지막 답을 되풀이한다
function scriptHoldRepeatingLast(...responders: (() => Response)[]) {
  const queue = [...responders]
  mockServer.use(http.post(HOLD_API, () => (queue.length > 1 ? queue.shift()! : queue[0])()))
}

// 목 데이터(회차 31)를 서버 시각만 바꿔 돌려주는 좌석 조회
function serveSeatsAt(serverMillis: number) {
  mockServer.use(
    http.get(SEATS_API, () => {
      const sections = new Map<
        string,
        { section: string; grade: string; price: number; seats: unknown[] }
      >()
      for (const seat of mockDb.seats.filter(
        (candidate) => candidate.scheduleId === ON_SALE_SCHEDULE_ID,
      )) {
        const section = sections.get(seat.section) ?? {
          section: seat.section,
          grade: seat.grade,
          price: seat.price,
          seats: [],
        }
        section.seats.push({
          seatId: seat.seatId,
          row: seat.row,
          number: seat.number,
          available: seat.available,
        })
        sections.set(seat.section, section)
      }
      return ok({
        scheduleId: ON_SALE_SCHEDULE_ID,
        serverTime: toKstIso(serverMillis),
        sections: [...sections.values()],
      })
    }),
  )
}

// 가짜 시계로 다시 보내기 간격(1초·2초·4초, 0~20% 더함)을 건너뛴다
async function skipRetryDelays() {
  await act(() => vi.advanceTimersByTimeAsync(10_000))
}

describe('SeatsPage 입장', () => {
  it('저장한 입장 토큰이 없으면 좌석 요청 없이 대기열로 바꿔 보내고 화면 이동 값을 넘긴다', async () => {
    const seatRequests = recordRequests(SEATS_API)
    const { router } = renderSeats()

    await waitFor(() => expect(router.state.location.pathname).toBe(QUEUE_PATH))
    expect(seatRequests).toHaveLength(0)
    expect(readQueueConcert(router.state.location.state)?.title).toBe(CONCERT_TITLE)
  })
})

describe('SeatsPage 구역과 좌석 고르기', () => {
  it('공연 제목, 구역 배치도, 매수, 안내를 처음 상태로 보인다', async () => {
    await renderLoaded()

    expect(screen.getByRole('heading', { level: 1, name: CONCERT_TITLE })).toBeInTheDocument()
    const zoneA = screen.getByRole('radio', { name: 'A구역 VIP, 남은 16석' })
    expect(zoneA).toBeChecked()
    expect(screen.getByRole('radio', { name: 'B구역 R, 남은 30석' })).not.toBeChecked()
    expect(screen.getByRole('radio', { name: 'C구역 S, 매진' })).toBeDisabled()
    expect(screen.getByRole('radio', { name: '2매' })).toBeChecked()
    expect(await screen.findByText('회차당 최대 4매')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '선택한 좌석' }).parentElement).toHaveTextContent(
      '0 / 2매',
    )
    expect(screen.getByRole('button', { name: '2석 더 골라 주세요' })).toBeDisabled()
    // 고른 구역의 좌석만 그린다
    expect(screen.getByRole('group', { name: 'A구역 좌석' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /B구역 1열 1번/ })).not.toBeInTheDocument()
  })

  it('공연 요약 없이 열면 최대 매수 안내가 없고 매수는 기본 한도 4개다', async () => {
    await renderLoaded({ concert: false })

    expect(screen.getByRole('heading', { level: 1, name: '좌석 선택' })).toBeInTheDocument()
    expect(
      within(screen.getByRole('radiogroup', { name: '매수' })).getAllByRole('radio'),
    ).toHaveLength(4)
    expect(screen.queryByText(/회차당 최대/)).not.toBeInTheDocument()
  })

  it('구역을 옮겨 고르면 두 구역의 좌석이 요약에 남고 구역 이름과 합계에 반영된다', async () => {
    await renderLoaded()

    await userEvent.click(seatButton('A', 1, 1))
    await userEvent.click(screen.getByRole('radio', { name: /^B구역 R/ }))
    expect(screen.getByRole('radio', { name: 'B구역 R, 남은 30석' })).toBeChecked()
    await userEvent.click(seatButton('B', 1, 1))

    expect(
      screen.getByRole('radio', { name: 'A구역 VIP, 남은 16석, 1석 고름' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'B구역 R, 남은 30석, 1석 고름' })).toBeInTheDocument()
    const list = within(summary()).getAllByRole('listitem')
    expect(list).toHaveLength(2)
    expect(list[0]).toHaveTextContent('A구역 1열 1번')
    expect(list[1]).toHaveTextContent('B구역 1열 1번')
    expect(within(summary()).getByText('297,000원')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '2매 선택 완료' })).toBeEnabled()
  })

  it('고른 구역을 다시 눌러도 구역 선택은 그대로다', async () => {
    await renderLoaded()

    await userEvent.click(screen.getByRole('radio', { name: /^A구역 VIP/ }))

    expect(screen.getByRole('radio', { name: /^A구역 VIP/ })).toBeChecked()
    expect(screen.getByRole('group', { name: 'A구역 좌석' })).toBeInTheDocument()
  })

  it('매수 한도에서 더 고르면 토스트로 알리고, 매수를 줄이면 먼저 고른 좌석만 남는다', async () => {
    await renderLoaded()
    await pickA(SEAT_A1, SEAT_A2)

    await userEvent.click(seatButton('A', 1, 3))

    expect(await screen.findByText('먼저 고른 좌석을 해제해 주세요')).toBeInTheDocument()
    expect(within(summary()).getAllByRole('listitem')).toHaveLength(2)

    await userEvent.click(screen.getByRole('radio', { name: '1매' }))

    const list = within(summary()).getAllByRole('listitem')
    expect(list).toHaveLength(1)
    expect(list[0]).toHaveTextContent('A구역 1열 1번')
    expect(screen.getByRole('button', { name: '1매 선택 완료' })).toBeEnabled()
  })

  it('해제하면 초점이 그 자리의 해제 버튼으로, 없으면 앞 해제 버튼으로, 다 비면 제목으로 간다', async () => {
    await renderLoaded()
    await userEvent.click(screen.getByRole('radio', { name: '3매' }))
    await pickA(SEAT_A1, SEAT_A2, SEAT_A3)

    await userEvent.click(screen.getByRole('button', { name: 'A구역 1열 2번 해제' }))
    expect(screen.getByRole('button', { name: 'A구역 1열 3번 해제' })).toHaveFocus()

    await userEvent.click(screen.getByRole('button', { name: 'A구역 1열 3번 해제' }))
    expect(screen.getByRole('button', { name: 'A구역 1열 1번 해제' })).toHaveFocus()

    await userEvent.click(screen.getByRole('button', { name: 'A구역 1열 1번 해제' }))
    expect(screen.getByRole('heading', { name: '선택한 좌석' })).toHaveFocus()
  })
})

describe('SeatsPage 선점', () => {
  it('좌석 번호를 오름차순으로, 멱등키와 입장 토큰 헤더를 붙여 보낸다', async () => {
    const entry = storeEntryToken()
    renderSeats()
    await screen.findByRole('radio', { name: /^A구역 VIP/ })
    const requests = recordRequests(HOLD_API)

    // 큰 번호를 먼저 고른다
    await userEvent.click(seatButton('A', 2, 8))
    await userEvent.click(seatButton('A', 1, 1))
    await userEvent.click(submitButton())

    await screen.findByRole('dialog')
    expect(requests).toHaveLength(1)
    expect(await requests[0].json()).toEqual({ seatIds: [SEAT_A1, SEAT_A_ROW2_NUMBER8] })
    expect(requests[0].headers.get('Idempotency-Key')).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    )
    expect(requests[0].headers.get('X-Entry-Token')).toBe(entry.token)
  })

  it('선점되면 닫을 수 없는 대화상자를 보이고, 결제하러 가면 결제 경로로 바꿔 보내며 값을 넘긴다', async () => {
    const { router } = await renderLoaded()
    await pickA(SEAT_A1, SEAT_A2)
    await userEvent.click(submitButton())

    const dialog = await screen.findByRole('dialog', { name: '좌석을 잡았어요' })
    const seats = within(within(dialog).getByRole('list', { name: '잡은 좌석' })).getAllByRole(
      'listitem',
    )
    expect(seats).toHaveLength(2)
    expect(seats[0]).toHaveTextContent('A구역 1열 1번')
    expect(seats[1]).toHaveTextContent('A구역 1열 2번')
    expect(within(dialog).getByRole('timer')).toHaveTextContent(/결제 가능\s*0[45]:\d\d/)

    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('dialog', { name: '좌석을 잡았어요' })).toBeInTheDocument()

    await userEvent.click(within(dialog).getByRole('button', { name: '결제하러 가기' }))

    await waitFor(() =>
      expect(router.state.location.pathname).toMatch(/^\/reservations\/\d+\/payment$/),
    )
    const navigation = readPaymentNavigation(router.state.location.state)
    expect(navigation?.hold.seatIds).toEqual([SEAT_A1, SEAT_A2])
    expect(navigation?.hold.status).toBe('HELD')
    expect(navigation?.seats.map((seat) => `${seat.section}${seat.row}-${seat.number}`)).toEqual([
      'A1-1',
      'A1-2',
    ])
    expect(navigation?.concert?.title).toBe(CONCERT_TITLE)
    // 결제 화면에서 뒤로 가도 좌석 화면으로 돌아오지 않는다
    expect(router.state.location.pathname).not.toBe(SEATS_PATH)
  })

  it('공연 요약 없이 결제로 가면 이동 값의 공연이 비어 있다', async () => {
    const { router } = await renderLoaded({ concert: false })
    await pickA(SEAT_A1, SEAT_A2)
    await userEvent.click(submitButton())
    await userEvent.click(await screen.findByRole('button', { name: '결제하러 가기' }))

    await waitFor(() => expect(router.state.location.pathname).toMatch(/payment$/))
    expect(readPaymentNavigation(router.state.location.state)?.concert).toBeNull()
  })

  it('결제로 갈 때 좌석 응답의 서버 시각으로 잰 시계 차이(숫자)를 이동 값에 넣는다', async () => {
    // 서버 시각은 브라우저보다 5분 느리다
    storeEntryToken()
    serveSeatsAt(Date.now() - 5 * 60_000)
    const { router } = renderSeats()
    await screen.findByRole('radio', { name: /^A구역 VIP/ })
    await pickA(SEAT_A1, SEAT_A2)
    await userEvent.click(submitButton())
    await userEvent.click(await screen.findByRole('button', { name: '결제하러 가기' }))

    await waitFor(() => expect(router.state.location.pathname).toMatch(/payment$/))
    const offset = readPaymentNavigation(router.state.location.state)?.clockOffsetMillis
    expect(typeof offset).toBe('number')
    expect(offset).toBeLessThan(-290_000)
    expect(offset).toBeGreaterThan(-310_000)
  })

  it('이미 선택된 좌석이면 고를 수 없게 된 좌석만 빼고 그 구역으로 옮겨 알리고, 다시 고르면 새 멱등키로 잡는다', async () => {
    setMockScenario({ seatHold: 'seat-conflict' })
    await renderLoaded()
    const requests = recordRequests(HOLD_API)

    // 목은 요청한 좌석 중 번호가 작은 첫 좌석(A구역 1열 1번)을 남에게 넘긴다. 마지막으로 보는 구역은 B다
    await userEvent.click(seatButton('A', 1, 1))
    await userEvent.click(screen.getByRole('radio', { name: /^B구역 R/ }))
    await userEvent.click(seatButton('B', 1, 1))
    await userEvent.click(submitButton())

    const warning = await screen.findByText('이미 선택된 좌석입니다')
    expect(warning.closest('[role="alert"]')).toHaveTextContent(
      'A구역 1열 1번이 방금 다른 분께 선택됐어요',
    )
    // 알림 자리로 초점이 간다
    expect(warning.closest('[tabindex="-1"]')).toHaveFocus()
    // 빠진 좌석 구역으로 옮기고, 남은 좌석은 그대로다
    expect(screen.getByRole('radio', { name: /^A구역 VIP/ })).toBeChecked()
    const list = within(summary()).getAllByRole('listitem')
    expect(list).toHaveLength(1)
    expect(list[0]).toHaveTextContent('B구역 1열 1번')
    expect(screen.getByRole('button', { name: /A구역 1열 1번, .*선택할 수 없음/ })).toBeDisabled()

    await userEvent.click(seatButton('A', 1, 2))
    await userEvent.click(submitButton())

    await screen.findByRole('dialog', { name: '좌석을 잡았어요' })
    expect(requests).toHaveLength(2)
    expect(await requests[1].json()).toEqual({ seatIds: [SEAT_A2, SEAT_B1] })
    const [first, second] = holdKeys(requests)
    expect(second).not.toBe(first)
  })

  it('결과를 모르면 확인 중임을 알리고 같은 멱등키로 다시 보내 처음 결과를 받는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    setMockScenario({ seatHold: 'hold-unknown' })
    await renderLoaded()
    const requests = recordRequests(HOLD_API)
    await pickA(SEAT_A1, SEAT_A2)

    await userEvent.click(submitButton())

    const checking = await screen.findByText('좌석을 확인하고 있어요')
    expect(checking.closest('[role="status"]')).not.toBeNull()
    expect(screen.queryByRole('button', { name: '2매 선택 완료' })).not.toBeInTheDocument()

    await skipRetryDelays()

    await screen.findByRole('dialog', { name: '좌석을 잡았어요' })
    expect(requests).toHaveLength(2)
    expect(new Set(holdKeys(requests)).size).toBe(1)
    expect(screen.queryByText('좌석을 확인하고 있어요')).not.toBeInTheDocument()
  })

  it('계속 결과를 모르면 확인하지 못했다고 알리고 좌석·매수를 막으며, 결과 확인하기는 같은 좌석을 같은 멱등키로 보낸다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mockServer.use(http.post(HOLD_API, unknown504))
    await renderLoaded()
    const requests = recordRequests(HOLD_API)
    await pickA(SEAT_A1, SEAT_A2)

    await userEvent.click(submitButton())
    await skipRetryDelays()

    const warning = await screen.findByText('좌석을 잡았는지 확인하지 못했어요')
    expect(warning.closest('[tabindex="-1"]')).toHaveFocus()
    const firstRound = requests.length
    expect(firstRound).toBeGreaterThan(1)
    expect(new Set(holdKeys(requests)).size).toBe(1)
    // 키가 남아 있는 동안 좌석·매수·해제·다시 고르기가 막힌다
    expect(screen.getByRole('radio', { name: '3매' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'A구역 1열 1번 해제' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '다시 고르기' })).toBeDisabled()
    await userEvent.click(seatButton('A', 1, 3))
    expect(within(summary()).getAllByRole('listitem')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: '2매 선택 완료' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '결과 확인하기' }))
    await skipRetryDelays()

    await waitFor(() => expect(requests.length).toBe(firstRound * 2))
    expect(new Set(holdKeys(requests)).size).toBe(1)
    expect(await requests[firstRound].json()).toEqual({ seatIds: [SEAT_A1, SEAT_A2] })
  })

  it('결과를 모른 채 다른 좌석 고르기를 누르면 알림이 닫히고 다시 고를 수 있으며 다음 요청은 새 멱등키다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    scriptHoldThenDefault(unknown504, unknown504, unknown504, unknown504)
    await renderLoaded()
    const requests = recordRequests(HOLD_API)
    await pickA(SEAT_A1, SEAT_A2)
    await userEvent.click(submitButton())
    await skipRetryDelays()
    await screen.findByText('좌석을 잡았는지 확인하지 못했어요')
    expect(requests).toHaveLength(4)

    await userEvent.click(screen.getByRole('button', { name: '다른 좌석 고르기' }))

    expect(screen.queryByText('좌석을 잡았는지 확인하지 못했어요')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: '좌석 선택' })).toHaveFocus()
    expect(screen.getByRole('radio', { name: '3매' })).toBeEnabled()
    await userEvent.click(screen.getByRole('button', { name: 'A구역 1열 2번 해제' }))
    await userEvent.click(seatButton('A', 1, 3))
    await userEvent.click(submitButton())

    await screen.findByRole('dialog', { name: '좌석을 잡았어요' })
    expect(requests).toHaveLength(5)
    expect(await requests[4].json()).toEqual({ seatIds: [SEAT_A1, SEAT_A3] })
    const keys = holdKeys(requests)
    expect(new Set(keys.slice(0, 4)).size).toBe(1)
    expect(keys[4]).not.toBe(keys[0])
  })

  it('결과를 모른 요청 뒤 409는 기다렸다 같은 키로 다시 확인해 잡혔으면 좌석 확보 대화상자를 보인다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    scriptHoldThenDefault(unknown504, conflict409)
    await renderLoaded()
    const requests = recordRequests(HOLD_API)
    await pickA(SEAT_A1, SEAT_A2)

    await userEvent.click(submitButton())
    await skipRetryDelays()

    await screen.findByRole('dialog', { name: '좌석을 잡았어요' })
    expect(requests).toHaveLength(3)
    expect(new Set(holdKeys(requests)).size).toBe(1)
    expect(screen.queryByText('이미 선택된 좌석입니다')).not.toBeInTheDocument()
  })

  it('결과를 모른 요청 뒤 409가 기다린 뒤에도 409면 이미 선택된 좌석으로 알리고 다음 요청은 새 키다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    scriptHoldRepeatingLast(unknown504, conflict409)
    await renderLoaded()
    const requests = recordRequests(HOLD_API)
    await pickA(SEAT_A1, SEAT_A2)

    await userEvent.click(submitButton())
    await skipRetryDelays()

    expect(await screen.findByText('이미 선택된 좌석입니다')).toBeInTheDocument()
    expect(screen.queryByText('좌석을 잡았는지 확인하지 못했어요')).not.toBeInTheDocument()
    expect(requests).toHaveLength(3)

    await userEvent.click(submitButton())
    await waitFor(() => expect(requests).toHaveLength(4))
    const keys = holdKeys(requests)
    expect(new Set(keys.slice(0, 3)).size).toBe(1)
    expect(keys[3]).not.toBe(keys[0])
  })

  it('1인 매수 한도를 넘으면 경고와 내 예매 보기 링크를 보인다', async () => {
    mockServer.use(
      http.post(HOLD_API, () =>
        fail(409, 'PURCHASE_LIMIT_EXCEEDED', '회차당 구매 가능 매수를 넘었습니다'),
      ),
    )
    await renderLoaded()
    await pickA(SEAT_A1, SEAT_A2)

    await userEvent.click(submitButton())

    const link = await screen.findByRole('link', { name: '내 예매 보기' })
    expect(link).toHaveAttribute('href', '/me/reservations')
    expect(link.closest('[role="alert"]')).not.toBeNull()
    // 선택은 그대로라 매수를 줄여 다시 보낼 수 있다
    expect(within(summary()).getAllByRole('listitem')).toHaveLength(2)
  })

  it('같은 키 재요청이 이미 결제까지 끝났으면 토스트를 보이고 예매 경로로 바꿔 보낸다', async () => {
    mockServer.use(
      http.post(HOLD_API, () =>
        ok(
          {
            requestId: '6f1b7c0e-3a52-4f6e-9a40-0d5c1c1f2b11',
            reservationId: 777,
            reservationNo: 'R20261104-000777',
            scheduleId: ON_SALE_SCHEDULE_ID,
            seatIds: [SEAT_A1, SEAT_A2],
            status: 'CONFIRMED',
            holdExpiresAt: toKstIso(Date.now() + 5 * 60_000),
            totalAmount: 330000,
            paymentToken: null,
            paymentTokenExpiresAt: null,
          },
          { status: 201, message: '선점되었습니다' },
        ),
      ),
    )
    const { router } = await renderLoaded()
    await pickA(SEAT_A1, SEAT_A2)

    await userEvent.click(submitButton())

    expect(await screen.findByText('이미 결제까지 끝난 예매예요')).toBeInTheDocument()
    await waitFor(() => expect(router.state.location.pathname).toBe('/reservations/777'))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('결제 가능 시간이 지나면 좌석 다시 고르기로 대화상자를 닫고 선택을 비우고 좌석을 다시 받는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mockServer.use(
      http.post(HOLD_API, () =>
        ok(
          {
            requestId: '6f1b7c0e-3a52-4f6e-9a40-0d5c1c1f2b11',
            reservationId: 778,
            reservationNo: 'R20261104-000778',
            scheduleId: ON_SALE_SCHEDULE_ID,
            seatIds: [SEAT_A1, SEAT_A2],
            status: 'HELD',
            holdExpiresAt: toKstIso(Date.now() + 15_000),
            totalAmount: 330000,
            paymentToken: 'mock-payment.778.1',
            paymentTokenExpiresAt: toKstIso(Date.now() + 5_000),
          },
          { status: 201, message: '선점되었습니다' },
        ),
      ),
    )
    await renderLoaded()
    await pickA(SEAT_A1, SEAT_A2)
    await userEvent.click(submitButton())
    await screen.findByRole('dialog', { name: '좌석을 잡았어요' })
    const seatRequests = recordRequests(SEATS_API)

    await act(() => vi.advanceTimersByTimeAsync(8_000))

    const dialog = await screen.findByRole('dialog', { name: '결제 가능 시간이 지났어요' })
    expect(within(dialog).queryByRole('button', { name: '결제하러 가기' })).not.toBeInTheDocument()
    expect(readHeldSeats(ON_SALE_SCHEDULE_ID)).not.toBeNull()
    await userEvent.click(within(dialog).getByRole('button', { name: '좌석 다시 고르기' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    // 저장한 선점을 지우고, 대화상자가 내려가면 초점은 좌석 선택 제목으로 간다
    expect(readHeldSeats(ON_SALE_SCHEDULE_ID)).toBeNull()
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 2, name: '좌석 선택' })).toHaveFocus(),
    )
    expect(within(summary()).queryAllByRole('listitem')).toHaveLength(0)
    expect(screen.getByRole('heading', { name: '선택한 좌석' }).parentElement).toHaveTextContent(
      '0 / 2매',
    )
    await waitFor(() => expect(seatRequests).toHaveLength(1))
  })
})

describe('SeatsPage 입장 시간', () => {
  it('선점이 입장 토큰 거절(403)로 끝나면 좌석을 잠그고, 대기열로 돌아가기는 저장 토큰을 지우고 대기열로 바꿔 보낸다', async () => {
    const entry = storeEntryToken()
    mockServer.use(
      http.post(HOLD_API, () => fail(403, 'ENTRY_TOKEN_INVALID', '입장 토큰이 올바르지 않습니다')),
    )
    const { router } = renderSeats()
    await screen.findByRole('radio', { name: /^A구역 VIP/ })
    await pickA(SEAT_A1, SEAT_A2)

    await userEvent.click(submitButton())

    const alert = (await screen.findByText('입장 시간이 끝났어요')).closest('[role="alert"]')!
    expect(screen.getByRole('group', { name: 'A구역 좌석' })).toHaveAttribute('data-locked')
    expect(seatButton('A', 1, 3)).toBeDisabled()
    expect(alert.closest('[tabindex="-1"]')).toHaveFocus()
    expect(screen.queryByText(/마지막 요청의 결과를 확인하지 못했어요/)).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe(SEATS_PATH)

    // 클라이언트가 이미 지운 토큰이 다시 저장돼 있어도 이 버튼이 지운다
    saveEntryToken(ON_SALE_SCHEDULE_ID, entry)
    await userEvent.click(
      within(alert as HTMLElement).getByRole('button', { name: '대기열로 돌아가기' }),
    )

    await waitFor(() => expect(router.state.location.pathname).toBe(QUEUE_PATH))
    expect(readEntryToken(ON_SALE_SCHEDULE_ID)).toBeNull()
    expect(readQueueConcert(router.state.location.state)?.title).toBe(CONCERT_TITLE)
  })

  it('좌석 조회가 입장 토큰 거절(403)이면 대기열로 자동으로 보내지 않고 안내만 한다', async () => {
    storeEntryToken()
    mockServer.use(
      http.get(SEATS_API, () => fail(403, 'ENTRY_TOKEN_INVALID', '입장 토큰이 올바르지 않습니다')),
    )
    const { router } = renderSeats()

    expect(await screen.findByText('입장 시간이 끝났어요')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '대기열로 돌아가기' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe(SEATS_PATH)
  })

  it('좌석 조회가 서버 오류(500)면 다시 시도로 회복한다', async () => {
    storeEntryToken()
    mockServer.use(
      http.get(
        SEATS_API,
        () => fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다'),
        {
          once: true,
        },
      ),
    )
    renderSeats()

    await userEvent.click(await screen.findByRole('button', { name: '다시 시도' }))

    expect(await screen.findByRole('radio', { name: /^A구역 VIP/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
  })

  it('입장 시간이 다 되면 좌석을 잠그고 대기열로 돌아가기를 보인다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    storeEntryToken(4_000)
    renderSeats()
    await screen.findByRole('radio', { name: /^A구역 VIP/ })
    expect(screen.queryByText('입장 시간이 끝났어요')).not.toBeInTheDocument()

    await act(() => vi.advanceTimersByTimeAsync(8_000))

    expect(await screen.findByText('입장 시간이 끝났어요')).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'A구역 좌석' })).toHaveAttribute('data-locked')
    expect(seatButton('A', 1, 1)).toBeDisabled()
    expect(screen.getByRole('radio', { name: '2매' })).toBeDisabled()
  })
})

describe('SeatsPage 빈 상태', () => {
  it('모든 구역이 매진이면 남은 좌석이 없다고 알리고 공연 요약이 있으면 다른 회차 보기를 준다', async () => {
    mockDb.seats.forEach((seat) => (seat.available = false))
    await renderLoaded()

    expect(screen.getByText('남은 좌석이 없어요')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '다른 회차 보기' })).toHaveAttribute(
      'href',
      `/concerts/${CONCERT_ID}`,
    )
    expect(screen.getByRole('radio', { name: 'A구역 VIP, 매진' })).toBeDisabled()
  })

  it('공연 요약이 없으면 다른 회차 보기 링크가 없다', async () => {
    mockDb.seats.forEach((seat) => (seat.available = false))
    await renderLoaded({ concert: false })

    expect(screen.getByText('남은 좌석이 없어요')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '다른 회차 보기' })).not.toBeInTheDocument()
  })

  it('구역이 없으면 아직 좌석이 없다고 알린다', async () => {
    mockDb.seats = []
    storeEntryToken()
    renderSeats()

    expect(await screen.findByText('이 회차에는 아직 좌석이 없어요')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '공연 상세로' })).toHaveAttribute(
      'href',
      `/concerts/${CONCERT_ID}`,
    )
    expect(screen.queryByRole('radiogroup', { name: '매수' })).not.toBeInTheDocument()
  })
})

describe('SeatsPage 탭 제목', () => {
  function setVisibility(state: 'hidden' | 'visible') {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
  }

  it('탭이 숨은 채 열리면 입장을 알리는 제목을 쓰고, 탭이 보이면 원래 제목으로 되돌린다', async () => {
    document.title = '원래 제목'
    setVisibility('hidden')
    await renderLoaded()

    expect(document.title).toBe('입장했어요 · 좌석을 골라 주세요')

    setVisibility('visible')
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'))
    })

    expect(document.title).toBe('원래 제목')
  })

  it('탭이 보이는 채 열리면 제목을 바꾸지 않는다', async () => {
    document.title = '원래 제목'
    setVisibility('visible')
    await renderLoaded()

    expect(document.title).toBe('원래 제목')
  })
})

describe('SeatsPage 입장 시간 시계', () => {
  // 저장한 expiresAt이 브라우저 시계로는 1분 지났다. 목 서버는 토큰 문자열의 만료로 통과시킨다
  function storeBrowserExpiredEntry() {
    const embeddedExpiry = Date.now() + 10 * 60_000
    saveEntryToken(ON_SALE_SCHEDULE_ID, {
      token: `mock-entry.${ON_SALE_SCHEDULE_ID}.${DEMO_USER_ID}.${embeddedExpiry}`,
      expiresAt: new Date(Date.now() - 60_000).toISOString(),
    })
  }

  it('브라우저 시계로는 지났어도 서버 시각으로 남았으면 좌석을 잠그지 않는다', async () => {
    storeBrowserExpiredEntry()
    // 서버 시각은 브라우저보다 5분 느리다. 서버 기준으로는 4분쯤 남는다
    serveSeatsAt(Date.now() - 5 * 60_000)
    renderSeats()
    await screen.findByRole('radio', { name: /^A구역 VIP/ })

    expect(screen.queryByText('입장 시간이 끝났어요')).not.toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'A구역 좌석' })).not.toHaveAttribute('data-locked')
    expect(seatButton('A', 1, 1)).toBeEnabled()
    expect(screen.getByRole('radio', { name: '2매' })).toBeEnabled()
    const chips = screen.getAllByRole('timer')
    expect(chips.length).toBeGreaterThan(0)
    chips.forEach((chip) => expect(chip).toHaveTextContent(/0[34]:\d\d/))
  })

  it('서버 시각으로도 지났으면 좌석을 잠근다', async () => {
    storeBrowserExpiredEntry()
    serveSeatsAt(Date.now())
    renderSeats()
    await screen.findByRole('radio', { name: /^A구역 VIP/ })

    expect(await screen.findByText('입장 시간이 끝났어요')).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'A구역 좌석' })).toHaveAttribute('data-locked')
    expect(seatButton('A', 1, 1)).toBeDisabled()
  })

  it('결과를 모른 요청 뒤 입장 토큰 거절이면 잡혔을 수 있다고 알리고 내 예매 링크를 준다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    scriptHoldRepeatingLast(unknown504, entryRejected403)
    await renderLoaded()
    await pickA(SEAT_A1, SEAT_A2)

    await userEvent.click(submitButton())
    await skipRetryDelays()

    const alert = (await screen.findByText('입장 시간이 끝났어요')).closest('[role="alert"]')!
    expect(alert).toHaveTextContent('마지막 요청의 결과를 확인하지 못했어요')
    expect(within(alert as HTMLElement).getByRole('link', { name: '내 예매' })).toHaveAttribute(
      'href',
      '/me/reservations',
    )
  })
})

describe('SeatsPage 409 뒤 좌석 다시 받기', () => {
  it('다시 받는 동안 좌석·매수·버튼을 막고, 받은 뒤 겹친 좌석만 뺀다', async () => {
    setMockScenario({ seatHold: 'seat-conflict' })
    await renderLoaded()
    const seatRequests = recordRequests(SEATS_API)
    let release!: () => void
    const gate = new Promise<void>((resolve) => (release = resolve))
    // 다시 받기 응답을 붙잡아 둔다. 풀면 기본 핸들러가 답한다
    mockServer.use(
      http.get(SEATS_API, async () => {
        await gate
      }),
    )
    await pickA(SEAT_A1, SEAT_A2)

    await userEvent.click(submitButton())
    await waitFor(() => expect(seatRequests).toHaveLength(1))

    const busy = submitButton()
    expect(busy).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('radio', { name: '3매' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '다시 고르기' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'A구역 1열 1번 해제' })).toBeDisabled()
    await userEvent.click(seatButton('A', 1, 3))
    expect(within(summary()).getAllByRole('listitem')).toHaveLength(2)

    release()

    expect(await screen.findByText('이미 선택된 좌석입니다')).toBeInTheDocument()
    const list = within(summary()).getAllByRole('listitem')
    expect(list).toHaveLength(1)
    expect(list[0]).toHaveTextContent('A구역 1열 2번')
    expect(screen.getByRole('radio', { name: '3매' })).toBeEnabled()
  })

  it('좌석을 다시 받지 못하면 좌석 다시 불러오기를 주고, 누르면 다시 받아 겹친 좌석을 뺀다', async () => {
    setMockScenario({ seatHold: 'seat-conflict' })
    await renderLoaded()
    mockServer.use(
      http.get(
        SEATS_API,
        () => fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다'),
        {
          once: true,
        },
      ),
    )
    await pickA(SEAT_A1, SEAT_A2)

    await userEvent.click(submitButton())

    expect(await screen.findByText('이미 선택된 좌석이 있어요')).toBeInTheDocument()
    expect(
      screen.getByText('좌석을 다시 불러오지 못했어요. 다시 불러온 뒤 골라 주세요.'),
    ).toBeInTheDocument()
    // 일반 다시 시도 알림은 숨긴다. 겹친 좌석은 아직 선택에 남아 있다
    expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument()
    expect(within(summary()).getAllByRole('listitem')).toHaveLength(2)

    await userEvent.click(screen.getByRole('button', { name: '좌석 다시 불러오기' }))

    expect(await screen.findByText(/A구역 1열 1번이 방금 다른 분께 선택됐어요/)).toBeInTheDocument()
    const list = within(summary()).getAllByRole('listitem')
    expect(list).toHaveLength(1)
    expect(list[0]).toHaveTextContent('A구역 1열 2번')
    expect(screen.queryByRole('button', { name: '좌석 다시 불러오기' })).not.toBeInTheDocument()
  })

  it('깜빡임 표시는 1.6초 뒤 지워져 구역을 다시 열어도 깜빡이지 않고, 알림을 닫으면 초점은 제목으로 간다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    setMockScenario({ seatHold: 'seat-conflict' })
    await renderLoaded()
    await pickA(SEAT_A1, SEAT_A2)

    await userEvent.click(submitButton())

    await screen.findByText('이미 선택된 좌석입니다')
    const flashingSeat = () =>
      screen.getByRole('button', { name: /A구역 1열 1번, .*선택할 수 없음/ })
    expect(flashingSeat()).toHaveAttribute('data-flashing')

    await act(() => vi.advanceTimersByTimeAsync(2_000))
    await userEvent.click(screen.getByRole('radio', { name: /^B구역 R/ }))
    await userEvent.click(screen.getByRole('radio', { name: /^A구역 VIP/ }))

    expect(flashingSeat()).not.toHaveAttribute('data-flashing')

    const alert = screen.getByText('이미 선택된 좌석입니다').closest('[role="alert"]')!
    await userEvent.click(within(alert as HTMLElement).getByRole('button', { name: '닫기' }))

    expect(screen.queryByText('이미 선택된 좌석입니다')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: '좌석 선택' })).toHaveFocus()
  })
})

describe('SeatsPage 저장한 선점 좌석', () => {
  function storedHoldExpiringIn(millis: number): StoredHold {
    return {
      hold: {
        requestId: '6f1b7c0e-3a52-4f6e-9a40-0d5c1c1f2b11',
        reservationId: 901,
        reservationNo: 'R20261104-000901',
        scheduleId: ON_SALE_SCHEDULE_ID,
        seatIds: [SEAT_A1, SEAT_A2],
        status: 'HELD',
        holdExpiresAt: toKstIso(Date.now() + millis + 10_000),
        totalAmount: 330000,
        paymentToken: 'mock-payment.901.42',
        paymentTokenExpiresAt: toKstIso(Date.now() + millis),
      },
      seats: [
        { seatId: SEAT_A1, section: 'A', grade: 'VIP', price: 165000, row: 1, number: 1 },
        { seatId: SEAT_A2, section: 'A', grade: 'VIP', price: 165000, row: 1, number: 2 },
      ],
    }
  }

  it('좌석을 잡으면 회차별로 저장하고, 화면을 다시 열면 대화상자를 바로 띄운다', async () => {
    await renderLoaded()
    await pickA(SEAT_A1, SEAT_A2)
    await userEvent.click(submitButton())
    await screen.findByRole('dialog', { name: '좌석을 잡았어요' })

    const saved = readHeldSeats(ON_SALE_SCHEDULE_ID)
    expect(saved?.hold.seatIds).toEqual([SEAT_A1, SEAT_A2])
    expect(saved?.seats.map((seat) => `${seat.section}${seat.row}-${seat.number}`)).toEqual([
      'A1-1',
      'A1-2',
    ])

    cleanup()
    renderSeats()

    const dialog = await screen.findByRole('dialog', { name: '좌석을 잡았어요' })
    expect(within(dialog).getAllByRole('listitem')).toHaveLength(2)
    expect(within(dialog).getByRole('button', { name: '결제하러 가기' })).toBeInTheDocument()
  })

  it('저장한 선점의 결제 가능 시간이 남았으면 열자마자 대화상자를 띄운다', async () => {
    saveHeldSeats(ON_SALE_SCHEDULE_ID, storedHoldExpiringIn(5 * 60_000))
    storeEntryToken()
    renderSeats()

    const dialog = await screen.findByRole('dialog', { name: '좌석을 잡았어요' })
    expect(within(dialog).getByRole('timer')).toHaveTextContent(/결제 가능\s*0[45]:\d\d/)
  })

  it('결제 가능 시간이 지난 저장값은 대화상자 없이 지운다', async () => {
    saveHeldSeats(ON_SALE_SCHEDULE_ID, storedHoldExpiringIn(-1_000))
    storeEntryToken()
    renderSeats()
    await screen.findByRole('radio', { name: /^A구역 VIP/ })

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(readHeldSeats(ON_SALE_SCHEDULE_ID)).toBeNull()
    expect(seatButton('A', 1, 1)).toBeEnabled()
  })
})
