import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'
import { mockDb } from '@/mocks/db'
import { mockServer } from '@/mocks/server'
import { CONCERT_ID, recordRequests, requestStarted, signInAsAdmin, signInAsDemo } from '@/test/api'
import { renderRoutes } from '@/test/render'

const UNKNOWN_OUTCOME_FAILURE = {
  status: 504,
  code: 'INTERNAL_SERVER_ERROR',
  message: '응답 시간이 초과됐습니다',
  traceId: '0'.repeat(32),
}

const NOT_ADMIN_NOTICE = '운영자만 볼 수 있는 화면이에요'

// 운영 API(POST /api/v1/admin/...)로 나간 요청을 모두 모은다
function recordAdminPosts(): string[] {
  const paths: string[] = []
  mockServer.events.on('request:start', ({ request }) => {
    const { pathname } = new URL(request.url)
    if (request.method === 'POST' && pathname.startsWith('/api/v1/admin/')) {
      paths.push(pathname)
    }
  })
  return paths
}

// datetime-local 입력은 jsdom에서 user.type이 안정적이지 않아 값을 바로 넣는다
function setValue(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

async function addSchedule(
  user: ReturnType<typeof userEvent.setup>,
  values: { venueId: string; startsAt: string; openAt: string; maxPerUser?: string },
) {
  await user.type(screen.getByLabelText('공연장 번호'), values.venueId)
  setValue('공연 시작', values.startsAt)
  setValue('예매 오픈', values.openAt)
  if (values.maxPerUser !== undefined) {
    await user.type(screen.getByLabelText('1인 최대 매수 (선택)'), values.maxPerUser)
  }
  await user.click(screen.getByRole('button', { name: '회차 등록' }))
}

describe('운영자 경로 보호', () => {
  it.each(['/admin', '/admin/concerts/new', `/admin/concerts/${CONCERT_ID}`])(
    '일반 사용자가 %s 를 열면 안내만 보고 운영 요청은 가지 않는다',
    async (path) => {
      await signInAsDemo()
      const adminPosts = recordAdminPosts()
      const { router } = renderRoutes(routes, path)

      expect(await screen.findByText(NOT_ADMIN_NOTICE)).toBeInTheDocument()
      expect(router.state.location.pathname).toBe(path)
      expect(screen.queryByRole('button', { name: '등록하기' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: '회차 등록' })).not.toBeInTheDocument()
      expect(adminPosts).toEqual([])
    },
  )

  it('로그인하지 않았으면 운영 경로를 돌아올 곳으로 넘기고 로그인 화면으로 보낸다', () => {
    const { router } = renderRoutes(routes, '/admin/concerts/new')

    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.state).toEqual({ from: '/admin/concerts/new' })
  })
})

describe('운영 첫 화면', () => {
  it('공연 목록을 보이고 각 줄은 회차 관리 화면으로, 공연 등록은 등록 화면으로 간다', async () => {
    await signInAsAdmin()
    const user = userEvent.setup()
    const { router } = renderRoutes(routes, '/admin')

    for (const concert of mockDb.concerts) {
      const link = await screen.findByRole('link', { name: new RegExp(concert.title) })
      expect(link).toHaveAttribute('href', `/admin/concerts/${concert.concertId}`)
    }

    await user.click(screen.getByRole('link', { name: '공연 등록' }))
    expect(router.state.location.pathname).toBe('/admin/concerts/new')
  })
})

describe('공연 등록', () => {
  it('제목이 비면 안내하고 요청을 보내지 않는다', async () => {
    await signInAsAdmin()
    const adminPosts = recordAdminPosts()
    const user = userEvent.setup()
    renderRoutes(routes, '/admin/concerts/new')

    await user.click(await screen.findByRole('button', { name: '등록하기' }))

    expect(await screen.findByText('공연 제목을 입력해 주세요')).toBeInTheDocument()
    expect(adminPosts).toEqual([])
  })

  it('등록하면 안내를 띄우고 새 공연의 회차 화면으로 가며, 뒤로 가면 등록 화면이 아니다', async () => {
    await signInAsAdmin()
    const user = userEvent.setup()
    // 운영 첫 화면에서 등록 화면으로 들어가야 등록 화면이 history에 남는지 알 수 있다
    const { router } = renderRoutes(routes, '/admin')

    await user.click(await screen.findByRole('link', { name: '공연 등록' }))
    await user.type(await screen.findByLabelText('공연 제목'), '새 공연')
    await user.click(screen.getByRole('button', { name: '등록하기' }))

    expect(await screen.findByText('공연을 등록했어요. 회차를 추가해 주세요')).toBeInTheDocument()
    const created = mockDb.concerts.find((concert) => concert.title === '새 공연')
    expect(created).toBeDefined()
    expect(router.state.location.pathname).toBe(`/admin/concerts/${created?.concertId}`)
    expect(await screen.findByRole('heading', { name: '새 공연' })).toBeInTheDocument()

    await act(async () => {
      await router.navigate(-1)
    })
    expect(router.state.location.pathname).toBe('/admin')
  })
})

describe('공연 등록: 결과를 모를 때', () => {
  it('504면 실패가 아니라 확인 안내를 보이고 등록 화면에 남으며 목록으로 가는 링크가 있다', async () => {
    await signInAsAdmin()
    mockServer.use(
      http.post('/api/v1/admin/concerts', () =>
        HttpResponse.json(UNKNOWN_OUTCOME_FAILURE, { status: 504 }),
      ),
    )
    const user = userEvent.setup()
    const { router } = renderRoutes(routes, '/admin/concerts/new')

    await user.type(await screen.findByLabelText('공연 제목'), '새 공연')
    await user.click(screen.getByRole('button', { name: '등록하기' }))

    expect(await screen.findByText(/등록됐는지 확인하지 못했어요/)).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/admin/concerts/new')
    expect(screen.getByRole('link', { name: '운영 목록 보기' })).toHaveAttribute('href', '/admin')
    expect(screen.getByLabelText('공연 제목')).toHaveValue('새 공연')
  })

  it('응답을 받지 못해도 같다', async () => {
    await signInAsAdmin()
    mockServer.use(http.post('/api/v1/admin/concerts', () => HttpResponse.error()))
    const user = userEvent.setup()
    const { router } = renderRoutes(routes, '/admin/concerts/new')

    await user.type(await screen.findByLabelText('공연 제목'), '새 공연')
    await user.click(screen.getByRole('button', { name: '등록하기' }))

    expect(await screen.findByText(/등록됐는지 확인하지 못했어요/)).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/admin/concerts/new')
  })
})

describe('회차 추가', () => {
  async function openConcert() {
    await signInAsAdmin()
    const user = userEvent.setup()
    const rendered = renderRoutes(routes, `/admin/concerts/${CONCERT_ID}`)
    await screen.findByRole('heading', { name: '회차 추가' })
    return { user, ...rendered }
  }

  it('오픈이 시작보다 늦으면 안내하고 요청을 보내지 않는다', async () => {
    const { user } = await openConcert()
    const adminPosts = recordAdminPosts()

    await addSchedule(user, {
      venueId: '1',
      startsAt: '2026-12-20T19:00',
      openAt: '2026-12-21T19:00',
    })

    expect(await screen.findByText('오픈 시각은 공연 시작보다 앞서야 해요')).toBeInTheDocument()
    expect(adminPosts).toEqual([])
  })

  it('없는 공연장이면 공연장 번호 칸 아래에 안내한다', async () => {
    const { user } = await openConcert()

    await addSchedule(user, {
      venueId: '99',
      startsAt: '2026-12-20T19:00',
      openAt: '2026-12-10T20:00',
    })

    const message = await screen.findByText('이 번호의 공연장이 없어요')
    expect(screen.getByLabelText('공연장 번호')).toHaveAccessibleDescription(
      expect.stringContaining(message.textContent ?? ''),
    )
  })

  it('올바르게 넣으면 안내와 함께 회차 목록에 한국 시각 그대로 더하고 폼을 비운다', async () => {
    const { user } = await openConcert()
    const requests = recordRequests(`/api/v1/admin/concerts/${CONCERT_ID}/schedules`)

    await addSchedule(user, {
      venueId: '1',
      startsAt: '2026-12-20T19:00',
      openAt: '2026-12-10T20:00',
    })

    expect(
      await screen.findByText('회차를 등록했어요. 좌석을 만들어야 예매할 수 있어요'),
    ).toBeInTheDocument()
    expect(await screen.findByText('2026.12.20 (일) 19:00')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('공연장 번호')).toHaveValue(''))
    expect(screen.getByLabelText('공연 시작')).toHaveValue('')
    expect(screen.getByLabelText('예매 오픈')).toHaveValue('')

    expect(requests).toHaveLength(1)
    const body = await requests[0].json()
    expect(body.startsAt).toBe('2026-12-20T19:00:00+09:00')
    expect(body.openAt).toBe('2026-12-10T20:00:00+09:00')
  })

  it('비운 선택 칸은 본문에 넣지 않고, 넣은 선택 칸은 숫자로 보낸다', async () => {
    const { user } = await openConcert()
    const requests = recordRequests(`/api/v1/admin/concerts/${CONCERT_ID}/schedules`)

    await addSchedule(user, {
      venueId: '1',
      startsAt: '2026-12-20T19:00',
      openAt: '2026-12-10T20:00',
    })
    await screen.findByText('2026.12.20 (일) 19:00')
    expect(await requests[0].json()).toEqual({
      venueId: 1,
      startsAt: '2026-12-20T19:00:00+09:00',
      openAt: '2026-12-10T20:00:00+09:00',
    })

    await addSchedule(user, {
      venueId: '1',
      startsAt: '2026-12-21T19:00',
      openAt: '2026-12-11T20:00',
      maxPerUser: '2',
    })
    await screen.findByText('2026.12.21 (월) 19:00')
    expect(await requests[1].json()).toMatchObject({ maxPerUser: 2 })
  })
})

describe('회차 추가: 결과를 모를 때', () => {
  it('504면 확인 안내를 보이고 공연 상세를 다시 조회하며 폼은 비우지 않는다', async () => {
    await signInAsAdmin()
    mockServer.use(
      http.post(`/api/v1/admin/concerts/${CONCERT_ID}/schedules`, () =>
        HttpResponse.json(UNKNOWN_OUTCOME_FAILURE, { status: 504 }),
      ),
    )
    const user = userEvent.setup()
    renderRoutes(routes, `/admin/concerts/${CONCERT_ID}`)
    await screen.findByRole('heading', { name: '회차 추가' })
    const detailRequests = recordRequests(`/api/v1/concerts/${CONCERT_ID}`)

    await addSchedule(user, {
      venueId: '1',
      startsAt: '2026-12-20T19:00',
      openAt: '2026-12-10T20:00',
    })

    expect(await screen.findByText(/등록됐는지 확인하지 못했어요/)).toBeInTheDocument()
    await waitFor(() => expect(detailRequests.length).toBeGreaterThan(0))
    expect(screen.getByLabelText('공연장 번호')).toHaveValue('1')
    expect(screen.getByLabelText('공연 시작')).toHaveValue('2026-12-20T19:00')
  })
})

describe('좌석 만들기', () => {
  async function openConcert() {
    await signInAsAdmin()
    const user = userEvent.setup()
    renderRoutes(routes, `/admin/concerts/${CONCERT_ID}`)
    await screen.findByRole('heading', { name: '회차 추가' })
    return user
  }

  // 회차 목록의 한 줄
  function scheduleRow(text: string) {
    const row = screen.getByText(text).closest('li')
    if (row === null) {
      throw new Error(`회차 줄을 찾지 못했어요: ${text}`)
    }
    return row
  }

  // 공연장(KSPO DOME)을 아는 새 회차를 만들어 좌석 만들기 대화상자까지 연다
  async function openSeatDialogOfNewSchedule(user: ReturnType<typeof userEvent.setup>) {
    await addSchedule(user, {
      venueId: '1',
      startsAt: '2026-12-20T19:00',
      openAt: '2026-12-10T20:00',
    })
    await screen.findByText('2026.12.20 (일) 19:00')
    await user.click(
      within(scheduleRow('2026.12.20 (일) 19:00')).getByRole('button', { name: '좌석 만들기' }),
    )
    return screen.findByRole('dialog')
  }

  it('공연장에 있는 등급과 다르게 넣으면 대화상자 안에 안내하고 닫지 않는다', async () => {
    const user = await openConcert()
    const dialog = await openSeatDialogOfNewSchedule(user)

    await user.type(within(dialog).getByLabelText('VIP석 가격(원)'), '165000')
    await user.click(within(dialog).getByRole('button', { name: '좌석 만들기' }))

    expect(await within(dialog).findByText(/등급이 공연장 좌석과 맞지 않아요/)).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('세 등급을 넣으면 안내를 띄우고 대화상자를 닫고 그 회차에 만든 좌석 수를 보인다', async () => {
    const user = await openConcert()
    const dialog = await openSeatDialogOfNewSchedule(user)

    await user.type(within(dialog).getByLabelText('VIP석 가격(원)'), '165000')
    await user.type(within(dialog).getByLabelText('R석 가격(원)'), '132000')
    await user.type(within(dialog).getByLabelText('S석 가격(원)'), '99000')
    await user.click(within(dialog).getByRole('button', { name: '좌석 만들기' }))

    // 목은 등급마다 2열 × 10번이다
    expect(await screen.findByText('좌석 60석을 만들었어요')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(within(scheduleRow('2026.12.20 (일) 19:00')).getByText('좌석 60석 만듦')).toBeVisible()
  })

  it('이미 좌석이 있는 회차면 안내하고 그 회차에 이미 있다고 보인다', async () => {
    const user = await openConcert()
    const row = scheduleRow('회차 #31')

    await user.click(within(row).getByRole('button', { name: '좌석 만들기' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('VIP석 가격(원)'), '165000')
    await user.click(within(dialog).getByRole('button', { name: '좌석 만들기' }))

    expect(await screen.findByText('이미 좌석을 만든 회차예요')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(within(scheduleRow('회차 #31')).getByText('좌석이 이미 있어요')).toBeVisible()
  })

  it('가격을 하나도 넣지 않으면 안내하고 요청을 보내지 않는다', async () => {
    const user = await openConcert()
    const adminPosts = recordAdminPosts()

    await user.click(within(scheduleRow('회차 #32')).getByRole('button', { name: '좌석 만들기' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '좌석 만들기' }))

    expect(
      await within(dialog).findByText('적어도 한 등급의 가격을 입력해 주세요'),
    ).toBeInTheDocument()
    expect(adminPosts).toEqual([])
  })

  it('빈 칸만으로 제출하면 알림은 대화상자 안에 보이고 VIP 칸은 오류로 표시되지 않는다', async () => {
    const user = await openConcert()

    await user.click(within(scheduleRow('회차 #32')).getByRole('button', { name: '좌석 만들기' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '좌석 만들기' }))

    expect(
      await within(dialog).findByText('적어도 한 등급의 가격을 입력해 주세요'),
    ).toBeInTheDocument()
    expect(within(dialog).getByLabelText('VIP석 가격(원)')).not.toHaveAttribute('aria-invalid')
  })

  it('요청이 끝나지 않은 동안에는 Esc로 닫히지 않고 닫기는 비활성이며, 끝나면 닫힌다', async () => {
    const user = await openConcert()
    let release: () => void = () => undefined
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    const started = requestStarted('/api/v1/admin/schedules/31/seats')
    mockServer.use(
      http.post('/api/v1/admin/schedules/:scheduleId/seats', async () => {
        await held
        return HttpResponse.json(
          { status: 409, code: 'SCHEDULE_SEATS_ALREADY_CREATED', message: '이미', traceId: '0' },
          { status: 409 },
        )
      }),
    )

    await user.click(within(scheduleRow('회차 #31')).getByRole('button', { name: '좌석 만들기' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('VIP석 가격(원)'), '165000')
    await user.click(within(dialog).getByRole('button', { name: '좌석 만들기' }))
    await started

    await user.keyboard('{Escape}')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: '닫기' })).toBeDisabled()

    release()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('Esc로 닫으면 초점이 그 회차의 좌석 만들기 버튼으로 돌아온다', async () => {
    const user = await openConcert()
    const button = within(scheduleRow('회차 #32')).getByRole('button', { name: '좌석 만들기' })

    await user.click(button)
    await screen.findByRole('dialog')
    await user.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(button).toHaveFocus())
  })

  it('좌석을 만들어 닫으면 초점이 좌석 수 글자로 간다', async () => {
    const user = await openConcert()
    const dialog = await openSeatDialogOfNewSchedule(user)

    await user.type(within(dialog).getByLabelText('VIP석 가격(원)'), '165000')
    await user.type(within(dialog).getByLabelText('R석 가격(원)'), '132000')
    await user.type(within(dialog).getByLabelText('S석 가격(원)'), '99000')
    await user.click(within(dialog).getByRole('button', { name: '좌석 만들기' }))

    const done = await within(scheduleRow('2026.12.20 (일) 19:00')).findByText('좌석 60석 만듦')
    await waitFor(() => expect(done).toHaveFocus())
  })
})

describe('없는 공연', () => {
  it.each(['/admin/concerts/999999', '/admin/concerts/abc'])(
    '%s 는 찾는 공연이 없다고 안내한다',
    async (path) => {
      await signInAsAdmin()
      renderRoutes(routes, path)

      expect(await screen.findByText(/찾는 공연이 없어요/)).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: '회차 등록' })).not.toBeInTheDocument()
    },
  )
})

describe('내 정보의 운영 화면 링크', () => {
  it('운영자에게만 보인다', async () => {
    await signInAsAdmin()
    renderRoutes(routes, '/me')

    const link = await screen.findByRole('link', { name: '운영 화면' })
    expect(link).toHaveAttribute('href', '/admin')
  })

  it('일반 사용자에게는 보이지 않는다', async () => {
    await signInAsDemo()
    renderRoutes(routes, '/me')

    await screen.findByText('일반 회원')
    expect(screen.queryByRole('link', { name: '운영 화면' })).not.toBeInTheDocument()
  })
})
