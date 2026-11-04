import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { readLotteryIdOfSchedule, saveLotteryOfSchedule } from '@/admin/adminLotteryRecords'
import { authQueryKeys } from '@/api/auth'
import { createLottery } from '@/api/adminLotteries'
import { routes } from '@/app/routes'
import { formatDateTime } from '@/lib/format'
import { mockDb } from '@/mocks/db'
import { fail } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { recordRequests, signInAsAdmin, signInAsDemo } from '@/test/api'
import {
  DAY,
  HOUR,
  iso,
  kstLocal,
  kstTimeAfterDays,
  lotteryInput,
  MINUTE,
  seedScheduleForLottery,
} from '@/test/adminLotteries'
import { renderRoutes } from '@/test/render'

const CONCERT_ID = 7
const NOT_ADMIN_NOTICE = '운영자만 볼 수 있는 화면이에요'

// 전역 setup은 sessionStorage만 비운다
afterEach(() => {
  localStorage.clear()
  vi.useRealTimers()
})

const newPath = (scheduleId: number) =>
  `/admin/concerts/${CONCERT_ID}/schedules/${scheduleId}/lottery`

// datetime-local 입력은 jsdom에서 user.type이 안정적이지 않아 값을 바로 넣는다
function setValue(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

const UNKNOWN_OUTCOME = () => fail(504, 'INTERNAL_SERVER_ERROR', '응답 시간이 초과됐습니다')

function mockStatus<T extends 'post' | 'patch'>(method: T, path: string, response: () => Response) {
  mockServer.use(http[method](path, response))
}

// 그 추첨의 공개 조회 응답을 붙잡아, 기록을 확인하는 동안의 화면을 본다
function holdInfo(lotteryId: number) {
  let release!: () => void
  const closed = new Promise<void>((resolve) => {
    release = resolve
  })
  mockServer.use(
    http.get(`/api/v1/lotteries/${lotteryId}`, async () => {
      await closed
      return undefined
    }),
  )
  return release
}

describe('운영자 경로 보호', () => {
  it.each(['/admin/users/password-reset', '/admin/lotteries/2', newPath(32)])(
    '일반 사용자가 %s 를 열면 안내만 보고 운영 요청은 가지 않는다',
    async (path) => {
      await signInAsDemo()
      const requests = recordRequests('/api/v1/admin/users/42/password-reset')
      const { router } = renderRoutes(routes, path)

      expect(await screen.findByText(NOT_ADMIN_NOTICE)).toBeInTheDocument()
      expect(router.state.location.pathname).toBe(path)
      expect(screen.queryByRole('button', { name: '추첨 설정' })).not.toBeInTheDocument()
      expect(requests).toHaveLength(0)
    },
  )
})

describe('추첨 설정 화면', () => {
  beforeEach(async () => {
    await signInAsAdmin()
  })

  function fillValid(options: { drawAt?: string } = {}) {
    const now = Date.now()
    fireEvent.change(screen.getByLabelText('추첨석으로 뗄 등급'), { target: { value: 'VIP' } })
    setValue('추첨석 수', '4')
    setValue('예비 순번 수', '2')
    setValue('응모 시작', kstLocal(now + DAY))
    setValue('응모 마감', kstLocal(now + 2 * DAY))
    setValue('추첨 시각', options.drawAt ?? kstLocal(kstTimeAfterDays(3, 4)))
  }

  async function openNew(scheduleId = seedScheduleForLottery()) {
    const view = renderRoutes(routes, newPath(scheduleId))
    await screen.findByRole('heading', { level: 1, name: '추첨 설정' })
    return { scheduleId, ...view }
  }

  it('회차 정보(번호, 공연·오픈 시각)와 설정 폼을 보인다', async () => {
    const scheduleId = seedScheduleForLottery()
    const schedule = mockDb.schedules.find((item) => item.scheduleId === scheduleId)!
    await openNew(scheduleId)

    const startsAt = formatDateTime(iso(schedule.startsAtMillis))
    const openAt = formatDateTime(iso(schedule.openAtMillis))
    expect(
      screen.getByText(`회차 #${scheduleId} · ${startsAt} 공연 · ${openAt} 예매 오픈`),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '추첨 설정' })).toBeInTheDocument()
    expect(screen.getByLabelText('한 번에 응모할 수 있는 매수')).toHaveValue('2')
    expect(screen.getByRole('link', { name: /노을섬/ })).toHaveAttribute(
      'href',
      '/admin/concerts/7',
    )
  })

  it('예매 오픈이 지난 회차는 경고만 보이고 폼이 없다', async () => {
    const scheduleId = seedScheduleForLottery({ openAfter: -MINUTE })
    renderRoutes(routes, newPath(scheduleId))

    expect(
      await screen.findByText(
        '예매 오픈이 지난 회차에는 추첨을 설정할 수 없어요. 판매 중인 좌석과 겹치기 때문이에요',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '추첨 설정' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('추첨석 수')).not.toBeInTheDocument()
  })

  it('이 브라우저에 설정 기록이 있고 추첨이 실제로 있으면 안내와 설정 보기 링크만 보이고 폼이 없다', async () => {
    const scheduleId = seedScheduleForLottery()
    const lottery = await createLottery(scheduleId, lotteryInput())
    saveLotteryOfSchedule(scheduleId, lottery.lotteryId)
    renderRoutes(routes, newPath(scheduleId))

    expect(
      await screen.findByText(
        new RegExp(`이 회차에는 이 브라우저에서 설정한 추첨 #${lottery.lotteryId}이 있어요`),
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '설정 보기' })).toHaveAttribute(
      'href',
      `/admin/lotteries/${lottery.lotteryId}`,
    )
    expect(screen.queryByLabelText('추첨석 수')).not.toBeInTheDocument()
  })

  it('기록을 확인하는 동안에는 스켈레톤을 보이고, 기록이 가리키는 추첨이 없으면 지우고 폼을 보인다', async () => {
    const scheduleId = seedScheduleForLottery()
    saveLotteryOfSchedule(scheduleId, 999_999)
    const release = holdInfo(999_999)
    renderRoutes(routes, newPath(scheduleId))

    expect(await screen.findByText('이 회차의 추첨을 확인하고 있어요')).toBeInTheDocument()
    expect(screen.queryByLabelText('추첨석 수')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '설정 보기' })).not.toBeInTheDocument()

    release()

    expect(await screen.findByLabelText('추첨석 수')).toBeInTheDocument()
    expect(screen.queryByText('이 회차의 추첨을 확인하고 있어요')).not.toBeInTheDocument()
    expect(readLotteryIdOfSchedule(scheduleId)).toBeNull()
  })

  it('기록이 다른 회차의 추첨을 가리키면 지우고 폼을 보인다', async () => {
    const scheduleId = seedScheduleForLottery()
    // 추첨 3번은 회차 31의 것이다
    saveLotteryOfSchedule(scheduleId, 3)
    renderRoutes(routes, newPath(scheduleId))

    expect(await screen.findByLabelText('추첨석 수')).toBeInTheDocument()
    expect(readLotteryIdOfSchedule(scheduleId)).toBeNull()
  })

  it('기록을 확인하다 다른 오류가 나면 기록을 믿고 안내한다', async () => {
    const scheduleId = seedScheduleForLottery()
    saveLotteryOfSchedule(scheduleId, 77)
    // 4xx는 조회를 다시 보내지 않아 바로 결과가 난다(5xx는 두 번 다시 보낸다)
    mockServer.use(
      http.get('/api/v1/lotteries/77', () => fail(403, 'FORBIDDEN', '접근 권한이 없습니다')),
    )
    renderRoutes(routes, newPath(scheduleId))

    expect(await screen.findByText(/설정한 추첨 #77이 있어요/)).toBeInTheDocument()
    expect(readLotteryIdOfSchedule(scheduleId)).toBe(77)
  })

  it('칸을 비운 채 설정하면 칸마다 안내하고 요청을 보내지 않는다', async () => {
    const scheduleId = seedScheduleForLottery()
    const requests = recordRequests(`/api/v1/admin/schedules/${scheduleId}/lotteries`)
    await openNew(scheduleId)

    await userEvent.click(screen.getByRole('button', { name: '추첨 설정' }))

    expect(await screen.findByText('추첨석으로 뗄 등급을 골라 주세요')).toBeInTheDocument()
    expect(screen.getByText('추첨석 수를 입력해 주세요')).toBeInTheDocument()
    expect(screen.getByText('예비 순번 수를 입력해 주세요')).toBeInTheDocument()
    expect(screen.getByText('응모 시작 시각을 입력해 주세요')).toBeInTheDocument()
    expect(screen.getByText('응모 마감 시각을 입력해 주세요')).toBeInTheDocument()
    expect(screen.getByText('추첨 시각을 입력해 주세요')).toBeInTheDocument()
    expect(requests).toHaveLength(0)
  })

  it('값 사이 규칙을 어기면 칸에 안내한다(응모 시작이 과거, 마감이 시작보다 앞, 추첨이 공연 1시간 전을 넘음)', async () => {
    await openNew()
    const now = Date.now()
    fillValid()

    setValue('응모 시작', kstLocal(now - HOUR))
    setValue('응모 마감', kstLocal(now - 2 * HOUR))
    setValue('추첨 시각', kstLocal(now + 31 * DAY))
    await userEvent.click(screen.getByRole('button', { name: '추첨 설정' }))

    expect(await screen.findByText(/응모 시작은 지금보다 뒤여야 해요/)).toBeInTheDocument()
    expect(screen.getByText('응모 마감은 응모 시작보다 뒤여야 해요')).toBeInTheDocument()
    expect(
      screen.getByText('추첨은 공연 시작 1시간 전까지여야 해요(배정·자동결제 시간)'),
    ).toBeInTheDocument()
  })

  it('추첨 시각이 새벽 3~5시(한국) 밖이면 안내를 띄우고 안이면 지운다', async () => {
    await openNew()
    const notice = /추첨 시각이 새벽 3~5시 밖이에요/

    setValue('추첨 시각', kstLocal(kstTimeAfterDays(3, 12)))
    expect(await screen.findByText(notice)).toBeInTheDocument()

    setValue('추첨 시각', kstLocal(kstTimeAfterDays(3, 3)))
    await waitFor(() => expect(screen.queryByText(notice)).not.toBeInTheDocument())
  })

  it('성공하면 결과(추첨 번호, 응모 화면 주소, 링크)를 보이고 이 브라우저에 기록한다', async () => {
    const { scheduleId } = await openNew()
    const requests = recordRequests(`/api/v1/admin/schedules/${scheduleId}/lotteries`)
    fillValid()

    await userEvent.click(screen.getByRole('button', { name: '추첨 설정' }))

    const created = mockDb.lotteries.find((lottery) => lottery.scheduleId === scheduleId)!
    const title = await screen.findByRole('heading', {
      level: 2,
      name: `추첨 #${created.lotteryId}을 설정했어요`,
    })
    // 폼이 사라져도 초점을 잃지 않게 결과 제목으로 옮긴다
    await waitFor(() => expect(title).toHaveFocus())
    expect(screen.getByText('추첨을 설정했어요')).toBeInTheDocument()
    expect(screen.getByLabelText('응모 화면 주소')).toHaveValue(
      `${window.location.origin}/lotteries/${created.lotteryId}`,
    )
    expect(screen.getByRole('link', { name: /응모 화면 열기/ })).toHaveAttribute(
      'href',
      `/lotteries/${created.lotteryId}`,
    )
    expect(screen.getByRole('link', { name: '설정 고치기' })).toHaveAttribute(
      'href',
      `/admin/lotteries/${created.lotteryId}`,
    )
    expect(screen.getByRole('link', { name: '공연으로 돌아가기' })).toHaveAttribute(
      'href',
      '/admin/concerts/7',
    )
    expect(screen.getByText(/VIP석 4석 · 예비 2명/)).toBeInTheDocument()
    // 권장 시간대 안이라 경고가 없다
    expect(screen.queryByText(/새벽 3~5시 밖이에요/)).not.toBeInTheDocument()
    expect(readLotteryIdOfSchedule(scheduleId)).toBe(created.lotteryId)
    // 본문은 숫자와 한국 오프셋 시각이다
    const body = (await requests[0].json()) as Record<string, unknown>
    expect(body).toMatchObject({
      sourceGrade: 'VIP',
      seatCount: 4,
      waitlistCount: 2,
      maxPerEntry: 2,
    })
    expect(body.entryStartAt).toMatch(/\+09:00$/)
  })

  it('권장 시간대 밖 추첨은 설정되고 결과에 경고가 보인다', async () => {
    await openNew()
    fillValid({ drawAt: kstLocal(kstTimeAfterDays(3, 12)) })

    await userEvent.click(screen.getByRole('button', { name: '추첨 설정' }))

    const warning = await screen.findByText(
      '추첨 시각이 새벽 3~5시 밖이에요. 추첨 직후 자동결제가 몰리므로 일반 예매가 적은 새벽을 권해요',
    )
    expect(warning).toBeInTheDocument()
  })

  it('성공한 뒤 공연 화면의 그 회차 줄에는 "추첨 #N"이 있다', async () => {
    const { scheduleId } = await openNew()
    fillValid()
    await userEvent.click(screen.getByRole('button', { name: '추첨 설정' }))
    await userEvent.click(await screen.findByRole('link', { name: '공연으로 돌아가기' }))

    const row = (await screen.findByText(`회차 #${scheduleId}`)).closest('li') as HTMLElement
    const created = mockDb.lotteries.find((lottery) => lottery.scheduleId === scheduleId)!
    const link = within(row).getByRole('link', { name: `추첨 #${created.lotteryId}` })
    expect(link).toHaveAttribute('href', `/admin/lotteries/${created.lotteryId}`)
    expect(within(row).queryByRole('link', { name: '추첨 설정' })).not.toBeInTheDocument()
  })

  describe('오류', () => {
    it('이미 추첨이 있는 회차(기록 없음)면 번호를 넣을 곳을 알리고 운영으로 가는 링크를 준다', async () => {
      const { scheduleId } = await openNew()
      fillValid()
      await createLottery(scheduleId, lotteryInput())

      await userEvent.click(screen.getByRole('button', { name: '추첨 설정' }))

      expect(await screen.findByText('이 회차에는 이미 추첨이 있어요')).toBeInTheDocument()
      expect(
        screen.getByText(/설정할 때 받은 번호를 운영 첫 화면의 "추첨 번호로 열기"에 넣어 주세요/),
      ).toBeInTheDocument()
      expect(screen.getByRole('link', { name: '운영으로' })).toHaveAttribute('href', '/admin')
      expect(readLotteryIdOfSchedule(scheduleId)).toBeNull()
    })

    it('화면을 보는 사이 오픈이 지나 서버가 설정 마감이라고 하면 안내한다', async () => {
      const { scheduleId } = await openNew()
      fillValid()
      mockDb.schedules.find((schedule) => schedule.scheduleId === scheduleId)!.openAtMillis =
        Date.now() - MINUTE

      await userEvent.click(screen.getByRole('button', { name: '추첨 설정' }))

      expect(
        await screen.findByText('예매 오픈이나 응모 시작이 지나 추첨 설정을 바꿀 수 없어요'),
      ).toBeInTheDocument()
      expect(readLotteryIdOfSchedule(scheduleId)).toBeNull()
    })

    it('좌석이 모자라면 추첨석 수 칸에 안내한다', async () => {
      const { scheduleId } = await openNew()
      fillValid()
      setValue('추첨석 수', '500')

      await userEvent.click(screen.getByRole('button', { name: '추첨 설정' }))

      expect(
        await screen.findByText(
          'VIP석이 모자라요. 좌석을 만들었는지, 추첨석 수를 줄일 수 있는지 확인해 주세요',
        ),
      ).toBeInTheDocument()
      expect(screen.getByLabelText('추첨석 수')).toHaveFocus()
      expect(readLotteryIdOfSchedule(scheduleId)).toBeNull()
    })

    it('좌석을 만들지 않은 회차도 추첨석 수 칸에 같은 안내를 한다', async () => {
      await openNew(seedScheduleForLottery({ seats: false }))
      fillValid()

      await userEvent.click(screen.getByRole('button', { name: '추첨 설정' }))

      expect(await screen.findByText(/VIP석이 모자라요/)).toBeInTheDocument()
    })

    it('서버가 칸별 오류를 주면 그 칸에 안내하고 초점을 옮긴다', async () => {
      const { scheduleId } = await openNew()
      fillValid()
      mockStatus('post', `/api/v1/admin/schedules/${scheduleId}/lotteries`, () =>
        fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
          errors: [{ field: 'waitlistCount', reason: '0 이상이어야 합니다' }],
        }),
      )

      await userEvent.click(screen.getByRole('button', { name: '추첨 설정' }))

      await waitFor(() => expect(screen.getByLabelText('예비 순번 수')).toHaveFocus())
      expect(screen.getByLabelText('예비 순번 수')).toHaveAttribute('aria-invalid', 'true')
    })

    it('칸별 오류 없는 400이면 값 사이 규칙을 다시 알려 준다', async () => {
      const { scheduleId } = await openNew()
      fillValid()
      mockStatus('post', `/api/v1/admin/schedules/${scheduleId}/lotteries`, () =>
        fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다'),
      )

      await userEvent.click(screen.getByRole('button', { name: '추첨 설정' }))

      expect(await screen.findByText(/입력값 사이 규칙이 맞지 않아요/)).toBeInTheDocument()
    })

    it('결과를 모르면 확인 안내를 보이고 폼을 남기며 기록하지 않는다', async () => {
      const { scheduleId } = await openNew()
      fillValid()
      mockStatus('post', `/api/v1/admin/schedules/${scheduleId}/lotteries`, UNKNOWN_OUTCOME)

      await userEvent.click(screen.getByRole('button', { name: '추첨 설정' }))

      expect(await screen.findByText(/설정됐는지 확인하지 못했어요/)).toBeInTheDocument()
      expect(
        screen.getByText(/다시 설정했을 때 이미 있다고 나오면 설정된 거예요/),
      ).toBeInTheDocument()
      expect(
        screen.getByText(/운영자용 추첨 조회가 없어 이 화면에서는 추첨 번호를 알 수 없어요/),
      ).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '추첨 설정' })).toBeInTheDocument()
      expect(screen.getByLabelText('추첨석 수')).toHaveValue('4')
      expect(readLotteryIdOfSchedule(scheduleId)).toBeNull()
    })

    it('없는 회차는 찾는 회차가 없다고 알린다', async () => {
      renderRoutes(routes, newPath(999_999))

      expect(await screen.findByText(/찾는 회차가 없어요/)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: '운영으로' })).toHaveAttribute('href', '/admin')
    })

    it('없는 공연도 같은 안내다', async () => {
      renderRoutes(routes, '/admin/concerts/999999/schedules/31/lottery')

      expect(await screen.findByText(/찾는 회차가 없어요/)).toBeInTheDocument()
    })
  })
})

describe('추첨 고치기 화면', () => {
  beforeEach(async () => {
    await signInAsAdmin()
  })

  // 응모 시작 전인 시드 추첨 2번(회차 41, 좌석 없음)
  async function openSeed() {
    const requests = recordRequests('/api/v1/admin/lotteries/2')
    const view = renderRoutes(routes, '/admin/lotteries/2')
    await screen.findByRole('heading', { level: 1, name: '추첨 #2' })
    return { requests, ...view }
  }

  // 좌석이 있는 회차에서 운영자가 설정한 추첨
  async function openCreated() {
    const scheduleId = seedScheduleForLottery()
    const lottery = await createLottery(
      scheduleId,
      lotteryInput({ seatCount: 5, waitlistCount: 2 }),
    )
    const requests = recordRequests(`/api/v1/admin/lotteries/${lottery.lotteryId}`)
    renderRoutes(routes, `/admin/lotteries/${lottery.lotteryId}`)
    await screen.findByRole('heading', { level: 1, name: `추첨 #${lottery.lotteryId}` })
    return { scheduleId, lottery, requests }
  }

  const patchesOf = (requests: Request[]) =>
    requests.filter((request) => request.method === 'PATCH')

  it('공개 조회 값(한국 시각)으로 채우고 등급과 예비 순번 수는 비워 둔다', async () => {
    await openSeed()
    const lottery = mockDb.lotteries.find((item) => item.lotteryId === 2)!

    expect(screen.getByText('회차 #41 · 설정됨')).toBeInTheDocument()
    expect(screen.getByLabelText('추첨석 수')).toHaveValue('10')
    expect(screen.getByLabelText('한 번에 응모할 수 있는 매수')).toHaveValue('1')
    expect(screen.getByLabelText('응모 시작')).toHaveValue(kstLocal(lottery.entryStartAtMillis))
    expect(screen.getByLabelText('응모 마감')).toHaveValue(kstLocal(lottery.entryEndAtMillis))
    expect(screen.getByLabelText('추첨 시각')).toHaveValue(kstLocal(lottery.drawAtMillis))
    expect(screen.getByLabelText('추첨석으로 뗄 등급')).toHaveValue('')
    expect(screen.getByRole('option', { name: '바꾸지 않음' })).toBeInTheDocument()
    expect(screen.getByLabelText('예비 순번 수')).toHaveValue('')
    expect(screen.getByLabelText('응모 화면 주소')).toHaveValue(
      `${window.location.origin}/lotteries/2`,
    )
    expect(screen.getByRole('link', { name: /응모 화면 열기/ })).toHaveAttribute(
      'href',
      '/lotteries/2',
    )
  })

  it.each([
    ['응모가 시작된 추첨(1번)', 1],
    ['추첨 완료 상태(3번)', 3],
  ])('%s은 폼 없이 값만 읽기 전용으로 보인다', async (_name, lotteryId) => {
    renderRoutes(routes, `/admin/lotteries/${lotteryId}`)

    expect(await screen.findByText(/응모가 시작돼 설정을 바꿀 수 없어요/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '저장' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('추첨석 수')).not.toBeInTheDocument()
    expect(screen.getByText('10석')).toBeInTheDocument()
    expect(screen.getByLabelText('응모 화면 주소')).toBeInTheDocument()
  })

  it('상태가 OPEN이 아니면 응모 시작 전이어도 읽기 전용이다', async () => {
    mockDb.lotteries.find((item) => item.lotteryId === 2)!.status = 'CLOSED'
    renderRoutes(routes, '/admin/lotteries/2')

    expect(await screen.findByText(/응모가 시작돼 설정을 바꿀 수 없어요/)).toBeInTheDocument()
    expect(screen.getByText('회차 #41 · 응모 마감')).toBeInTheDocument()
  })

  it('가짜 시계로 응모 시작 시각을 넘기면 폼이 읽기 전용으로 바뀐다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const lottery = mockDb.lotteries.find((item) => item.lotteryId === 2)!
    lottery.entryStartAtMillis = Date.now() + 15_000
    renderRoutes(routes, '/admin/lotteries/2')
    await screen.findByRole('button', { name: '저장' })

    await act(() => vi.advanceTimersByTimeAsync(20_000))

    expect(await screen.findByText(/응모가 시작돼 설정을 바꿀 수 없어요/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '저장' })).not.toBeInTheDocument()
  })

  it('바꾼 칸이 없으면 요청 없이 안내한다', async () => {
    const { requests } = await openSeed()

    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('바꾼 칸이 없어요')).toBeInTheDocument()
    expect(patchesOf(requests)).toHaveLength(0)
  })

  it('추첨석 수만 바꾸면 PATCH 본문은 { seatCount }뿐이다', async () => {
    const { requests } = await openSeed()

    setValue('추첨석 수', '8')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('추첨 설정을 고쳤어요')).toBeInTheDocument()
    const patches = patchesOf(requests)
    expect(patches).toHaveLength(1)
    expect(await patches[0].json()).toEqual({ seatCount: 8 })
    expect(mockDb.lotteries.find((item) => item.lotteryId === 2)?.seatCount).toBe(8)
  })

  it('고친 값을 도로 돌려놓으면 바꾼 칸이 없는 것으로 본다', async () => {
    const { requests } = await openSeed()

    setValue('추첨석 수', '8')
    setValue('추첨석 수', '10')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('바꾼 칸이 없어요')).toBeInTheDocument()
    expect(patchesOf(requests)).toHaveLength(0)
  })

  it('저장하면 응답 값으로 다시 채우고(등급·예비 순번 수 포함) 바꾼 칸 표시를 지운다', async () => {
    const { lottery, requests } = await openCreated()

    expect(screen.getByLabelText('추첨석으로 뗄 등급')).toHaveValue('')
    fireEvent.change(screen.getByLabelText('추첨석으로 뗄 등급'), { target: { value: 'VIP' } })
    setValue('예비 순번 수', '9')
    setValue('추첨석 수', '6')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('추첨 설정을 고쳤어요')).toBeInTheDocument()
    expect(await patchesOf(requests)[0].json()).toEqual({
      sourceGrade: 'VIP',
      waitlistCount: 9,
      seatCount: 6,
    })
    expect(screen.getByLabelText('추첨석으로 뗄 등급')).toHaveValue('VIP')
    expect(screen.getByLabelText('예비 순번 수')).toHaveValue('9')
    expect(screen.getByLabelText('추첨석 수')).toHaveValue('6')
    expect(mockDb.lotteries.find((item) => item.lotteryId === lottery.lotteryId)).toMatchObject({
      sourceGrade: 'VIP',
      waitlistCount: 9,
      seatCount: 6,
    })

    // 저장한 값이 새 기준이라 다시 저장하면 바꾼 칸이 없다
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByText('바꾼 칸이 없어요')).toBeInTheDocument()
    expect(patchesOf(requests)).toHaveLength(1)
  })

  it('등급을 비운 채 다른 칸만 바꾸면 등급은 보내지 않는다', async () => {
    const { requests } = await openCreated()

    setValue('예비 순번 수', '')
    setValue('추첨석 수', '4')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    await screen.findByText('추첨 설정을 고쳤어요')
    expect(await patchesOf(requests)[0].json()).toEqual({ seatCount: 4 })
  })

  it('저장 응답에 경고가 있으면 보인다(권장 시간대 밖 추첨 시각)', async () => {
    await openCreated()

    setValue('추첨 시각', kstLocal(kstTimeAfterDays(5, 12)))
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    // 칸 아래 안내와 별개로, 서버가 준 경고가 폼 위에 보인다
    expect(
      await screen.findByText(
        '추첨 시각이 새벽 3~5시 밖이에요. 추첨 직후 자동결제가 몰리므로 일반 예매가 적은 새벽을 권해요',
      ),
    ).toBeInTheDocument()
  })

  it('칸 검사에 걸리면 요청을 보내지 않는다', async () => {
    const { requests } = await openSeed()

    setValue('추첨석 수', '0')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('추첨석 수는 1 이상으로 입력해 주세요')).toBeInTheDocument()
    expect(patchesOf(requests)).toHaveLength(0)
  })

  describe('오류', () => {
    it('늘릴 좌석이 모자라면 추첨석 수 칸에 안내한다', async () => {
      await openSeed()

      setValue('추첨석 수', '12')
      await userEvent.click(screen.getByRole('button', { name: '저장' }))

      expect(
        await screen.findByText('추첨석으로 뗄 좌석이 모자라요. 추첨석 수나 등급을 확인해 주세요'),
      ).toBeInTheDocument()
      expect(screen.getByLabelText('추첨석 수')).toHaveFocus()
    })

    it('화면을 보는 사이 서버에서 응모가 시작됐으면 설정 마감 안내를 보인다', async () => {
      await openSeed()
      mockDb.lotteries.find((item) => item.lotteryId === 2)!.entryStartAtMillis =
        Date.now() - MINUTE

      setValue('추첨석 수', '8')
      await userEvent.click(screen.getByRole('button', { name: '저장' }))

      expect(
        await screen.findByText('예매 오픈이나 응모 시작이 지나 추첨 설정을 바꿀 수 없어요'),
      ).toBeInTheDocument()
    })

    it('결과를 모르면 같은 값으로 다시 저장해도 된다고 알린다', async () => {
      await openSeed()
      mockStatus('patch', '/api/v1/admin/lotteries/2', UNKNOWN_OUTCOME)

      setValue('추첨석 수', '8')
      await userEvent.click(screen.getByRole('button', { name: '저장' }))

      expect(await screen.findByText(/저장됐는지 확인하지 못했어요/)).toBeInTheDocument()
      expect(screen.getByLabelText('추첨석 수')).toHaveValue('8')
    })

    it('칸별 오류 없는 400이면 값 사이 규칙을 알려 준다', async () => {
      await openSeed()
      mockStatus('patch', '/api/v1/admin/lotteries/2', () =>
        fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다'),
      )

      setValue('추첨석 수', '8')
      await userEvent.click(screen.getByRole('button', { name: '저장' }))

      expect(await screen.findByText(/입력값 사이 규칙이 맞지 않아요/)).toBeInTheDocument()
    })

    it('없는 추첨은 찾는 추첨이 없다고 알린다', async () => {
      renderRoutes(routes, '/admin/lotteries/999999')

      expect(await screen.findByText(/찾는 추첨이 없어요/)).toBeInTheDocument()
      expect(screen.getByRole('link', { name: '운영으로' })).toHaveAttribute('href', '/admin')
    })

    it('숫자가 아닌 추첨 번호도 같은 안내다', async () => {
      renderRoutes(routes, '/admin/lotteries/abc')

      expect(await screen.findByText(/찾는 추첨이 없어요/)).toBeInTheDocument()
    })
  })
})

describe('추첨 고치기 화면: 기록과 시각 비교', () => {
  beforeEach(async () => {
    await signInAsAdmin()
  })

  it('없는 추첨(404)이면 그 번호를 가리키는 기록을 모두 지운다', async () => {
    saveLotteryOfSchedule(32, 999_999)
    saveLotteryOfSchedule(41, 999_999)
    saveLotteryOfSchedule(31, 3)
    renderRoutes(routes, '/admin/lotteries/999999')

    expect(await screen.findByText(/찾는 추첨이 없어요/)).toBeInTheDocument()
    await waitFor(() => expect(readLotteryIdOfSchedule(32)).toBeNull())
    expect(readLotteryIdOfSchedule(41)).toBeNull()
    // 다른 추첨의 기록은 그대로다
    expect(readLotteryIdOfSchedule(31)).toBe(3)
  })

  // 추첨 2번의 마감을 초가 있는 값(분 + 27초)으로, 추첨을 30분 뒤로 둔다
  function seedSecondsLottery() {
    const lottery = mockDb.lotteries.find((item) => item.lotteryId === 2)!
    const base = Math.floor((Date.now() + 2 * DAY) / MINUTE) * MINUTE
    lottery.entryStartAtMillis = base - DAY
    lottery.entryEndAtMillis = base + 27_000
    lottery.drawAtMillis = base + 30 * MINUTE
    return base
  }

  it('저장된 마감이 초를 가진 값일 때 추첨을 같은 분으로 고치면 칸 오류이고 요청을 보내지 않는다', async () => {
    const base = seedSecondsLottery()
    const requests = recordRequests('/api/v1/admin/lotteries/2')
    renderRoutes(routes, '/admin/lotteries/2')
    await screen.findByRole('heading', { level: 1, name: '추첨 #2' })
    // 칸에는 분까지만 보인다
    expect(screen.getByLabelText('응모 마감')).toHaveValue(kstLocal(base))

    setValue('추첨 시각', kstLocal(base))
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('추첨은 응모 마감과 같거나 뒤여야 해요')).toBeInTheDocument()
    expect(requests.filter((request) => request.method === 'PATCH')).toHaveLength(0)
  })

  it('바꾸지 않은 칸은 저장된 값 그대로 통과해 다른 칸만 저장된다', async () => {
    seedSecondsLottery()
    const requests = recordRequests('/api/v1/admin/lotteries/2')
    renderRoutes(routes, '/admin/lotteries/2')
    await screen.findByRole('heading', { level: 1, name: '추첨 #2' })

    setValue('추첨석 수', '8')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('추첨 설정을 고쳤어요')).toBeInTheDocument()
    const patches = requests.filter((request) => request.method === 'PATCH')
    expect(await patches[0].json()).toEqual({ seatCount: 8 })
  })

  it('저장한 뒤에도 응답의 초 값으로 비교해 같은 분의 추첨은 계속 막는다', async () => {
    const base = seedSecondsLottery()
    const requests = recordRequests('/api/v1/admin/lotteries/2')
    renderRoutes(routes, '/admin/lotteries/2')
    await screen.findByRole('heading', { level: 1, name: '추첨 #2' })
    setValue('추첨석 수', '8')
    await userEvent.click(screen.getByRole('button', { name: '저장' }))
    await screen.findByText('추첨 설정을 고쳤어요')

    setValue('추첨 시각', kstLocal(base))
    await userEvent.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByText('추첨은 응모 마감과 같거나 뒤여야 해요')).toBeInTheDocument()
    expect(requests.filter((request) => request.method === 'PATCH')).toHaveLength(1)
  })
})

describe('비밀번호 초기화 화면', () => {
  beforeEach(async () => {
    await signInAsAdmin()
  })

  async function openReset() {
    const user = userEvent.setup()
    const view = renderRoutes(routes, '/admin/users/password-reset')
    await screen.findByRole('heading', { level: 1, name: '비밀번호 초기화' })
    return { user, ...view }
  }

  const resetPath = (userId: number) => `/api/v1/admin/users/${userId}/password-reset`

  it('회원 번호가 비었거나 숫자가 아니거나 0이면 안내하고 대화상자를 열지 않는다', async () => {
    const { user } = await openReset()
    const submit = screen.getByRole('button', { name: '임시 비밀번호 만들기' })

    await user.click(submit)
    expect(await screen.findByText('회원 번호를 입력해 주세요')).toBeInTheDocument()

    await user.type(screen.getByLabelText('회원 번호'), 'abc')
    await user.click(submit)
    expect(await screen.findByText('회원 번호는 숫자로 입력해 주세요')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('회원 번호'))
    await user.type(screen.getByLabelText('회원 번호'), '0')
    await user.click(submit)
    expect(await screen.findByText('회원 번호를 확인해 주세요')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('만들기를 누르면 확인 대화상자가 열리고, 취소하면 요청 없이 닫히며 초점이 만들기 버튼으로 돌아온다', async () => {
    const { user } = await openReset()
    const requests = recordRequests(resetPath(42))

    await user.type(screen.getByLabelText('회원 번호'), '42')
    await user.click(screen.getByRole('button', { name: '임시 비밀번호 만들기' }))
    const dialog = await screen.findByRole('dialog', {
      name: '회원 #42의 비밀번호를 초기화할까요?',
    })
    await user.click(within(dialog).getByRole('button', { name: '취소' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(requests).toHaveLength(0)
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '임시 비밀번호 만들기' })).toHaveFocus(),
    )
  })

  it('초기화하면 임시 비밀번호 칸을 보이고 초점을 결과 제목으로 옮기며, 다른 회원 초기화로 되돌아간다', async () => {
    const { user } = await openReset()

    await user.type(screen.getByLabelText('회원 번호'), '42')
    await user.click(screen.getByRole('button', { name: '임시 비밀번호 만들기' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '초기화' }))

    const title = await screen.findByRole('heading', { level: 2, name: '회원 #42의 임시 비밀번호' })
    await waitFor(() => expect(title).toHaveFocus())
    const temporary = mockDb.users.find((candidate) => candidate.userId === 42)!.password
    expect(screen.getByLabelText('임시 비밀번호')).toHaveValue(temporary)
    expect(temporary).toHaveLength(16)
    expect(screen.getByText(/이 화면을 떠나면 다시 볼 수 없어요/)).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '다른 회원 초기화' }))

    expect(await screen.findByLabelText('회원 번호')).toHaveValue('')
    expect(screen.queryByLabelText('임시 비밀번호')).not.toBeInTheDocument()
  })

  it('임시 비밀번호는 sessionStorage·localStorage에 남지 않는다', async () => {
    const { user } = await openReset()

    await user.type(screen.getByLabelText('회원 번호'), '42')
    await user.click(screen.getByRole('button', { name: '임시 비밀번호 만들기' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: '초기화' }),
    )
    await screen.findByLabelText('임시 비밀번호')

    const temporary = mockDb.users.find((candidate) => candidate.userId === 42)!.password
    for (const storage of [sessionStorage, localStorage]) {
      for (let index = 0; index < storage.length; index++) {
        const value = storage.getItem(storage.key(index) as string) as string
        expect(value).not.toContain(temporary)
      }
    }
  })

  it('없는 회원이면 대화상자를 닫고 회원 번호 칸에 안내하며 초점을 칸으로 옮긴다', async () => {
    const { user } = await openReset()

    await user.type(screen.getByLabelText('회원 번호'), '999999')
    await user.click(screen.getByRole('button', { name: '임시 비밀번호 만들기' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: '초기화' }),
    )

    expect(await screen.findByText('이 번호의 회원이 없어요')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(screen.getByLabelText('회원 번호')).toHaveFocus())
  })

  it('서버 오류는 대화상자 안에 보이고 대화상자는 닫히지 않는다', async () => {
    const { user } = await openReset()
    mockStatus('post', resetPath(42), () =>
      fail(500, 'INTERNAL_SERVER_ERROR', '서버 내부 오류가 발생했습니다'),
    )

    await user.type(screen.getByLabelText('회원 번호'), '42')
    await user.click(screen.getByRole('button', { name: '임시 비밀번호 만들기' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '초기화' }))

    expect(await within(dialog).findByText(/문제가 생겼어요/)).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.queryByLabelText('임시 비밀번호')).not.toBeInTheDocument()
  })

  it('결과를 모르면 대화상자 안에 확인 안내를 보인다', async () => {
    const { user } = await openReset()
    mockStatus('post', resetPath(42), UNKNOWN_OUTCOME)

    await user.type(screen.getByLabelText('회원 번호'), '42')
    await user.click(screen.getByRole('button', { name: '임시 비밀번호 만들기' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '초기화' }))

    expect(await within(dialog).findByText(/초기화됐는지 확인하지 못했어요/)).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('요청 중에는 대화상자를 닫을 수 없다', async () => {
    const { user } = await openReset()
    let release!: () => void
    const closed = new Promise<void>((resolve) => {
      release = resolve
    })
    mockServer.use(
      http.post(resetPath(42), async () => {
        await closed
        return undefined
      }),
    )
    const requests = recordRequests(resetPath(42))

    await user.type(screen.getByLabelText('회원 번호'), '42')
    await user.click(screen.getByRole('button', { name: '임시 비밀번호 만들기' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '초기화' }))
    await waitFor(() => expect(requests).toHaveLength(1))
    await user.keyboard('{Escape}')

    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: '취소' })).toBeDisabled()

    release()
    expect(await screen.findByLabelText('임시 비밀번호')).toBeInTheDocument()
  })
})

describe('비밀번호 초기화 화면: 내 계정과 임시 비밀번호 보관', () => {
  beforeEach(async () => {
    await signInAsAdmin()
  })

  async function openReset() {
    const user = userEvent.setup()
    const view = renderRoutes(routes, '/admin/users/password-reset')
    await screen.findByRole('heading', { level: 1, name: '비밀번호 초기화' })
    // 내 번호를 알아야 막을 수 있어서 내 정보를 받은 뒤에 누른다
    await waitFor(() => expect(view.queryClient.getQueryData(authQueryKeys.me)).toBeDefined())
    return { user, ...view }
  }

  const resetPath = (userId: number) => `/api/v1/admin/users/${userId}/password-reset`
  const cachedPasswords = (queryClient: ReturnType<typeof renderRoutes>['queryClient']) =>
    queryClient
      .getMutationCache()
      .getAll()
      .filter((mutation) =>
        JSON.stringify(mutation.state.data ?? null).includes('temporaryPassword'),
      )

  async function resetMember(user: ReturnType<typeof userEvent.setup>, userId: string) {
    await user.type(screen.getByLabelText('회원 번호'), userId)
    await user.click(screen.getByRole('button', { name: '임시 비밀번호 만들기' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: '초기화' }),
    )
    await screen.findByLabelText('임시 비밀번호')
  }

  it('내 번호(운영자 1)를 넣으면 대화상자 없이 칸 오류이고 요청을 보내지 않는다', async () => {
    const { user } = await openReset()
    const requests = recordRequests(resetPath(1))

    await user.type(screen.getByLabelText('회원 번호'), '1')
    await user.click(screen.getByRole('button', { name: '임시 비밀번호 만들기' }))

    expect(
      await screen.findByText(
        '내 계정이에요. 내 비밀번호는 내 정보의 비밀번호 변경에서 바꿔 주세요',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(requests).toHaveLength(0)
    expect(screen.getByLabelText('회원 번호')).toHaveFocus()
  })

  it('다른 회원 번호는 그대로 대화상자가 열린다', async () => {
    const { user } = await openReset()

    await user.type(screen.getByLabelText('회원 번호'), '42')
    await user.click(screen.getByRole('button', { name: '임시 비밀번호 만들기' }))

    expect(
      await screen.findByRole('dialog', { name: '회원 #42의 비밀번호를 초기화할까요?' }),
    ).toBeInTheDocument()
  })

  it('[다른 회원 초기화]를 누르면 회원 번호 칸으로 초점이 가고 받은 응답이 캐시에 남지 않는다', async () => {
    const { user, queryClient } = await openReset()
    await resetMember(user, '42')
    // 결과를 보는 동안에는 응답이 있다
    expect(cachedPasswords(queryClient)).toHaveLength(1)

    await user.click(screen.getByRole('button', { name: '다른 회원 초기화' }))

    await waitFor(() => expect(screen.getByLabelText('회원 번호')).toHaveFocus())
    expect(screen.getByLabelText('회원 번호')).toHaveValue('')
    expect(cachedPasswords(queryClient)).toHaveLength(0)
  })

  it('화면을 떠나면 받은 응답이 캐시에서 사라진다', async () => {
    const { user, queryClient, router } = await openReset()
    await resetMember(user, '42')
    expect(cachedPasswords(queryClient)).toHaveLength(1)

    await act(() => router.navigate('/admin'))

    await waitFor(() => expect(cachedPasswords(queryClient)).toHaveLength(0))
  })
})

describe('운영 첫 화면의 추첨·비밀번호 초기화 입구', () => {
  beforeEach(async () => {
    await signInAsAdmin()
  })

  it('비밀번호 초기화 링크가 있다', async () => {
    renderRoutes(routes, '/admin')

    const link = await screen.findByRole('link', { name: /비밀번호 초기화/ })
    expect(link).toHaveAttribute('href', '/admin/users/password-reset')
  })

  it('추첨 번호로 열기: 숫자가 아니거나 0이면 오류를 보이고 이동하지 않는다', async () => {
    const user = userEvent.setup()
    const { router } = renderRoutes(routes, '/admin')
    const input = await screen.findByLabelText('추첨 번호로 열기')

    for (const value of ['abc', '0', '1.5']) {
      await user.clear(input)
      await user.type(input, value)
      await user.click(screen.getByRole('button', { name: '열기' }))
      expect(await screen.findByText('추첨 번호를 숫자로 입력해 주세요')).toBeInTheDocument()
    }
    expect(router.state.location.pathname).toBe('/admin')

    // 다시 입력하면 오류가 사라진다
    await user.type(input, '1')
    expect(screen.queryByText('추첨 번호를 숫자로 입력해 주세요')).not.toBeInTheDocument()
  })

  it('추첨 번호로 열기: 번호를 넣으면 그 추첨 설정 화면으로 간다', async () => {
    const user = userEvent.setup()
    const { router } = renderRoutes(routes, '/admin')

    await user.type(await screen.findByLabelText('추첨 번호로 열기'), ' 2 ')
    await user.click(screen.getByRole('button', { name: '열기' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/admin/lotteries/2'))
    expect(await screen.findByRole('heading', { level: 1, name: '추첨 #2' })).toBeInTheDocument()
  })
})

describe('공연 화면 회차 줄의 추첨 버튼', () => {
  beforeEach(async () => {
    await signInAsAdmin()
  })

  const rowOf = async (scheduleId: number) =>
    (await screen.findByText(`회차 #${scheduleId}`)).closest('li') as HTMLElement

  it('"추첨 설정"은 예매 오픈 전 회차에만 있고 설정 화면으로 간다', async () => {
    renderRoutes(routes, `/admin/concerts/${CONCERT_ID}`)

    // 31은 판매 중, 32는 오픈 전이다
    expect(
      within(await rowOf(31)).queryByRole('link', { name: '추첨 설정' }),
    ).not.toBeInTheDocument()
    expect(within(await rowOf(32)).getByRole('link', { name: '추첨 설정' })).toHaveAttribute(
      'href',
      '/admin/concerts/7/schedules/32/lottery',
    )
  })

  it('종료된 회차에도 없다', async () => {
    renderRoutes(routes, '/admin/concerts/9')

    expect(within(await rowOf(51)).queryByRole('link', { name: /추첨/ })).not.toBeInTheDocument()
  })

  it('이 브라우저에 기록이 있고 추첨이 실제로 있으면 판매 중인 회차라도 "추첨 #N"으로 바뀐다', async () => {
    // 추첨 3번은 회차 31의 것이다
    saveLotteryOfSchedule(31, 3)
    renderRoutes(routes, `/admin/concerts/${CONCERT_ID}`)

    const row = await rowOf(31)
    expect(await within(row).findByRole('link', { name: '추첨 #3' })).toHaveAttribute(
      'href',
      '/admin/lotteries/3',
    )
    expect(within(await rowOf(32)).getByRole('link', { name: '추첨 설정' })).toBeInTheDocument()
    expect(readLotteryIdOfSchedule(31)).toBe(3)
  })

  it('기록이 가리키는 추첨이 없으면(404) [추첨 설정]으로 돌아가고 기록을 지운다', async () => {
    saveLotteryOfSchedule(32, 999_999)
    renderRoutes(routes, `/admin/concerts/${CONCERT_ID}`)

    const row = await rowOf(32)
    expect(await within(row).findByRole('link', { name: '추첨 설정' })).toBeInTheDocument()
    expect(within(row).queryByRole('link', { name: /추첨 #/ })).not.toBeInTheDocument()
    await waitFor(() => expect(readLotteryIdOfSchedule(32)).toBeNull())
  })

  it('기록이 다른 회차의 추첨을 가리키면 같다', async () => {
    saveLotteryOfSchedule(32, 3)
    renderRoutes(routes, `/admin/concerts/${CONCERT_ID}`)

    const row = await rowOf(32)
    expect(await within(row).findByRole('link', { name: '추첨 설정' })).toBeInTheDocument()
    await waitFor(() => expect(readLotteryIdOfSchedule(32)).toBeNull())
  })

  it('기록을 확인하는 동안에는 그 줄에 추첨 버튼이 없다', async () => {
    saveLotteryOfSchedule(32, 999_999)
    const release = holdInfo(999_999)
    renderRoutes(routes, `/admin/concerts/${CONCERT_ID}`)

    const row = await rowOf(32)
    expect(within(row).queryByRole('link', { name: /추첨/ })).not.toBeInTheDocument()

    release()
    expect(await within(row).findByRole('link', { name: '추첨 설정' })).toBeInTheDocument()
  })

  it('기록을 확인하다 다른 오류가 나면 기록을 믿고 "추첨 #N"을 보인다', async () => {
    saveLotteryOfSchedule(31, 3)
    mockServer.use(
      http.get('/api/v1/lotteries/3', () => fail(403, 'FORBIDDEN', '접근 권한이 없습니다')),
    )
    renderRoutes(routes, `/admin/concerts/${CONCERT_ID}`)

    expect(
      await within(await rowOf(31)).findByRole('link', { name: '추첨 #3' }),
    ).toBeInTheDocument()
    expect(readLotteryIdOfSchedule(31)).toBe(3)
  })
})

describe('내 정보의 회원 번호', () => {
  it('일반 회원의 번호를 보인다', async () => {
    await signInAsDemo()
    renderRoutes(routes, '/me')

    expect(await screen.findByText('회원 번호')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(
      screen.getByText('운영자에게 비밀번호 초기화를 부탁할 때 알려 주세요'),
    ).toBeInTheDocument()
  })

  it('운영자의 번호도 보인다', async () => {
    await signInAsAdmin()
    renderRoutes(routes, '/me')

    const label = await screen.findByText('회원 번호')
    expect(label.nextElementSibling).toHaveTextContent('1')
  })
})
