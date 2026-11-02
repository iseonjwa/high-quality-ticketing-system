import { http } from 'msw'
import { mockDb, toKstIso } from '@/mocks/db'
import { saleStatusOf } from '@/mocks/handlers/concerts'
import {
  createEntryToken,
  entryTokenExpiresAt,
  fail,
  ok,
  requireSchedule,
  requireUser,
} from '@/mocks/respond'
import { mockScenario } from '@/mocks/scenario'

// 대기열(SA 11-5, ADR 0031). 순번은 줄어드는 흉내만 낸다. 실제 계산(seq - admitted)은 서버 몫이다.

const WAITING_POLLS = 2
const POSITION_PER_POLL = 120
// 실제 서버는 순번에 따라 간격을 정하고 jitter를 섞는다. 목은 화면 확인을 빠르게 하려고 1초로 둔다
const MOCK_POLL_MILLIS = 1000

type QueueEntry = { pollsUntilReady: number; issuedEntryToken: string | null }

function entryKey(scheduleId: number, userId: number) {
  return `${scheduleId}:${userId}`
}

// 발급한 입장 토큰이 만료됐으면 줄에서 뺀다(서버의 issued 만료와 같다)
function currentEntry(scheduleId: number, userId: number): QueueEntry | undefined {
  const key = entryKey(scheduleId, userId)
  const entry = mockDb.queueEntries.get(key)
  if (entry?.issuedEntryToken && entryTokenExpiresAt(entry.issuedEntryToken) <= Date.now()) {
    mockDb.queueEntries.delete(key)
    return undefined
  }
  return entry
}

function positionOf(entry: QueueEntry) {
  if (entry.pollsUntilReady <= 0) {
    return { status: 'READY' }
  }
  const position = entry.pollsUntilReady * POSITION_PER_POLL
  return {
    status: 'WAITING',
    position,
    estimatedWaitSeconds: Math.ceil(position / 50),
    nextPollAfterMillis: MOCK_POLL_MILLIS,
  }
}

export const queueHandlers = [
  http.post('/api/v1/schedules/:scheduleId/queue', ({ request, params }) => {
    const scheduleId = Number(params.scheduleId)
    const user = requireUser(request)
    const schedule = requireSchedule(scheduleId)
    if (saleStatusOf(schedule) !== 'ON_SALE') {
      return fail(409, 'SALE_NOT_OPEN', '아직 예매가 시작되지 않았습니다')
    }

    const existing = currentEntry(scheduleId, user.userId)
    if (existing !== undefined) {
      return ok(positionOf(existing), { message: '이미 대기열에 진입했습니다' })
    }
    const entry: QueueEntry = {
      pollsUntilReady: mockScenario().queue === 'ready' ? 0 : WAITING_POLLS,
      issuedEntryToken: null,
    }
    mockDb.queueEntries.set(entryKey(scheduleId, user.userId), entry)
    return ok(positionOf(entry), {
      status: 201,
      message: '대기열에 등록되었습니다',
      headers: { Location: `/api/v1/schedules/${scheduleId}/queue/me` },
    })
  }),

  http.get('/api/v1/schedules/:scheduleId/queue/me', ({ request, params }) => {
    const scheduleId = Number(params.scheduleId)
    const user = requireUser(request)
    const entry = currentEntry(scheduleId, user.userId)
    if (entry === undefined) {
      return fail(404, 'QUEUE_NOT_JOINED', '대기열에 없습니다. 다시 진입해 주세요')
    }
    entry.pollsUntilReady -= 1
    return ok(positionOf(entry), { message: '대기 상태를 조회했습니다' })
  }),

  http.post('/api/v1/schedules/:scheduleId/queue/token', ({ request, params }) => {
    const scheduleId = Number(params.scheduleId)
    const user = requireUser(request)
    const entry = currentEntry(scheduleId, user.userId)
    if (entry === undefined) {
      return fail(404, 'QUEUE_NOT_JOINED', '대기열에 없습니다. 다시 진입해 주세요')
    }
    if (entry.pollsUntilReady > 0) {
      return fail(409, 'QUEUE_NOT_READY', '아직 입장 차례가 아닙니다')
    }

    // 재발급은 같은 토큰을 준다(ADR 0031 결정 9)
    entry.issuedEntryToken ??= createEntryToken(scheduleId, user.userId, Date.now())
    return ok({
      status: 'ADMITTED',
      entryToken: entry.issuedEntryToken,
      entryTokenExpiresAt: toKstIso(entryTokenExpiresAt(entry.issuedEntryToken)),
    })
  }),
]
