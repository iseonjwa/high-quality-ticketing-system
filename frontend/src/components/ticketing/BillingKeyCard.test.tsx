import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { readBillingKeyRecord, saveBillingKeyRecord } from '@/auth/billingKeyRecord'
import { BillingKeyCard } from '@/components/ticketing/BillingKeyCard'
import { Toaster } from '@/components/ui/sonner'
import { hasActiveBillingKey, mockDb } from '@/mocks/db'
import { fail } from '@/mocks/respond'
import { setMockScenario } from '@/mocks/scenario'
import { mockServer } from '@/mocks/server'
import { recordRequests, requestStarted, signInAsDemo } from '@/test/api'

const USER_ID = 42
const REGISTER_API = '/api/v1/payments/billing-keys'
const REVOKE_API = '/api/v1/payments/billing-keys/me'

beforeEach(async () => {
  await signInAsDemo()
})

function renderCards(count = 1) {
  const queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      {Array.from({ length: count }, (_, index) => (
        <div key={index} data-testid={`card-${index}`}>
          <BillingKeyCard userId={USER_ID} />
        </div>
      ))}
      <Toaster />
    </QueryClientProvider>,
  )
}

// 핸들러가 풀어 줄 때까지 응답하지 않는다. 요청 중인 순간을 붙잡는 데 쓴다
function holdResponse() {
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  return { gate, release }
}

function seedActiveCard() {
  mockDb.billingKeys.push({ billingKeyId: 7, userId: USER_ID, status: 'ACTIVE' })
  saveBillingKeyRecord(USER_ID, { cardLast4: '4242', registeredAt: '2026-11-04T03:00:00.000Z' })
}

describe('BillingKeyCard: 표시', () => {
  it('기록이 없으면 기록 없음 배지와 안내, 카드 등록만 보이고 해지 버튼은 없다', () => {
    renderCards()

    expect(screen.getByText('기록 없음')).toBeInTheDocument()
    expect(screen.getByText('이 브라우저에서 등록한 카드가 없어요')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '카드 등록' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /해지/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다른 카드로 바꾸기' })).not.toBeInTheDocument()
  })

  it('기록이 있으면 등록됨 배지와 카드 끝 네 자리·등록일, 해지와 다른 카드로 바꾸기를 보인다', () => {
    // 2026-11-04T03:00Z는 한국 시각으로 같은 날 12시다
    saveBillingKeyRecord(USER_ID, { cardLast4: '4242', registeredAt: '2026-11-04T03:00:00.000Z' })
    renderCards()

    expect(screen.getByText('등록됨')).toBeInTheDocument()
    // 실물 카드 모양: 카드 이름, 끝 네 자리, 등록일을 따로 보인다
    expect(screen.getByText('시연용 카드')).toBeInTheDocument()
    expect(screen.getByText('•••• 4242')).toBeInTheDocument()
    expect(screen.getByText('11.04 등록')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '해지' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다른 카드로 바꾸기' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '카드 등록' })).not.toBeInTheDocument()
  })

  it('카드 끝 네 자리가 없으면 자리만 "•••• ••••"로 보이고 등록일은 그대로 보인다', () => {
    saveBillingKeyRecord(USER_ID, { cardLast4: null, registeredAt: '2026-11-04T03:00:00.000Z' })
    renderCards()

    expect(screen.getByText('•••• ••••')).toBeInTheDocument()
    expect(screen.getByText('11.04 등록')).toBeInTheDocument()
    expect(screen.queryByText(/•••• \d{4}/)).not.toBeInTheDocument()
  })
})

describe('BillingKeyCard: 카드 모양', () => {
  it('등록일은 한국 날짜로 쓴다(UTC로는 아직 전날인 시각)', () => {
    // 2026-11-04T16:00Z는 한국 시각으로 11월 5일 01시다
    saveBillingKeyRecord(USER_ID, { cardLast4: '4242', registeredAt: '2026-11-04T16:00:00.000Z' })
    renderCards()

    expect(screen.getByText('11.05 등록')).toBeInTheDocument()
  })

  it('카드 옆에 해지 안내와 [해지]가 있고, 기록이 없으면 카드 모양 자체가 없다', () => {
    saveBillingKeyRecord(USER_ID, { cardLast4: '4242', registeredAt: '2026-11-04T03:00:00.000Z' })
    renderCards()

    expect(
      screen.getByText(/쓰지 않으려면 해지해 주세요. 해지하면 당첨돼도 결제되지 않아/),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '해지' })).toBeInTheDocument()
  })

  it('기록이 없으면 시연용 카드 모양과 해지 안내를 보이지 않는다', () => {
    renderCards()

    expect(screen.queryByText('시연용 카드')).not.toBeInTheDocument()
    expect(screen.queryByText(/쓰지 않으려면 해지해 주세요/)).not.toBeInTheDocument()
    expect(screen.queryByText(/••••/)).not.toBeInTheDocument()
  })
})

describe('BillingKeyCard: 등록', () => {
  it('등록하면 demo- 인증 값을 보내고 기록을 저장하고 토스트를 띄우고 표시가 바뀐다', async () => {
    const requests = recordRequests(REGISTER_API)
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '카드 등록' }))

    expect(await screen.findByText('자동결제 카드를 등록했어요')).toBeInTheDocument()
    expect(requests).toHaveLength(1)
    expect(await requests[0].json()).toEqual({ authKey: expect.stringMatching(/^demo-/) })
    expect(readBillingKeyRecord(USER_ID)).toMatchObject({ cardLast4: '4242' })
    expect(hasActiveBillingKey(USER_ID)).toBe(true)
    expect(screen.getByText('등록됨')).toBeInTheDocument()
    expect(screen.getByText(/•••• 4242/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '다른 카드로 바꾸기' })).toBeInTheDocument()
  })

  it('다른 카드로 바꾸면 새 카드로 바뀌고 서버에는 ACTIVE가 하나다', async () => {
    seedActiveCard()
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '다른 카드로 바꾸기' }))

    expect(await screen.findByText('자동결제 카드를 등록했어요')).toBeInTheDocument()
    const statuses = mockDb.billingKeys.map((key) => key.status)
    expect(statuses.filter((status) => status === 'ACTIVE')).toHaveLength(1)
  })

  it('카드사가 거절하면 거절 문구를 보이고 기록을 만들지 않는다', async () => {
    mockServer.use(
      http.post(REGISTER_API, () => fail(400, 'BILLING_KEY_REJECTED', '결제사가 거절했습니다')),
    )
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '카드 등록' }))

    expect(await screen.findByText('카드사가 등록을 거절했어요')).toBeInTheDocument()
    expect(readBillingKeyRecord(USER_ID)).toBeNull()
    expect(screen.getByRole('button', { name: '카드 등록' })).toBeInTheDocument()
  })

  it('결제사 장애(503 PG_UNAVAILABLE)면 연결 문구를 보이고 다시 등록할 수 있다', async () => {
    setMockScenario({ billing: 'unavailable' })
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '카드 등록' }))

    expect(await screen.findByText('카드사 연결이 원활하지 않아요')).toBeInTheDocument()
    expect(readBillingKeyRecord(USER_ID)).toBeNull()

    setMockScenario({ billing: 'ok' })
    await userEvent.click(screen.getByRole('button', { name: '카드 등록' }))

    expect(await screen.findByText('자동결제 카드를 등록했어요')).toBeInTheDocument()
    expect(screen.queryByText('카드사 연결이 원활하지 않아요')).not.toBeInTheDocument()
  })

  it('응답이 없거나 504면 등록됐는지 확인하지 못했다고 알리고 기록을 만들지 않는다', async () => {
    mockServer.use(http.post(REGISTER_API, () => fail(504, 'INTERNAL_SERVER_ERROR', '시간 초과')))
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '카드 등록' }))

    expect(await screen.findByText('등록됐는지 확인하지 못했어요')).toBeInTheDocument()
    expect(
      screen.getByText('다시 등록하면 새 카드로 바뀌어요. 쓰지 않으려면 해지해 주세요.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '해지' })).toBeInTheDocument()
    expect(readBillingKeyRecord(USER_ID)).toBeNull()

    mockServer.use(http.post(REGISTER_API, () => HttpResponse.error()))
    await userEvent.click(screen.getByRole('button', { name: '카드 등록' }))

    await waitFor(() => {
      expect(screen.getByText('등록됐는지 확인하지 못했어요')).toBeInTheDocument()
    })
    expect(readBillingKeyRecord(USER_ID)).toBeNull()
  })

  it('그 밖의 오류는 공통 오류 안내로 보이고 등록 전용 문구를 쓰지 않는다', async () => {
    mockServer.use(http.post(REGISTER_API, () => fail(500, 'INTERNAL_SERVER_ERROR', '서버 오류')))
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '카드 등록' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByText('카드사가 등록을 거절했어요')).not.toBeInTheDocument()
    expect(screen.queryByText('카드사 연결이 원활하지 않아요')).not.toBeInTheDocument()
    expect(screen.queryByText('등록됐는지 확인하지 못했어요')).not.toBeInTheDocument()
  })

  it('요청 중에는 등록 버튼이 눌리지 않아 요청이 한 번만 나간다', async () => {
    const { gate, release } = holdResponse()
    const started = requestStarted(REGISTER_API)
    mockServer.use(
      http.post(REGISTER_API, async () => {
        await gate
        return HttpResponse.json(
          {
            status: 201,
            message: '빌링키를 등록했습니다',
            data: { billingKeyId: 9, cardLast4: '4242', status: 'ACTIVE' },
            traceId: 'a'.repeat(32),
          },
          { status: 201 },
        )
      }),
    )
    const requests = recordRequests(REGISTER_API)
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '카드 등록' }))
    await started

    const button = screen.getByRole('button', { name: '카드 등록' })
    expect(button).toHaveAttribute('aria-busy', 'true')
    await userEvent.click(button)
    release()

    expect(await screen.findByText('등록됨')).toBeInTheDocument()
    expect(requests).toHaveLength(1)
  })
})

describe('BillingKeyCard: 해지', () => {
  it('해지를 누르면 확인 대화상자를 먼저 보이고 요청은 아직 보내지 않는다', async () => {
    seedActiveCard()
    const requests = recordRequests(REVOKE_API)
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '해지' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('자동결제 카드를 해지할까요?')).toBeInTheDocument()
    expect(requests).toHaveLength(0)
    expect(readBillingKeyRecord(USER_ID)).not.toBeNull()
  })

  it('취소하면 대화상자만 닫히고 카드와 기록이 그대로다', async () => {
    seedActiveCard()
    const requests = recordRequests(REVOKE_API)
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '해지' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: '취소' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(requests).toHaveLength(0)
    expect(readBillingKeyRecord(USER_ID)).not.toBeNull()
    expect(hasActiveBillingKey(USER_ID)).toBe(true)
    expect(screen.getByText('등록됨')).toBeInTheDocument()
  })

  it('해지하면(revoked true) 기록을 지우고 토스트를 띄우고 없음으로 돌아간다', async () => {
    seedActiveCard()
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '해지' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: '해지' }))

    expect(await screen.findByText('자동결제 카드를 해지했어요')).toBeInTheDocument()
    expect(readBillingKeyRecord(USER_ID)).toBeNull()
    expect(hasActiveBillingKey(USER_ID)).toBe(false)
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(screen.getByText('기록 없음')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '카드 등록' })).toBeInTheDocument()
  })

  it('서버에 카드가 없었으면(revoked false) 다른 토스트를 띄우고 기록은 지운다', async () => {
    // 다른 브라우저에서 이미 해지한 경우다. 이 브라우저의 기록만 남아 있다
    saveBillingKeyRecord(USER_ID, { cardLast4: '4242', registeredAt: '2026-11-04T03:00:00.000Z' })
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '해지' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: '해지' }))

    expect(await screen.findByText('등록된 카드가 없었어요')).toBeInTheDocument()
    expect(readBillingKeyRecord(USER_ID)).toBeNull()
    expect(screen.getByText('기록 없음')).toBeInTheDocument()
  })

  it('요청 중에는 대화상자가 닫히지 않고 버튼이 막힌다', async () => {
    seedActiveCard()
    const { gate, release } = holdResponse()
    const started = requestStarted(REVOKE_API)
    mockServer.use(
      http.delete(REVOKE_API, async () => {
        await gate
        return HttpResponse.json({
          status: 200,
          message: '빌링키를 해지했습니다',
          data: { revoked: true },
          traceId: 'b'.repeat(32),
        })
      }),
    )
    const requests = recordRequests(REVOKE_API)
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '해지' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: '해지' }))
    await started

    expect(within(dialog).getByRole('button', { name: '취소' })).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: '해지' })).toHaveAttribute(
      'aria-busy',
      'true',
    )
    // 닫기 버튼이 없고 Esc로도 닫히지 않는다
    expect(within(dialog).queryByRole('button', { name: /닫기|Close/i })).not.toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: '해지' }))
    release()

    expect(await screen.findByText('자동결제 카드를 해지했어요')).toBeInTheDocument()
    expect(requests).toHaveLength(1)
  })

  it('해지가 실패하면 기록을 지우지 않고 대화상자가 열린 채로 다시 누를 수 있다', async () => {
    seedActiveCard()
    mockServer.use(http.delete(REVOKE_API, () => fail(500, 'INTERNAL_SERVER_ERROR', '서버 오류')))
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '해지' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: '해지' }))

    // 오류 안내는 대화상자 안에 있다. 카드에 두면 모달 뒤에 가려져 읽히지 않는다
    expect(await within(dialog).findByText(/문제가 생겼어요/)).toBeInTheDocument()
    expect(readBillingKeyRecord(USER_ID)).not.toBeNull()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: '해지' })).not.toHaveAttribute(
      'aria-busy',
      'true',
    )
  })
})

describe('BillingKeyCard: 같은 화면의 카드 둘', () => {
  it('한쪽에서 등록하면 다른 쪽도 바로 등록됨으로 바뀐다', async () => {
    renderCards(2)
    const first = within(screen.getByTestId('card-0'))
    const second = within(screen.getByTestId('card-1'))

    await userEvent.click(first.getByRole('button', { name: '카드 등록' }))

    await waitFor(() => {
      expect(second.getByText('등록됨')).toBeInTheDocument()
    })
    expect(second.getByText(/•••• 4242/)).toBeInTheDocument()
    expect(second.getByRole('button', { name: '해지' })).toBeInTheDocument()
  })

  it('한쪽에서 해지하면 다른 쪽도 바로 없음으로 바뀐다', async () => {
    seedActiveCard()
    renderCards(2)
    const first = within(screen.getByTestId('card-0'))
    const second = within(screen.getByTestId('card-1'))
    expect(second.getByText('등록됨')).toBeInTheDocument()

    await userEvent.click(first.getByRole('button', { name: '해지' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: '해지' }))

    await waitFor(() => {
      expect(second.getByText('기록 없음')).toBeInTheDocument()
    })
    expect(second.getByRole('button', { name: '카드 등록' })).toBeInTheDocument()
  })
})

describe('BillingKeyCard: 배포 환경과 기록 밖의 카드', () => {
  it('crypto.randomUUID가 없는 http 주소에서도 등록한다', async () => {
    // 배포는 http://<EC2 주소>라 보안 컨텍스트가 아니고 crypto.randomUUID가 없다(ADR 0040)
    Object.defineProperty(globalThis.crypto, 'randomUUID', { value: undefined, configurable: true })
    try {
      const requests = recordRequests(REGISTER_API)
      renderCards()

      await userEvent.click(screen.getByRole('button', { name: '카드 등록' }))

      expect(await screen.findByText('자동결제 카드를 등록했어요')).toBeInTheDocument()
      expect(await requests[0].json()).toEqual({
        authKey: expect.stringMatching(/^demo-[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-/),
      })
    } finally {
      Reflect.deleteProperty(globalThis.crypto, 'randomUUID')
    }
  })

  it('다른 곳에서 등록한 카드가 서버에 있어도 기록이 없으면 해지 버튼이 없고, 등록하면 새 카드로 바뀐다', async () => {
    mockDb.billingKeys.push({ billingKeyId: 7, userId: USER_ID, status: 'ACTIVE' })
    renderCards()

    expect(screen.queryByRole('button', { name: /해지/ })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '카드 등록' }))

    expect(await screen.findByText('자동결제 카드를 등록했어요')).toBeInTheDocument()
    // 앞 카드는 해지되고 새 카드 하나만 ACTIVE다(BillingKeyService)
    const active = mockDb.billingKeys.filter(
      (key) => key.userId === USER_ID && key.status === 'ACTIVE',
    )
    expect(active).toHaveLength(1)
    expect(active[0].billingKeyId).not.toBe(7)
    expect(hasActiveBillingKey(USER_ID)).toBe(true)
  })

  it('등록 결과를 모르면 안내에서 바로 해지할 수 있고, 해지하면 그 안내가 사라진다', async () => {
    mockServer.use(http.post(REGISTER_API, () => fail(504, 'INTERNAL_SERVER_ERROR', '시간 초과')))
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '카드 등록' }))
    const notice = (await screen.findByText('등록됐는지 확인하지 못했어요')).closest(
      '[data-slot="alert"]',
    )
    expect(notice).not.toBeNull()
    await userEvent.click(within(notice as HTMLElement).getByRole('button', { name: '해지' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: '해지' }))

    expect(await screen.findByText('등록된 카드가 없었어요')).toBeInTheDocument()
    expect(screen.queryByText('등록됐는지 확인하지 못했어요')).not.toBeInTheDocument()
    // 연 버튼(안내의 해지)이 사라졌으니 등록 버튼으로 초점이 간다
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '카드 등록' })).toHaveFocus()
    })
  })
})

describe('BillingKeyCard: 초점과 지난 오류', () => {
  it('취소하면 대화상자를 연 해지 버튼으로 초점이 돌아간다', async () => {
    seedActiveCard()
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '해지' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: '취소' }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '해지' })).toHaveFocus()
    })
  })

  it('해지하면 해지 버튼이 사라지므로 카드 등록 버튼으로 초점이 간다', async () => {
    seedActiveCard()
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '해지' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: '해지' }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '카드 등록' })).toHaveFocus()
    })
  })

  it('해지가 실패한 뒤 대화상자를 다시 열면 지난 오류를 보이지 않는다', async () => {
    seedActiveCard()
    mockServer.use(http.delete(REVOKE_API, () => fail(500, 'INTERNAL_SERVER_ERROR', '서버 오류')))
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '해지' }))
    let dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: '해지' }))
    expect(await within(dialog).findByText(/문제가 생겼어요/)).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('button', { name: '취소' }))
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    await userEvent.click(screen.getByRole('button', { name: '해지' }))
    dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByText(/문제가 생겼어요/)).not.toBeInTheDocument()
  })

  it('카드를 바꾸다 실패한 뒤 해지하면 등록 실패 안내가 사라진다', async () => {
    seedActiveCard()
    setMockScenario({ billing: 'unavailable' })
    renderCards()

    await userEvent.click(screen.getByRole('button', { name: '다른 카드로 바꾸기' }))
    expect(await screen.findByText('카드사 연결이 원활하지 않아요')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '해지' }))
    const dialog = await screen.findByRole('dialog')
    await userEvent.click(within(dialog).getByRole('button', { name: '해지' }))

    expect(await screen.findByText('자동결제 카드를 해지했어요')).toBeInTheDocument()
    expect(screen.queryByText('카드사 연결이 원활하지 않아요')).not.toBeInTheDocument()
  })
})

describe('BillingKeyCard: 다른 탭과 제목', () => {
  it('다른 탭이 기록을 바꾸면(storage 이벤트) 바로 다시 그린다', () => {
    renderCards()
    expect(screen.getByText('기록 없음')).toBeInTheDocument()

    // 다른 탭의 저장은 이 탭의 알림을 거치지 않고 storage 이벤트로만 온다
    const key = `iseonjwa.billingKey.${USER_ID}`
    localStorage.setItem(
      key,
      JSON.stringify({ cardLast4: '4242', registeredAt: '2026-11-04T03:00:00.000Z' }),
    )
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key }))
    })
    expect(screen.getByText('등록됨')).toBeInTheDocument()

    localStorage.removeItem(key)
    act(() => {
      window.dispatchEvent(new StorageEvent('storage', { key: null }))
    })
    expect(screen.getByText('기록 없음')).toBeInTheDocument()
  })

  it('카드가 둘이어도 각 영역의 제목 id가 겹치지 않는다', () => {
    renderCards(2)

    const regions = screen.getAllByRole('region', { name: '자동결제 카드' })
    expect(regions).toHaveLength(2)
    const ids = regions.map((region) => region.getAttribute('aria-labelledby'))
    expect(new Set(ids).size).toBe(2)
  })
})
