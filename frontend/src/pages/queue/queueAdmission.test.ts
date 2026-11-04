import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from '@/api/apiError'
import { readEntryToken, saveEntryToken } from '@/auth/entryTokens'
import { toKstIso } from '@/mocks/db'
import { fail } from '@/mocks/respond'
import { setMockScenario } from '@/mocks/scenario'
import { mockServer } from '@/mocks/server'
import {
  runQueueAdmission,
  type QueueAdmissionState,
  type QueueStep,
} from '@/pages/queue/queueAdmission'
import {
  BEFORE_OPEN_SCHEDULE_ID,
  ON_SALE_SCHEDULE_ID,
  recordRequests,
  signInAsDemo,
} from '@/test/api'

const QUEUE_PATH = `/api/v1/schedules/${ON_SALE_SCHEDULE_ID}/queue`
const POSITION_PATH = `${QUEUE_PATH}/me`
const TOKEN_PATH = `${QUEUE_PATH}/token`

beforeEach(async () => {
  await signInAsDemo()
})

// 기다린 시간을 남기고 바로 끝나는 sleep과 상태 기록을 묶어 흐름을 끝까지 돌린다
async function run(scheduleId = ON_SALE_SCHEDULE_ID) {
  const states: QueueAdmissionState[] = []
  const waits: number[] = []
  await runQueueAdmission({
    scheduleId,
    signal: new AbortController().signal,
    onChange: (state) => states.push(state),
    sleep: (millis) => {
      waits.push(millis)
      return Promise.resolve()
    },
    random: () => 0.5,
  })
  return { states, waits, last: states[states.length - 1] }
}

function stepsOf(states: QueueAdmissionState[]): QueueStep[] {
  return states.map((state) => state.step)
}

describe('대기열 입장 흐름', () => {
  it('진입 → 순번 조회 → READY → 토큰을 받아 저장하고 입장한다', async () => {
    const { states, waits, last } = await run()

    expect(stepsOf(states)).toEqual(['joining', 'waiting', 'waiting', 'admitting', 'admitted'])
    expect(waits).toEqual([1000, 1000])
    expect(states.filter((state) => state.step === 'waiting').map((s) => s.waiting)).toEqual([
      { position: 240, estimatedWaitSeconds: 5, initialPosition: 240 },
      { position: 120, estimatedWaitSeconds: 3, initialPosition: 240 },
    ])
    expect(last.connectionError).toBeNull()
    expect(readEntryToken(ON_SALE_SCHEDULE_ID)).not.toBeNull()
  })

  it('진입 응답이 READY면 순번 조회 없이 토큰을 부른다', async () => {
    setMockScenario({ queue: 'ready' })
    const positionRequests = recordRequests(POSITION_PATH)
    const tokenRequests = recordRequests(TOKEN_PATH)

    const { states, waits } = await run()

    expect(stepsOf(states)).toEqual(['joining', 'admitting', 'admitted'])
    expect(waits).toEqual([])
    expect(positionRequests).toHaveLength(0)
    expect(tokenRequests).toHaveLength(1)
  })

  it('저장한 토큰이 아직 유효하면 요청 없이 입장한다', async () => {
    saveEntryToken(ON_SALE_SCHEDULE_ID, {
      token: 'saved-token',
      expiresAt: toKstIso(Date.now() + 60_000),
    })
    const requests = recordRequests(QUEUE_PATH)

    const { states } = await run()

    expect(stepsOf(states)).toEqual(['admitted'])
    expect(requests).toHaveLength(0)
  })

  it('저장한 토큰이 만료됐으면 대기열부터 다시 한다', async () => {
    saveEntryToken(ON_SALE_SCHEDULE_ID, {
      token: 'old-token',
      expiresAt: toKstIso(Date.now() - 1000),
    })

    const { states } = await run()

    expect(stepsOf(states)[0]).toBe('joining')
    expect(readEntryToken(ON_SALE_SCHEDULE_ID)?.token).not.toBe('old-token')
  })

  it('503이면 Retry-After에 jitter를 더해 기다렸다가 다시 보내고, 성공하면 연결 오류를 지운다', async () => {
    setMockScenario({ queue: 'unavailable' })

    const { states, waits, last } = await run()

    const retrying = states[1]
    expect(retrying.step).toBe('joining')
    expect(retrying.connectionError).toBeInstanceOf(ApiError)
    expect(retrying.connectionError?.status).toBe(503)
    expect(waits[0]).toBe(1100)
    expect(states[2].connectionError).toBeNull()
    expect(last.step).toBe('admitted')
  })

  it('연결에 실패하면 Retry-After 없이 지수로 늘려 가며 다시 보낸다', async () => {
    const failOnce = () => http.post(QUEUE_PATH, () => HttpResponse.error(), { once: true })
    mockServer.use(failOnce(), failOnce())

    const { states, waits, last } = await run()

    expect(waits.slice(0, 2)).toEqual([1100, 2200])
    expect(states[1].connectionError?.status).toBe(0)
    expect(last.step).toBe('admitted')
  })

  it('429도 Retry-After를 따라 다시 보낸다', async () => {
    mockServer.use(
      http.get(
        POSITION_PATH,
        () =>
          fail(429, 'TOO_MANY_REQUESTS', '요청이 너무 많습니다', {
            headers: { 'Retry-After': '2' },
          }),
        { once: true },
      ),
    )

    const { states, waits, last } = await run()

    expect(states.some((state) => state.connectionError?.status === 429)).toBe(true)
    expect(waits).toContain(2200)
    expect(last.step).toBe('admitted')
  })

  it('토큰 요청이 404면 만료로 끝나고 다시 진입하면 입장한다', async () => {
    setMockScenario({ queue: 'expired' })

    const first = await run()
    expect(first.last.step).toBe('expired')
    expect(first.last.connectionError).toBeNull()

    const second = await run()
    expect(second.last.step).toBe('admitted')
  })

  it('오픈 전 회차에 진입하면 409로 notOpen이다', async () => {
    const { states } = await run(BEFORE_OPEN_SCHEDULE_ID)

    expect(stepsOf(states)).toEqual(['joining', 'notOpen'])
  })

  it('토큰 요청이 409 QUEUE_NOT_READY면 3초 뒤 순번 조회로 돌아간다', async () => {
    setMockScenario({ queue: 'ready' })
    mockServer.use(
      http.post(TOKEN_PATH, () => fail(409, 'QUEUE_NOT_READY', '아직 입장 차례가 아닙니다'), {
        once: true,
      }),
    )
    const positionRequests = recordRequests(POSITION_PATH)

    const { states, waits } = await run()

    expect(stepsOf(states)).toEqual(['joining', 'admitting', 'waiting', 'admitting', 'admitted'])
    expect(waits).toEqual([3000])
    expect(positionRequests).toHaveLength(1)
  })

  it('예상하지 못한 오류는 failed와 그 원인으로 끝난다', async () => {
    mockServer.use(http.post(QUEUE_PATH, () => fail(400, 'INVALID_INPUT', '잘못된 요청입니다')))

    const { last } = await run()

    expect(last.step).toBe('failed')
    expect(last.error).toBeInstanceOf(ApiError)
    expect((last.error as ApiError).code).toBe('INVALID_INPUT')
  })

  it('기다리는 중에 취소하면 더는 상태를 알리지 않고 요청도 보내지 않는다', async () => {
    const controller = new AbortController()
    const states: QueueAdmissionState[] = []
    const positionRequests = recordRequests(POSITION_PATH)
    let sleeping!: () => void
    const sleepStarted = new Promise<void>((resolve) => (sleeping = resolve))

    const finished = runQueueAdmission({
      scheduleId: ON_SALE_SCHEDULE_ID,
      signal: controller.signal,
      onChange: (state) => states.push(state),
      sleep: (_millis, signal) =>
        new Promise((resolve) => {
          sleeping()
          signal.addEventListener('abort', () => resolve())
        }),
    })
    await sleepStarted
    const countBeforeAbort = states.length
    controller.abort()
    await finished

    expect(states).toHaveLength(countBeforeAbort)
    expect(positionRequests).toHaveLength(0)
  })

  it('요청 중에 취소해도 failed로 알리지 않는다', async () => {
    const controller = new AbortController()
    const states: QueueAdmissionState[] = []

    const finished = runQueueAdmission({
      scheduleId: ON_SALE_SCHEDULE_ID,
      signal: controller.signal,
      onChange: (state) => states.push(state),
    })
    controller.abort()
    await finished

    expect(stepsOf(states)).not.toContain('failed')
  })
})
