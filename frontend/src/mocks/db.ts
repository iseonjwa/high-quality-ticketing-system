// 목 API가 쓰는 메모리 상태. 테스트마다 resetMockDb()로 처음 상태로 돌린다.
// 브라우저 목 모드에서는 새로고침하면 처음 상태로 돌아간다.

export const DEMO_USER = { email: 'demo@iseonjwa.dev', password: 'password1234' } as const
export const ADMIN_USER = { email: 'admin@iseonjwa.dev', password: 'password1234' } as const
// 운영자가 비밀번호를 초기화한 상태의 계정. 이 비밀번호로 로그인하면 403 PASSWORD_CHANGE_REQUIRED다
export const TEMPORARY_PASSWORD_USER = {
  email: 'temp@iseonjwa.dev',
  password: 'TempPass2345',
} as const

const MINUTE = 60 * 1000
const DAY = 24 * 60 * MINUTE

// 숫자 기준은 서버 설정·SA 9-4와 같다
export const ACCESS_TOKEN_SECONDS = 1800
// config-repo/user-service.yml auth.max-login-failures
export const MAX_LOGIN_FAILURES = 5
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
  failedLoginCount: number
  locked: boolean
  passwordChangeRequired: boolean
}

const NEW_USER_SECURITY = {
  failedLoginCount: 0,
  locked: false,
  passwordChangeRequired: false,
} as const satisfies Partial<MockUser>

export function newMockUser(
  fields: Pick<MockUser, 'userId' | 'email' | 'password' | 'nickname' | 'role'>,
): MockUser {
  return { ...fields, ...NEW_USER_SECURITY }
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

export interface MockVenue {
  venueId: number
  name: string
  // 공연장 좌석의 등급 모음. 회차 좌석을 만들 때 가격표의 등급과 정확히 같아야 한다
  grades: string[]
}

interface MockState {
  nextId: number
  users: MockUser[]
  venues: MockVenue[]
  concerts: { concertId: number; title: string; description: string | null }[]
  schedules: MockSchedule[]
  seats: MockSeat[]
  // 접근 토큰 → 사용자 ID. 지우면 Gateway가 만료로 보고 401을 준다
  accessTokens: Map<string, number>
  // 만료된 Access 토큰과 로그아웃으로 블랙리스트에 오른 Access 토큰. Gateway처럼 401 코드를 나눠 준다
  expiredAccessTokens: Set<string>
  revokedAccessTokens: Set<string>
  // 사용자 ID → 현재 Refresh 토큰. 서버처럼 사용자당 하나이고 재발급마다 바뀐다
  refreshTokens: Map<number, string>
  // 로그아웃·비밀번호 변경으로 폐기한 Refresh 토큰. 브라우저 목 모드가 처음 보는 토큰을 받아 줄 때도 이것은 거절한다
  revokedRefreshTokens: Set<string>
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
  // queue-unavailable이 아직 503을 줘야 하는 남은 횟수
  queueUnavailableLeft: number
  // queue-expired가 이미 줄 기록을 지웠는지
  queueExpiredUsed: boolean
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
  for (const token of mockDb.accessTokens.keys()) {
    mockDb.expiredAccessTokens.add(token)
  }
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
      newMockUser({ userId: 42, ...DEMO_USER, nickname: '데모', role: 'USER' }),
      newMockUser({
        userId: 1,
        ...ADMIN_USER,
        nickname: '운영자',
        role: 'ADMIN',
      }),
      {
        ...newMockUser({ userId: 43, ...TEMPORARY_PASSWORD_USER, nickname: '임시', role: 'USER' }),
        passwordChangeRequired: true,
      },
    ],
    // 로컬 시드 공연장과 같다(등급 VIP·R·S)
    venues: [{ venueId: 1, name: 'KSPO DOME', grades: ['VIP', 'R', 'S'] }],
    // 공연·장소 이름은 지어낸 것이다(디자인 샘플과 같다)
    concerts: [
      {
        concertId: 7,
        title: '노을섬 단독 콘서트 〈새벽의 좌석〉',
        description:
          '목 데이터로 만든 공연이다.\n첫 회차는 예매 중이고, 둘째 회차는 10분 뒤에 오픈한다.',
      },
      {
        concertId: 8,
        title: '파란 정거장 2026 투어',
        description: '목 데이터로 만든 공연이다. 오픈까지 이틀이 남았다.',
      },
      {
        concertId: 9,
        title: '미드나잇 오케스트라 〈별의 지도〉',
        description: '목 데이터로 만든 공연이다. 모든 회차가 끝났다.',
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
      {
        scheduleId: 41,
        concertId: 8,
        venueName: '부산 오션돔',
        startsAtMillis: nowMillis + 25 * DAY,
        openAtMillis: nowMillis + 2 * DAY + 3 * 60 * MINUTE,
        maxPerUser: 2,
      },
      {
        scheduleId: 51,
        concertId: 9,
        venueName: '서울 심포니홀',
        startsAtMillis: nowMillis - DAY,
        openAtMillis: nowMillis - 10 * DAY,
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
    expiredAccessTokens: new Set(),
    revokedAccessTokens: new Set(),
    refreshTokens: new Map(),
    revokedRefreshTokens: new Set(),
    queueEntries: new Map(),
    reservations: new Map(),
    holdRequests: new Map(),
    payments: new Map(),
    paymentRequests: new Map(),
    seatConflictUsed: false,
    queueUnavailableLeft: 1,
    queueExpiredUsed: false,
  }
}

export function createSeats(spec: {
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
