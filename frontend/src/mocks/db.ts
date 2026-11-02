// 목 API가 쓰는 메모리 상태. 테스트마다 resetMockDb()로 처음 상태로 돌린다.
// 브라우저 목 모드에서는 새로고침하면 처음 상태로 돌아간다.

export const DEMO_USER = { email: 'demo@iseonjwa.dev', password: 'password1234' } as const

const MINUTE = 60 * 1000
const DAY = 24 * 60 * MINUTE

// 숫자 기준은 서버 설정·SA 9-4와 같다
export const ACCESS_TOKEN_SECONDS = 1800
export const ENTRY_TOKEN_MILLIS = 10 * MINUTE
export const HOLD_MILLIS = 5 * MINUTE
// 결제 토큰은 선점 만료 10초 전에 끝난다(SA 7-3)
export const PAYMENT_TOKEN_MARGIN_MILLIS = 10 * 1000

export interface MockUser {
  userId: number
  email: string
  password: string
  nickname: string
  role: 'USER' | 'ADMIN'
}

export interface MockSchedule {
  scheduleId: number
  concertId: number
  venueName: string
  startsAtMillis: number
  openAtMillis: number
  maxPerUser: number
}

export interface MockSeat {
  seatId: number
  scheduleId: number
  section: string
  grade: string
  price: number
  row: number
  number: number
  available: boolean
}

export interface MockReservation {
  reservationId: number
  reservationNo: string
  requestId: string
  userId: number
  scheduleId: number
  seatIds: number[]
  status: 'HELD' | 'CONFIRMED' | 'EXPIRED' | 'RELEASED'
  holdExpiresAtMillis: number
  totalAmount: number
  // 결제 승인 뒤 확정 반영을 기다리는 동안만 값이 있다. 예매 조회 몇 번 뒤 CONFIRMED가 되는지
  confirmAfterChecks: number | null
}

export interface MockPayment {
  paymentId: number
  reservationId: number
  userId: number
  status: 'UNKNOWN' | 'APPROVED' | 'DECLINED'
  amount: number
  approvedAtMillis: number | null
  reasonCode: string | null
  // UNKNOWN이 결제 조회 몇 번 뒤 APPROVED가 되는지
  checksUntilResolved: number
}

// 결제 요청이 처음 만든 결제의 응답. 같은 키 재요청에 그대로 돌려준다(멱등 기록의 response_body)
export interface StoredPaymentResponse {
  status: number
  data: Record<string, unknown>
  headers?: Record<string, string>
}

interface MockState {
  nextId: number
  users: MockUser[]
  concerts: { concertId: number; title: string; description: string }[]
  schedules: MockSchedule[]
  seats: MockSeat[]
  // 접근 토큰 → 사용자 ID. 지우면 Gateway가 만료로 보고 401을 준다
  accessTokens: Map<string, number>
  // 사용자 ID → 현재 Refresh 토큰. 서버처럼 사용자당 하나이고 재발급마다 바뀐다
  refreshTokens: Map<number, string>
  // `${scheduleId}:${userId}` → 남은 순번 조회 횟수
  queueEntries: Map<string, { pollsUntilReady: number; issuedEntryToken: string | null }>
  reservations: Map<number, MockReservation>
  // Idempotency-Key → 선점 요청
  holdRequests: Map<
    string,
    { userId: number; scheduleId: number; seatKey: string; reservationId: number }
  >
  payments: Map<number, MockPayment>
  // Idempotency-Key → 결제 요청. 기존 결제에 붙은 별칭 키는 storedResponse가 null이다
  paymentRequests: Map<
    string,
    {
      userId: number
      bodyKey: string
      paymentId: number
      storedResponse: StoredPaymentResponse | null
    }
  >
  seatConflictUsed: boolean
}

export const mockDb: MockState = createInitialState()

// 목 상태와 달리 테스트마다 돌리지 않는 설정
export const mockSettings = {
  // 브라우저 목 모드는 새로고침하면 목 상태가 비어 저장된 Refresh 토큰을 모른다. 그때 로그인이 풀리지 않게
  // 처음 보는 토큰을 받아 준다. 테스트에서는 꺼서 서버처럼 거절한다(로그아웃한 토큰이 되살아나지 않게).
  acceptUnknownRefreshTokens: false,
}

export function resetMockDb() {
  Object.assign(mockDb, createInitialState())
}

export function nextMockId(): number {
  mockDb.nextId += 1
  return mockDb.nextId
}

// 테스트용: 발급한 Access 토큰을 모두 만료시킨다. Refresh 토큰은 그대로라 재발급하면 이어진다.
export function expireMockAccessTokens() {
  mockDb.accessTokens.clear()
}

// 선점 만료 시각이 지난 HELD를 만료 처리하고 좌석을 돌려준다. 요청을 처리하기 전에 부른다.
export function settleExpiredHolds(nowMillis = Date.now()) {
  for (const reservation of mockDb.reservations.values()) {
    const awaitingConfirmation = reservation.confirmAfterChecks !== null
    if (
      reservation.status === 'HELD' &&
      !awaitingConfirmation &&
      reservation.holdExpiresAtMillis <= nowMillis
    ) {
      reservation.status = 'EXPIRED'
      releaseSeats(reservation.seatIds)
    }
  }
}

// 결제가 승인되면 부른다. 실제로는 PaymentApproved 이벤트를 받아 비동기로 확정한다(목표 2초, SA 9-4).
// 승인 직후 예매 조회는 아직 HELD라서 화면은 CONFIRMED가 될 때까지 다시 조회해야 한다(design-system.md 5절 "확정 반영 대기").
export function scheduleConfirmation(reservation: MockReservation) {
  reservation.confirmAfterChecks = 1
}

// 예매를 조회할 때 부른다. 확정 반영을 기다리는 예매는 정해진 횟수만큼 조회된 뒤 CONFIRMED가 된다.
export function advanceConfirmation(reservation: MockReservation) {
  if (reservation.confirmAfterChecks === null) {
    return
  }
  if (reservation.confirmAfterChecks <= 0) {
    reservation.status = 'CONFIRMED'
    reservation.confirmAfterChecks = null
    return
  }
  reservation.confirmAfterChecks -= 1
}

export function releaseSeats(seatIds: number[]) {
  for (const seat of mockDb.seats) {
    if (seatIds.includes(seat.seatId)) {
      seat.available = true
    }
  }
}

// 응답 시각은 `+09:00`이다(SA 11-1). 결제 시각만 Instant라 `Z`로 낸다(PaymentView).
export function toKstIso(millis: number): string {
  return new Date(millis + 9 * 60 * 60 * 1000).toISOString().slice(0, 19) + '+09:00'
}

function createInitialState(nowMillis = Date.now()): MockState {
  return {
    nextId: 1000,
    users: [
      { userId: 42, ...DEMO_USER, nickname: '데모', role: 'USER' },
      {
        userId: 1,
        email: 'admin@iseonjwa.dev',
        password: 'password1234',
        nickname: '운영자',
        role: 'ADMIN',
      },
    ],
    // 공연·장소 이름은 지어낸 것이다(디자인 샘플과 같다)
    concerts: [
      {
        concertId: 7,
        title: '노을섬 단독 콘서트 〈새벽의 좌석〉',
        description: '목 데이터로 만든 공연이다.',
      },
    ],
    schedules: [
      {
        scheduleId: 31,
        concertId: 7,
        venueName: '한강 아트홀',
        startsAtMillis: nowMillis + 18 * DAY,
        openAtMillis: nowMillis - MINUTE,
        maxPerUser: 4,
      },
      {
        scheduleId: 32,
        concertId: 7,
        venueName: '한강 아트홀',
        startsAtMillis: nowMillis + 19 * DAY,
        openAtMillis: nowMillis + 10 * MINUTE,
        maxPerUser: 4,
      },
    ],
    seats: [
      ...createSeats({
        scheduleId: 31,
        firstSeatId: 1001,
        section: 'A',
        grade: 'VIP',
        price: 165000,
        rows: 2,
        perRow: 8,
      }),
      ...createSeats({
        scheduleId: 31,
        firstSeatId: 1101,
        section: 'B',
        grade: 'R',
        price: 132000,
        rows: 3,
        perRow: 10,
      }),
    ],
    accessTokens: new Map(),
    refreshTokens: new Map(),
    queueEntries: new Map(),
    reservations: new Map(),
    holdRequests: new Map(),
    payments: new Map(),
    paymentRequests: new Map(),
    seatConflictUsed: false,
  }
}

function createSeats(spec: {
  scheduleId: number
  firstSeatId: number
  section: string
  grade: string
  price: number
  rows: number
  perRow: number
}): MockSeat[] {
  const seats: MockSeat[] = []
  for (let row = 1; row <= spec.rows; row++) {
    for (let number = 1; number <= spec.perRow; number++) {
      seats.push({
        seatId: spec.firstSeatId + (row - 1) * spec.perRow + (number - 1),
        scheduleId: spec.scheduleId,
        section: spec.section,
        grade: spec.grade,
        price: spec.price,
        row,
        number,
        available: true,
      })
    }
  }
  return seats
}
