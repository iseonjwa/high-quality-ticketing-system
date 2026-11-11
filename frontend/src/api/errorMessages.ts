import { isApiError } from '@/api/apiError'

// 에러 코드(SA 11-3, 서비스별 ErrorCode enum)를 화면 안내로 바꾼다. 문구 규칙과 톤은 design-system.md 1·6절을 따른다.
// 해요체로 원인 한 문장, 다음 행동 한 문장. HTTP 코드·토큰 같은 기술 용어는 보이지 않는다.
// 다음 행동(재조회, 대기열로 이동 등)은 화면마다 달라서 여기서 정하지 않는다.

// info: 정보, warning: 경고, danger: 위험, field: 입력 칸 아래 문구
export type ErrorTone = 'info' | 'warning' | 'danger' | 'field'

export interface ErrorNotice {
  tone: ErrorTone
  message: string
  // 예상하지 못한 오류에만 문의용 번호를 보인다
  traceId: string | null
}

interface NoticeTemplate {
  tone: ErrorTone
  message: string
  showTraceId?: boolean
}

const UNEXPECTED: NoticeTemplate = {
  tone: 'danger',
  message: '문제가 생겼어요. 문의할 때 아래 번호를 알려 주세요',
  showTraceId: true,
}

// 문의 번호가 없는 예상하지 못한 오류(중계 서버의 HTML 응답, ApiError가 아닌 오류)
const UNEXPECTED_WITHOUT_TRACE = '문제가 생겼어요. 잠시 뒤 다시 시도해 주세요'

// 응답을 받지 못했거나(상태 0) Gateway 응답 시한을 넘겼다(504). 선점·결제 화면은 이 안내 대신
// isOutcomeUnknown으로 가려 "확인 중"을 보인다(design-system.md 6절 "결제 결과 모름")
const NO_RESPONSE: ErrorNotice = {
  tone: 'warning',
  message: '연결이 끊겼어요. 네트워크를 확인하고 다시 시도해 주세요',
  traceId: null,
}
const GATEWAY_TIMEOUT: ErrorNotice = {
  tone: 'warning',
  message: '응답이 늦어지고 있어요. 잠시 뒤 다시 시도해 주세요',
  traceId: null,
}

const PAYMENT_TIME_OVER: NoticeTemplate = {
  tone: 'danger',
  message: '결제 가능 시간이 지났어요. 좌석을 다시 골라 주세요',
}

const SIGN_IN_AGAIN: NoticeTemplate = {
  tone: 'warning',
  message: '로그인이 끝났어요. 다시 로그인해 주세요',
}

export const ERROR_NOTICES: Readonly<Record<string, NoticeTemplate>> = {
  // 공통
  VALIDATION_FAILED: { tone: 'field', message: '입력한 값을 확인해 주세요' },
  // 401은 보통 보이지 않는다. 재발급 뒤 재시도하고, 재발급도 실패했을 때만 이 문구로 로그인 화면에 보낸다
  UNAUTHORIZED: SIGN_IN_AGAIN,
  TOKEN_EXPIRED: SIGN_IN_AGAIN,
  TOKEN_REVOKED: SIGN_IN_AGAIN,
  FORBIDDEN: { tone: 'danger', message: '이 화면을 볼 수 있는 권한이 없어요' },
  NOT_FOUND: { tone: 'warning', message: '찾는 정보가 없어요. 목록에서 다시 골라 주세요' },
  // 같은 키에 다른 본문은 화면이 키를 잘못 다룬 것이다. 원인을 찾을 수 있게 번호를 보인다
  IDEMPOTENCY_KEY_REUSED: UNEXPECTED,
  RATE_LIMITED: { tone: 'warning', message: '요청이 많아요. 잠시 뒤 다시 시도해 주세요' },
  SERVICE_UNAVAILABLE: { tone: 'warning', message: '연결이 잠시 불안정해요. 곧 다시 시도할게요' },

  // 회원
  EMAIL_DUPLICATED: { tone: 'field', message: '이미 가입한 이메일이에요. 로그인해 주세요' },
  INVALID_CREDENTIALS: { tone: 'field', message: '이메일 또는 비밀번호를 확인해 주세요' },
  ACCOUNT_LOCKED: {
    tone: 'danger',
    message: '로그인을 여러 번 실패해 계정이 잠겼어요. 운영자에게 비밀번호 초기화를 요청해 주세요',
  },
  PASSWORD_CHANGE_REQUIRED: {
    tone: 'warning',
    message: '임시 비밀번호예요. 비밀번호를 바꾼 뒤 로그인해 주세요',
  },

  // 대기열·입장
  SALE_NOT_OPEN: { tone: 'info', message: '아직 오픈 전이에요' },
  QUEUE_NOT_JOINED: {
    tone: 'warning',
    message: '입장 시간이 지나 줄에서 빠졌어요. 다시 줄을 서 주세요',
  },
  QUEUE_NOT_READY: { tone: 'info', message: '아직 차례가 아니에요. 잠시만 기다려 주세요' },
  ENTRY_TOKEN_INVALID: {
    tone: 'danger',
    message: '입장 시간이 끝났어요. 대기열로 돌아가 주세요',
  },

  // 좌석 선점·예매
  SEAT_COUNT_MISMATCH: { tone: 'warning', message: '좌석은 1~4석을 겹치지 않게 골라 주세요' },
  // 서비스 이름과 같은 문장이라 발표에서 설계 의도가 보인다(design-system.md 9절 1번)
  SEAT_ALREADY_HELD: {
    tone: 'warning',
    message: '이미 선택된 좌석입니다. 남은 좌석은 그대로 두었어요',
  },
  PURCHASE_LIMIT_EXCEEDED: {
    tone: 'warning',
    message: '이 회차에서 살 수 있는 매수를 넘었어요. 내 예매를 확인해 주세요',
  },
  RESERVATION_NOT_PAYABLE: PAYMENT_TIME_OVER,
  RESERVATION_NOT_CANCELABLE: { tone: 'warning', message: '지금은 취소할 수 없는 예매예요' },
  LOTTERY_RESERVATION_NOT_CANCELABLE: {
    tone: 'warning',
    message: '추첨으로 배정된 예매는 취소할 수 없어요',
  },
  CANCEL_PERIOD_ENDED: { tone: 'warning', message: '공연이 시작돼 취소할 수 없어요' },

  // 결제
  PAYMENT_TOKEN_INVALID: PAYMENT_TIME_OVER,
  // 금액은 화면이 토큰과 같은 값을 보낸다. 다르면 화면 문제라 번호를 보인다
  PAYMENT_AMOUNT_MISMATCH: UNEXPECTED,
  PAYMENT_ALREADY_EXISTS: {
    tone: 'warning',
    message: '이미 처리 중인 결제가 있어요. 내 예매에서 결과를 확인해 주세요',
  },
  PAYMENT_ALREADY_CLOSED: {
    tone: 'danger',
    message: '이 예매의 결제는 끝났어요. 좌석을 새로 골라 주세요',
  },
  PG_UNAVAILABLE: {
    tone: 'warning',
    message: '결제사 연결이 원활하지 않아요. 좌석은 남은 시간 동안 유지돼요',
  },

  // 추첨
  LOTTERY_NOT_OPEN: { tone: 'info', message: '지금은 응모 기간이 아니에요' },
  LOTTERY_ALREADY_ENTERED: { tone: 'info', message: '이미 응모했어요. 결과 발표를 기다려 주세요' },
  BILLING_KEY_REQUIRED: { tone: 'warning', message: '자동결제 카드를 먼저 등록해 주세요' },

  // 운영자
  SCHEDULE_SEATS_ALREADY_CREATED: { tone: 'warning', message: '이미 좌석을 만든 회차예요' },
  LOTTERY_ALREADY_EXISTS: {
    tone: 'warning',
    message: '이 회차에는 이미 추첨이 있어요. 회차마다 추첨은 하나예요',
  },
  LOTTERY_SETUP_CLOSED: {
    tone: 'warning',
    message: '예매 오픈이나 응모 시작이 지나 추첨 설정을 바꿀 수 없어요',
  },
  LOTTERY_SEATS_NOT_ENOUGH: {
    tone: 'warning',
    message: '추첨석으로 뗄 좌석이 모자라요. 좌석을 만들었는지, 추첨석 수를 확인해 주세요',
  },
}

// 표에 없는 코드(METHOD_NOT_ALLOWED, INTERNAL_SERVER_ERROR, 응답 형식 어긋남 등)와 ApiError가 아닌 오류는
// 예상하지 못한 오류로 보인다. 연결 끊김과 504는 코드보다 상태로 먼저 가린다(504의 코드는 INTERNAL_SERVER_ERROR다).
export function describeError(error: unknown): ErrorNotice {
  if (!isApiError(error)) {
    return { tone: UNEXPECTED.tone, message: UNEXPECTED_WITHOUT_TRACE, traceId: null }
  }
  if (error.status === 0) {
    return NO_RESPONSE
  }
  if (error.status === 504) {
    return GATEWAY_TIMEOUT
  }
  const template = ERROR_NOTICES[error.code] ?? UNEXPECTED
  const traceId = template.showTraceId ? error.traceId : null
  if (template.showTraceId && traceId === null) {
    return { tone: template.tone, message: UNEXPECTED_WITHOUT_TRACE, traceId: null }
  }
  return { tone: template.tone, message: template.message, traceId }
}
