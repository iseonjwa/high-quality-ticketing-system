import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readBillingKeyRecord, saveBillingKeyRecord } from '@/auth/billingKeyRecord'
import { clearSession } from '@/auth/session'
import { routes } from '@/app/routes'
import { formatDateTime } from '@/lib/format'
import { mockDb, type MockLottery, type MockLotteryEntry } from '@/mocks/db'
import { fail } from '@/mocks/respond'
import { setMockScenario } from '@/mocks/scenario'
import { mockServer } from '@/mocks/server'
import { recordRequests, signInAsDemo } from '@/test/api'
import { readAuthState } from '@/auth/navigation'

const MINUTE = 60_000
const HOUR = 60 * MINUTE
const DAY = 24 * HOUR
// 목 추첨: 1번 응모 중(최대 2매), 2번 응모 시작 전(1매), 3번 추첨 완료(데모 사용자의 1매 응모가 있다)
const OPEN = 1
const BEFORE_START = 2
const DRAWN = 3
const DEMO_USER_ID = 42

const infoApi = (lotteryId: number) => `/api/v1/lotteries/${lotteryId}`
const entriesApi = (lotteryId: number) => `/api/v1/lotteries/${lotteryId}/entries`
const myEntryApi = (lotteryId: number) => `/api/v1/lotteries/${lotteryId}/entries/me`

// 서버는 Instant를 `Z`로 준다. 목과 같은 모양으로 만들어 화면이 그리는 글자를 기대값으로 쓴다
const instant = (millis: number) => new Date(millis).toISOString().slice(0, 19) + 'Z'
const timeText = (millis: number) => formatDateTime(instant(millis))

beforeEach(async () => {
  await signInAsDemo()
  // jsdom에는 scrollIntoView가 없다
  Element.prototype.scrollIntoView = vi.fn()
})

// 전역 setup은 sessionStorage만 비운다
afterEach(() => {
  vi.useRealTimers()
  localStorage.clear()
})

// 조회 재시도를 끈 클라이언트로 그려 실패 화면을 바로 본다
function renderLottery(path: string = `/lotteries/${OPEN}`) {
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, queryClient }
}

function lotteryRow(lotteryId: number): MockLottery {
  return mockDb.lotteries.find((lottery) => lottery.lotteryId === lotteryId)!
}

// 추첨의 시각과 상태를 지금 기준으로 바꾼다(밀리초 오프셋)
function placeLottery(
  lotteryId: number,
  place: { start?: number; end?: number; draw?: number; status?: MockLottery['status'] },
) {
  const now = Date.now()
  const lottery = lotteryRow(lotteryId)
  if (place.start !== undefined) lottery.entryStartAtMillis = now + place.start
  if (place.end !== undefined) lottery.entryEndAtMillis = now + place.end
  if (place.draw !== undefined) lottery.drawAtMillis = now + place.draw
  if (place.status !== undefined) lottery.status = place.status
  return lottery
}

// 데모 사용자의 응모 행을 서버에 둔다
function seedEntry(lotteryId: number, fields: Partial<MockLotteryEntry> = {}): MockLotteryEntry {
  const entry: MockLotteryEntry = {
    entryId: 9000 + mockDb.lotteryEntries.length,
    lotteryId,
    requestId: crypto.randomUUID(),
    userId: DEMO_USER_ID,
    quantity: 1,
    status: 'ENTERED',
    drawRank: null,
    reservationId: null,
    failReason: null,
    seedStage: 'settled',
    ...fields,
  }
  mockDb.lotteryEntries.push(entry)
  return entry
}

// 3번 추첨의 데모 응모를 지정한 결과로 고정한다
function setDrawnResult(fields: Partial<MockLotteryEntry>) {
  const entry = mockDb.lotteryEntries.find((candidate) => candidate.lotteryId === DRAWN)!
  Object.assign(entry, { seedStage: 'settled', ...fields })
  return entry
}

function saveCard() {
  saveBillingKeyRecord(DEMO_USER_ID, {
    cardLast4: '4242',
    registeredAt: '2026-11-04T03:00:00.000Z',
  })
}

async function advance(millis: number, stepMillis = 1000) {
  for (let elapsed = 0; elapsed < millis; elapsed += stepMillis) {
    await act(() => vi.advanceTimersByTimeAsync(Math.min(stepMillis, millis - elapsed)))
  }
}

const methodOf = (requests: Request[], method: string) =>
  requests.filter((request) => request.method === method)

// 추첨 정보 구역의 단계 배지
const infoSection = () => screen.getByRole('region', { name: '추첨 응모' })
const enterButton = (name: string | RegExp = /매 응모하기$/) =>
  screen.findByRole('button', { name })
const alertOf = (title: string) => screen.getByText(title).closest('[role]') as HTMLElement

async function gate() {
  let release!: () => void
  const closed = new Promise<void>((resolve) => {
    release = resolve
  })
  return { closed, release }
}

describe('LotteryPage 단계 배지', () => {
  it.each([
    ['응모 시작 전', () => placeLottery(BEFORE_START, {}), BEFORE_START],
    ['응모 중', () => placeLottery(OPEN, {}), OPEN],
    ['응모 마감', () => placeLottery(OPEN, { start: -2 * HOUR, end: -HOUR, draw: HOUR }), OPEN],
    ['추첨 중', () => placeLottery(OPEN, { start: -3 * HOUR, end: -2 * HOUR, draw: -HOUR }), OPEN],
    ['추첨 완료', () => placeLottery(DRAWN, {}), DRAWN],
    ['종료', () => placeLottery(DRAWN, { status: 'DONE' }), DRAWN],
  ] as const)('%s', async (label, arrange, lotteryId) => {
    arrange()
    renderLottery(`/lotteries/${lotteryId}`)

    await screen.findByRole('heading', { level: 1, name: '추첨 응모' })
    expect(within(infoSection()).getByText(label)).toBeInTheDocument()
  })

  it('추첨 정보(번호·회차·추첨석·기간·추첨 시각·매수)와 규칙 문구를 보인다', async () => {
    const lottery = lotteryRow(OPEN)
    renderLottery()

    await screen.findByRole('heading', { level: 1, name: '추첨 응모' })
    expect(screen.getByText('추첨 번호 1 · 회차 32 · 추첨석 10석')).toBeInTheDocument()
    expect(
      screen.getByText(
        `${timeText(lottery.entryStartAtMillis)} ~ ${timeText(lottery.entryEndAtMillis)}`,
      ),
    ).toBeInTheDocument()
    expect(screen.getByText(timeText(lottery.drawAtMillis))).toBeInTheDocument()
    expect(screen.getByText('1~2매')).toBeInTheDocument()
    expect(screen.getByText('당첨되면 등록한 카드로 바로 자동결제돼요.')).toBeInTheDocument()
    expect(
      screen.getByText('응모는 한 번만 할 수 있고, 취소한 뒤에는 다시 응모할 수 없어요.'),
    ).toBeInTheDocument()
    expect(screen.getByText('연석은 보장하지 않아요.')).toBeInTheDocument()
  })

  it('가짜 시계로 응모 시작 시각을 지나면 시작 전에서 응모 중으로 바뀌고 버튼이 켜진다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const lottery = placeLottery(BEFORE_START, { start: 3_000, end: DAY, draw: DAY + HOUR })
    renderLottery(`/lotteries/${BEFORE_START}`)

    await screen.findByText('응모 시작 전')
    expect(
      screen.getByText(`${timeText(lottery.entryStartAtMillis)}부터 응모할 수 있어요.`),
    ).toBeInTheDocument()
    expect(await enterButton('1매 응모하기')).toBeDisabled()

    await advance(5_000)

    expect(within(infoSection()).getByText('응모 중')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '1매 응모하기' })).toBeEnabled()
    expect(screen.queryByText(/부터 응모할 수 있어요/)).not.toBeInTheDocument()
  })

  it('가짜 시계로 응모 마감 시각을 지나면 응모 중에서 마감으로 바뀌고 폼이 "응모하지 않았어요"가 된다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    placeLottery(OPEN, { end: 3_000, draw: HOUR })
    renderLottery()

    expect(await enterButton('1매 응모하기')).toBeEnabled()
    expect(within(infoSection()).getByText('응모 중')).toBeInTheDocument()

    await advance(5_000)

    expect(within(infoSection()).getByText('응모 마감')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: '응모하지 않았어요' })).toBeInTheDocument()
    expect(screen.getByText('응모 기간이 끝났어요.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /응모하기$/ })).not.toBeInTheDocument()
  })

  it('가짜 시계로 추첨 시각을 지나면 마감에서 추첨 중으로 바뀐다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    placeLottery(OPEN, { start: -DAY, end: -HOUR, draw: 3_000 })
    renderLottery()

    await screen.findByText('응모 마감')

    await advance(5_000)

    expect(within(infoSection()).getByText('추첨 중')).toBeInTheDocument()
  })
})

describe('LotteryPage 응모하기', () => {
  it('매수를 고르면 버튼 글자가 바뀌고, 최대 1매인 추첨에는 매수 선택이 없다', async () => {
    renderLottery()

    expect(await enterButton('1매 응모하기')).toBeInTheDocument()
    expect(screen.getByText('응모 매수')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('radio', { name: '2매' }))
    expect(screen.getByRole('button', { name: '2매 응모하기' })).toBeInTheDocument()
  })

  it('최대 1매인 추첨에는 매수 선택이 없다', async () => {
    placeLottery(BEFORE_START, { start: -MINUTE })
    renderLottery(`/lotteries/${BEFORE_START}`)

    expect(await enterButton('1매 응모하기')).toBeEnabled()
    expect(screen.queryByText('응모 매수')).not.toBeInTheDocument()
  })

  it('누르면 멱등키 헤더와 매수 본문으로 보내고 응모했어요(응모 완료·매수·결과 발표)를 보인다', async () => {
    const lottery = lotteryRow(OPEN)
    const requests = recordRequests(entriesApi(OPEN))
    renderLottery()

    await userEvent.click(await screen.findByRole('radio', { name: '2매' }))
    await userEvent.click(screen.getByRole('button', { name: '2매 응모하기' }))

    expect(await screen.findByRole('heading', { level: 2, name: '응모했어요' })).toBeInTheDocument()
    expect(screen.getByText('응모 완료')).toBeInTheDocument()
    expect(screen.getByText('2매')).toBeInTheDocument()
    expect(screen.getByText(`${timeText(lottery.drawAtMillis)} 이후`)).toBeInTheDocument()
    const posts = methodOf(requests, 'POST')
    expect(posts).toHaveLength(1)
    expect(posts[0].headers.get('Idempotency-Key')).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    )
    expect(await posts[0].json()).toEqual({ quantity: 2 })
    expect(mockDb.lotteryEntries.find((entry) => entry.lotteryId === OPEN)).toMatchObject({
      userId: DEMO_USER_ID,
      quantity: 2,
      status: 'ENTERED',
    })
    // 응모 뒤에는 폼이 없다
    expect(screen.queryByRole('button', { name: /매 응모하기$/ })).not.toBeInTheDocument()
  })

  it('응답이 504면 "응모됐는지 확인하지 못했어요"와 [응모 결과 확인하기], 매수 잠금이고 같은 키로 다시 보낸다', async () => {
    setMockScenario({ lottery: 'unknown' })
    const requests = recordRequests(entriesApi(OPEN))
    renderLottery()

    await userEvent.click(await screen.findByRole('radio', { name: '2매' }))
    await userEvent.click(screen.getByRole('button', { name: '2매 응모하기' }))

    expect(await screen.findByText('응모됐는지 확인하지 못했어요')).toBeInTheDocument()
    expect(screen.getByText('다시 누르면 같은 응모로 결과를 확인해요.')).toBeInTheDocument()
    // 같은 키에 다른 매수는 409이므로 결과를 모르는 동안 매수를 바꾸지 못한다
    expect(screen.getByRole('radio', { name: '1매' })).toBeDisabled()
    expect(screen.getByRole('radio', { name: '2매' })).toBeDisabled()

    await userEvent.click(screen.getByRole('button', { name: '응모 결과 확인하기' }))

    expect(await screen.findByRole('heading', { level: 2, name: '응모했어요' })).toBeInTheDocument()
    const posts = methodOf(requests, 'POST')
    expect(posts).toHaveLength(2)
    expect(posts[1].headers.get('Idempotency-Key')).toBe(posts[0].headers.get('Idempotency-Key'))
    expect(await posts[1].json()).toEqual({ quantity: 2 })
    expect(mockDb.lotteryEntries.filter((entry) => entry.lotteryId === OPEN)).toHaveLength(1)
  })

  it('다른 실패(서버 오류) 뒤에는 키를 버려, 매수를 바꿔 다시 보내면 새 키다', async () => {
    mockServer.use(
      http.post(
        entriesApi(OPEN),
        () => fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다'),
        {
          once: true,
        },
      ),
    )
    const requests = recordRequests(entriesApi(OPEN))
    renderLottery()

    await userEvent.click(await enterButton('1매 응모하기'))
    expect(await screen.findByText(/문제가 생겼어요/)).toBeInTheDocument()
    // 매수는 잠기지 않는다
    expect(screen.getByRole('radio', { name: '2매' })).toBeEnabled()
    await userEvent.click(screen.getByRole('radio', { name: '2매' }))
    await userEvent.click(screen.getByRole('button', { name: '2매 응모하기' }))

    expect(await screen.findByRole('heading', { level: 2, name: '응모했어요' })).toBeInTheDocument()
    const posts = methodOf(requests, 'POST')
    expect(posts).toHaveLength(2)
    expect(posts[1].headers.get('Idempotency-Key')).not.toBe(
      posts[0].headers.get('Idempotency-Key'),
    )
    expect(await posts[1].json()).toEqual({ quantity: 2 })
  })

  it('같은 매수로 다시 눌러도 다른 실패 뒤에는 새 키다', async () => {
    mockServer.use(
      http.post(
        entriesApi(OPEN),
        () => fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다'),
        {
          once: true,
        },
      ),
    )
    const requests = recordRequests(entriesApi(OPEN))
    renderLottery()

    await userEvent.click(await enterButton('1매 응모하기'))
    await screen.findByText(/문제가 생겼어요/)
    await userEvent.click(screen.getByRole('button', { name: '1매 응모하기' }))

    await screen.findByRole('heading', { level: 2, name: '응모했어요' })
    const keys = methodOf(requests, 'POST').map((request) => request.headers.get('Idempotency-Key'))
    expect(keys).toHaveLength(2)
    expect(keys[1]).not.toBe(keys[0])
  })

  it('이미 응모했다는 409(다른 탭에서 응모)면 내 응모를 다시 받아 결과를 보인다', async () => {
    const requests = recordRequests(myEntryApi(OPEN))
    renderLottery()
    const button = await enterButton('1매 응모하기')
    // 화면을 연 뒤 다른 탭에서 응모했다
    seedEntry(OPEN, { quantity: 2 })

    await userEvent.click(button)

    expect(await screen.findByRole('heading', { level: 2, name: '응모했어요' })).toBeInTheDocument()
    expect(screen.getByText('2매')).toBeInTheDocument()
    expect(methodOf(requests, 'GET')).toHaveLength(2)
  })

  it('응모 기간이 아니라는 409면 안내하고 추첨 정보를 다시 받는다', async () => {
    setMockScenario({ lottery: 'closed' })
    const infoRequests = recordRequests(infoApi(OPEN))
    renderLottery()

    await userEvent.click(await enterButton('1매 응모하기'))

    expect(await screen.findByText('지금은 응모 기간이 아니에요')).toBeInTheDocument()
    await waitFor(() => expect(infoRequests).toHaveLength(2))
    // 키를 버렸으므로 다시 누르면 새 요청이다
    expect(screen.getByRole('button', { name: '1매 응모하기' })).toBeEnabled()
  })
})

describe('LotteryPage 자동결제 카드 안내', () => {
  it('이 브라우저에 카드 기록이 없으면 경고와 [카드 등록]을 보이고, 누르면 자동결제 카드 영역으로 초점을 옮긴다', async () => {
    renderLottery()

    expect(await screen.findByText('자동결제 카드를 확인해 주세요')).toBeInTheDocument()
    expect(
      screen.getByText(
        '당첨되면 등록한 카드로 결제돼요. 카드가 없으면 당첨이 다음 순번으로 넘어가요.',
      ),
    ).toBeInTheDocument()
    const card = await waitFor(() => {
      const found = document.getElementById('billing-key')
      expect(found).not.toBeNull()
      return found!
    })
    expect(card).not.toHaveFocus()

    await userEvent.click(
      within(alertOf('자동결제 카드를 확인해 주세요')).getByRole('button', { name: '카드 등록' }),
    )

    expect(card).toHaveFocus()
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
  })

  it('카드 기록이 있으면 경고가 없다', async () => {
    saveCard()
    renderLottery()

    await enterButton('1매 응모하기')
    expect(screen.queryByText('자동결제 카드를 확인해 주세요')).not.toBeInTheDocument()
    expect(await screen.findByText('등록됨')).toBeInTheDocument()
  })

  it('자동결제 카드 영역에서 등록하면 경고가 바로 사라진다', async () => {
    renderLottery()
    await screen.findByText('자동결제 카드를 확인해 주세요')
    const card = await waitFor(() => {
      const found = document.getElementById('billing-key')
      expect(found).not.toBeNull()
      return found!
    })

    await userEvent.click(within(card).getByRole('button', { name: '카드 등록' }))

    await waitFor(() =>
      expect(screen.queryByText('자동결제 카드를 확인해 주세요')).not.toBeInTheDocument(),
    )
    expect(readBillingKeyRecord(DEMO_USER_ID)).not.toBeNull()
    expect(await within(card).findByText('등록됨')).toBeInTheDocument()
  })

  it('응모한 뒤에는 경고가 없고 카드 영역은 남는다', async () => {
    seedEntry(OPEN)
    renderLottery()

    await screen.findByRole('heading', { level: 2, name: '응모했어요' })
    expect(screen.queryByText('자동결제 카드를 확인해 주세요')).not.toBeInTheDocument()
    expect(await screen.findByText('자동결제 카드')).toBeInTheDocument()
  })
})

describe('LotteryPage 응모 취소', () => {
  async function openDialog() {
    await userEvent.click(await screen.findByRole('button', { name: '응모 취소' }))
    return screen.findByRole('dialog', { name: '응모를 취소할까요?' })
  }

  it('[응모 취소]를 누르면 확인 대화상자가 열리고, 돌아가기는 요청 없이 닫는다', async () => {
    seedEntry(OPEN)
    const requests = recordRequests(myEntryApi(OPEN))
    renderLottery()

    const dialog = await openDialog()
    expect(
      within(dialog).getByText('취소하면 이 추첨에 다시 응모할 수 없어요.'),
    ).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: '돌아가기' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(methodOf(requests, 'DELETE')).toHaveLength(0)
    expect(screen.getByRole('heading', { level: 2, name: '응모했어요' })).toBeInTheDocument()
  })

  it('대화상자에서 확인하면 취소하고 "응모를 취소했어요"를 보인다', async () => {
    seedEntry(OPEN)
    const requests = recordRequests(myEntryApi(OPEN))
    renderLottery()

    const dialog = await openDialog()
    await userEvent.click(within(dialog).getByRole('button', { name: '응모 취소' }))

    expect(
      await screen.findByRole('heading', { level: 2, name: '응모를 취소했어요' }),
    ).toBeInTheDocument()
    expect(screen.getByText('응모 취소', { selector: 'span' })).toBeInTheDocument()
    expect(screen.getByText('취소한 추첨에는 다시 응모할 수 없어요.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(methodOf(requests, 'DELETE')).toHaveLength(1)
    expect(mockDb.lotteryEntries.find((entry) => entry.lotteryId === OPEN)?.status).toBe('CANCELED')
  })

  it('마감이 지나 409면 대화상자 안에 취소할 수 없다고 알리고 추첨 정보와 내 응모를 다시 받는다', async () => {
    seedEntry(OPEN)
    const infoRequests = recordRequests(infoApi(OPEN))
    const meRequests = recordRequests(myEntryApi(OPEN))
    renderLottery()
    const dialog = await openDialog()
    // 화면은 아직 응모 중으로 알지만 서버에서는 마감이 지났다
    placeLottery(OPEN, { end: -1000 })

    await userEvent.click(within(dialog).getByRole('button', { name: '응모 취소' }))

    expect(
      await within(dialog).findByText('응모 마감이 지나 취소할 수 없어요.'),
    ).toBeInTheDocument()
    await waitFor(() => expect(infoRequests).toHaveLength(2))
    expect(methodOf(meRequests, 'GET')).toHaveLength(2)
    expect(mockDb.lotteryEntries.find((entry) => entry.lotteryId === OPEN)?.status).toBe('ENTERED')
  })

  it('요청 중에는 대화상자를 닫을 수 없다', async () => {
    seedEntry(OPEN)
    const { closed, release } = await gate()
    mockServer.use(
      http.delete(myEntryApi(OPEN), async () => {
        await closed
        return undefined
      }),
    )
    const requests = recordRequests(myEntryApi(OPEN))
    renderLottery()
    const dialog = await openDialog()

    await userEvent.click(within(dialog).getByRole('button', { name: '응모 취소' }))
    await waitFor(() => expect(methodOf(requests, 'DELETE')).toHaveLength(1))
    await userEvent.keyboard('{Escape}')

    expect(screen.getByRole('dialog', { name: '응모를 취소할까요?' })).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: '돌아가기' })).toBeDisabled()
    expect(within(dialog).queryByRole('button', { name: /닫기|Close/i })).not.toBeInTheDocument()

    release()
    expect(
      await screen.findByRole('heading', { level: 2, name: '응모를 취소했어요' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('마감 뒤에는 [응모 취소]가 없고, 추첨 시각이 지나면 추첨하고 있다고 알린다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    seedEntry(OPEN, { quantity: 2 })
    placeLottery(OPEN, { start: -DAY, end: -HOUR, draw: 3_000 })
    renderLottery()

    await screen.findByRole('heading', { level: 2, name: '응모했어요' })
    expect(screen.queryByRole('button', { name: '응모 취소' })).not.toBeInTheDocument()
    expect(screen.queryByText(/추첨하고 있어요/)).not.toBeInTheDocument()

    await advance(5_000)

    expect(
      screen.getByText('추첨하고 있어요. 결과가 나오면 이 화면이 바뀌어요.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '응모 취소' })).not.toBeInTheDocument()
  })
})

describe('LotteryPage 결과', () => {
  async function renderDrawn() {
    renderLottery(`/lotteries/${DRAWN}`)
    await waitFor(() =>
      expect(document.querySelector('#entry-title, #entry-result-title')).not.toBeNull(),
    )
  }

  it('CANCELED는 "응모를 취소했어요"이다', async () => {
    setDrawnResult({ status: 'CANCELED' })
    await renderDrawn()

    expect(screen.getByRole('heading', { level: 2, name: '응모를 취소했어요' })).toBeInTheDocument()
  })

  it('WON은 "당첨됐어요"이고 예매 링크가 없다', async () => {
    setDrawnResult({ status: 'WON', drawRank: 1, reservationId: 7001 })
    await renderDrawn()

    expect(screen.getByRole('heading', { level: 2, name: '당첨됐어요' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '예매 보기' })).not.toBeInTheDocument()
  })

  it('PAID는 "당첨되고 결제까지 끝났어요"와 [예매 보기](예매 상세)이다', async () => {
    setDrawnResult({ status: 'PAID', drawRank: 1, reservationId: 7001 })
    await renderDrawn()

    expect(
      screen.getByRole('heading', { level: 2, name: '당첨되고 결제까지 끝났어요' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '예매 보기' })).toHaveAttribute(
      'href',
      '/reservations/7001',
    )
  })

  it('PAID인데 예매 번호가 없으면 [예매 보기]는 내 예매 목록으로 간다', async () => {
    setDrawnResult({ status: 'PAID', drawRank: 1, reservationId: null })
    await renderDrawn()

    expect(screen.getByRole('link', { name: '예매 보기' })).toHaveAttribute(
      'href',
      '/me/reservations',
    )
  })

  it('WAITLISTED는 "예비 순번이에요"와 순번이다', async () => {
    setDrawnResult({ status: 'WAITLISTED', drawRank: 3 })
    await renderDrawn()

    const result = screen.getByRole('region', { name: '예비 순번이에요' })
    expect(result).toHaveTextContent('3번')
    expect(within(result).getByText(/추첨 30분 뒤에 끝나요/)).toBeInTheDocument()
  })

  it('LOST는 "아쉽게도 당첨되지 않았어요"이다', async () => {
    setDrawnResult({ status: 'LOST' })
    await renderDrawn()

    expect(
      screen.getByRole('heading', { level: 2, name: '아쉽게도 당첨되지 않았어요' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '예매 보기' })).not.toBeInTheDocument()
  })

  it.each([
    [
      'BILLING_KEY_MISSING',
      '자동결제가 되지 않았어요',
      '등록된 자동결제 카드가 없어 당첨이 다음 순번으로 넘어갔어요.',
    ],
    [
      'HOLD_EXPIRED',
      '자동결제가 되지 않았어요',
      '결제 결과를 제때 받지 못해 당첨이 다음 순번으로 넘어갔어요. 결제된 금액이 있으면 환불돼요.',
    ],
    ['NO_SEAT', '좌석을 배정하지 못했어요', '승계 마감까지 배정할 추첨석이 남지 않았어요.'],
    [
      'PURCHASE_LIMIT_EXCEEDED',
      '좌석을 배정하지 못했어요',
      '이 회차에서 살 수 있는 매수를 이미 채워 당첨이 다음 순번으로 넘어갔어요.',
    ],
    ['SOMETHING_NEW', '당첨이 다음 순번으로 넘어갔어요', '좌석 배정이나 자동결제가 되지 않았어요.'],
    [null, '당첨이 다음 순번으로 넘어갔어요', '좌석 배정이나 자동결제가 되지 않았어요.'],
  ])('FAILED(%s)는 사유별 안내를 보인다', async (failReason, title, description) => {
    setDrawnResult({ status: 'FAILED', failReason })
    await renderDrawn()

    expect(screen.getByRole('heading', { level: 2, name: title })).toBeInTheDocument()
    expect(screen.getByText(description)).toBeInTheDocument()
  })

  it('추첨 완료 단계에서 응모하지 않은 사용자에게는 "응모하지 않았어요"를 보인다', async () => {
    mockDb.lotteryEntries.length = 0
    renderLottery(`/lotteries/${DRAWN}`)

    expect(
      await screen.findByRole('heading', { level: 2, name: '응모하지 않았어요' }),
    ).toBeInTheDocument()
    expect(screen.getByText('응모 기간이 끝났어요.')).toBeInTheDocument()
  })

  it('PAID에서 [예매 보기]를 누르면 확정된 예매 상세가 열린다', async () => {
    // 목 기본 시나리오: 처음 조회 WON, 다음 조회부터 PAID(예매 확정)
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { router } = renderLottery(`/lotteries/${DRAWN}`)
    await screen.findByRole('heading', { level: 2, name: '당첨됐어요' })

    await advance(12_000)
    const link = await screen.findByRole('link', { name: '예매 보기' })
    await userEvent.click(link)

    await waitFor(() => expect(router.state.location.pathname).toMatch(/^\/reservations\/\d+$/))
    expect(await screen.findAllByText(/티켓 번호/)).toHaveLength(1)
    expect(screen.getByText('예매 확정')).toBeInTheDocument()
  })
})

describe('LotteryPage 다시 받기', () => {
  it('추첨 시각 전의 ENTERED는 내 응모를 다시 받지 않는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    seedEntry(OPEN)
    const requests = recordRequests(myEntryApi(OPEN))
    renderLottery()
    await screen.findByRole('heading', { level: 2, name: '응모했어요' })

    await advance(60_000, 5_000)

    expect(methodOf(requests, 'GET')).toHaveLength(1)
  })

  it('추첨 시각이 지나면 10초마다 받다가 WON이 되면 결과를, PAID가 되면 예매 보기를 보인다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const entry = seedEntry(OPEN)
    // 응모 마감은 지났고 추첨은 5초 뒤다
    placeLottery(OPEN, { start: -DAY, end: -HOUR, draw: 5_000 })
    const requests = recordRequests(myEntryApi(OPEN))
    renderLottery()
    await screen.findByRole('heading', { level: 2, name: '응모했어요' })
    const reads = () => methodOf(requests, 'GET').length
    expect(reads()).toBe(1)

    // 추첨 시각 전에는 받지 않는다
    await advance(4_000)
    expect(reads()).toBe(1)

    // 추첨 시각이 지나면 10초마다 받는다
    await advance(12_000)
    expect(reads()).toBe(2)
    expect(screen.getByRole('heading', { level: 2, name: '응모했어요' })).toBeInTheDocument()

    Object.assign(entry, { status: 'WON', drawRank: 1, reservationId: 7001 })
    await advance(10_000)
    expect(await screen.findByRole('heading', { level: 2, name: '당첨됐어요' })).toBeInTheDocument()
    const afterWon = reads()

    // WON도 계속 받는다
    Object.assign(entry, { status: 'PAID' })
    await advance(10_000)
    expect(reads()).toBe(afterWon + 1)
    expect(
      await screen.findByRole('heading', { level: 2, name: '당첨되고 결제까지 끝났어요' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '예매 보기' })).toHaveAttribute(
      'href',
      '/reservations/7001',
    )

    // PAID는 끝난 상태라 더 받지 않는다
    await advance(40_000, 5_000)
    expect(reads()).toBe(afterWon + 1)
  })

  it('WAITLISTED도 10초마다 받아 승계로 WON이 되면 바뀐다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const entry = setDrawnResult({ status: 'WAITLISTED', drawRank: 3 })
    const requests = recordRequests(myEntryApi(DRAWN))
    renderLottery(`/lotteries/${DRAWN}`)
    await screen.findByRole('region', { name: '예비 순번이에요' })

    await advance(10_000)
    expect(methodOf(requests, 'GET')).toHaveLength(2)
    Object.assign(entry, { status: 'WON', drawRank: 2, reservationId: 7002 })
    await advance(10_000)

    expect(await screen.findByRole('heading', { level: 2, name: '당첨됐어요' })).toBeInTheDocument()
  })

  it('추첨 시각 + 90분이 지나면 내 응모 다시 받기를 멈춘다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    setDrawnResult({ status: 'WON', drawRank: 1, reservationId: 7001 })
    // 한도(추첨 + 90분)가 25초 뒤다
    placeLottery(DRAWN, { draw: -90 * MINUTE + 25_000 })
    const requests = recordRequests(myEntryApi(DRAWN))
    renderLottery(`/lotteries/${DRAWN}`)
    await screen.findByRole('heading', { level: 2, name: '당첨됐어요' })

    await advance(15_000)
    expect(methodOf(requests, 'GET').length).toBeGreaterThanOrEqual(2)

    // 한도를 넘긴 뒤 받은 수는 더 늘지 않는다
    await advance(25_000)
    const settled = methodOf(requests, 'GET').length
    await advance(60_000, 5_000)
    expect(methodOf(requests, 'GET')).toHaveLength(settled)
    expect(screen.getByRole('heading', { level: 2, name: '당첨됐어요' })).toBeInTheDocument()
  })

  it('추첨 중 단계 동안 추첨 정보를 10초마다 받다가 추첨 완료가 되면 멈춘다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    placeLottery(OPEN, { start: -DAY, end: -2 * HOUR, draw: -MINUTE })
    const requests = recordRequests(infoApi(OPEN))
    renderLottery()
    await screen.findByText('추첨 중')
    expect(requests).toHaveLength(1)

    await advance(10_000)
    expect(requests).toHaveLength(2)
    expect(within(infoSection()).getByText('추첨 중')).toBeInTheDocument()

    lotteryRow(OPEN).status = 'DRAWN'
    await advance(10_000)
    expect(await screen.findByText('추첨 완료')).toBeInTheDocument()
    expect(requests).toHaveLength(3)

    await advance(40_000, 5_000)
    expect(requests).toHaveLength(3)
  })

  it('추첨 중이 아닌 단계(응모 중·마감)에서는 추첨 정보를 다시 받지 않는다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    placeLottery(OPEN, { start: -DAY, end: -HOUR, draw: HOUR })
    const requests = recordRequests(infoApi(OPEN))
    renderLottery()
    await screen.findByText('응모 마감')

    await advance(40_000, 5_000)

    expect(requests).toHaveLength(1)
  })

  it('받은 내 응모는 다시 받기가 실패해도 그대로 두고 위에 오류 안내를 보이며, 다시 시도하면 안내가 사라진다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    setDrawnResult({ status: 'WON', drawRank: 1, reservationId: 7001 })
    let calls = 0
    mockServer.use(
      http.get(myEntryApi(DRAWN), () => {
        calls += 1
        return calls === 2
          ? fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다')
          : undefined
      }),
    )
    renderLottery(`/lotteries/${DRAWN}`)
    const title = await screen.findByRole('heading', { level: 2, name: '당첨됐어요' })

    await advance(10_000)

    const retry = await screen.findByRole('button', { name: '다시 시도' })
    expect(screen.getByRole('heading', { level: 2, name: '당첨됐어요' })).toBe(title)
    expect(retry.compareDocumentPosition(title) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.queryByText('아직 응모하지 않았어요')).not.toBeInTheDocument()

    await userEvent.click(retry)

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '다시 시도' })).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('heading', { level: 2, name: '당첨됐어요' })).toBeInTheDocument()
  })

  it('처음 받는 내 응모가 실패하면 결과 자리에 오류 안내와 다시 시도를 보인다', async () => {
    let calls = 0
    mockServer.use(
      http.get(myEntryApi(OPEN), () => {
        calls += 1
        return calls === 1
          ? fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다')
          : undefined
      }),
    )
    renderLottery()

    const retry = await screen.findByRole('button', { name: '다시 시도' })
    expect(screen.queryByRole('button', { name: /응모하기$/ })).not.toBeInTheDocument()
    await userEvent.click(retry)

    expect(await enterButton('1매 응모하기')).toBeInTheDocument()
  })
})

describe('LotteryPage 로그인 전', () => {
  it('추첨 정보와 [로그인하고 응모하기]를 보이고 응모 폼·자동결제 카드는 없다', async () => {
    clearSession()
    renderLottery()

    expect(await screen.findByRole('heading', { level: 1, name: '추첨 응모' })).toBeInTheDocument()
    expect(within(infoSection()).getByText('응모 중')).toBeInTheDocument()
    expect(screen.getByText('로그인하면 응모하고 결과를 볼 수 있어요.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /응모하기$/ })).not.toBeInTheDocument()
    expect(screen.queryByText('자동결제 카드')).not.toBeInTheDocument()
    expect(screen.queryByText('자동결제 카드를 확인해 주세요')).not.toBeInTheDocument()
  })

  it('[로그인하고 응모하기]는 로그인 화면으로 보내며 이 추첨 경로를 돌아올 곳으로 넘긴다', async () => {
    clearSession()
    const { router } = renderLottery()

    await userEvent.click(await screen.findByRole('link', { name: '로그인하고 응모하기' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(readAuthState(router.state.location.state).from).toBe(`/lotteries/${OPEN}`)
  })

  it('로그인 전에는 내 응모를 요청하지 않는다', async () => {
    clearSession()
    const requests = recordRequests(myEntryApi(OPEN))
    renderLottery()

    await screen.findByRole('link', { name: '로그인하고 응모하기' })
    expect(requests).toHaveLength(0)
  })
})

describe('LotteryPage 조회 실패', () => {
  it('없는 추첨은 "찾는 추첨이 없어요"와 공연 목록으로를 보인다', async () => {
    renderLottery('/lotteries/999')

    expect(await screen.findByText(/찾는 추첨이 없어요/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '공연 목록으로' })).toHaveAttribute('href', '/')
  })

  it('주소의 추첨 번호가 숫자가 아니면 조회 없이 같은 안내다', async () => {
    const requests = recordRequests(infoApi(0))
    mockServer.use(http.get('/api/v1/lotteries/abc', () => fail(500, 'X', 'must not be called')))
    renderLottery('/lotteries/abc')

    expect(await screen.findByText(/찾는 추첨이 없어요/)).toBeInTheDocument()
    expect(requests).toHaveLength(0)
  })

  it('서버 오류는 오류 안내와 다시 시도를 보이고, 다시 시도하면 화면을 보인다', async () => {
    let calls = 0
    mockServer.use(
      http.get(infoApi(OPEN), () => {
        calls += 1
        return calls === 1
          ? fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다')
          : undefined
      }),
    )
    renderLottery()

    const retry = await screen.findByRole('button', { name: '다시 시도' })
    expect(screen.queryByText(/찾는 추첨이 없어요/)).not.toBeInTheDocument()
    await userEvent.click(retry)

    expect(await screen.findByRole('heading', { level: 1, name: '추첨 응모' })).toBeInTheDocument()
    expect(calls).toBe(2)
  })
})
