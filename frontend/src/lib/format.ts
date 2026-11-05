import { instantMillis } from '@/lib/instant'

const numberFormat = new Intl.NumberFormat('ko-KR')

const SECOND = 1000
const MINUTE = 60
const HOUR = 3600
const DAY = 86400

function pad(value: number): string {
  return String(value).padStart(2, '0')
}

export function formatWon(amount: number): string {
  return `${numberFormat.format(amount)}원`
}

// Note: 남은 시간은 올림으로 센다. 내림이면 0.9초 남았을 때 이미 "00:00"이 보이는데 만료 처리(onExpire)는
// 아직 일어나지 않아, 화면은 끝났는데 버튼은 그대로인 순간이 최대 1초 생긴다. 올림이면 "00:00"과 만료가 같은 순간이다.
function ceilSeconds(millis: number): number {
  return Math.max(0, Math.ceil(millis / SECOND))
}

// 남은 시간. 1시간 미만 "04:30", 이상 "1:04:30", 음수는 "00:00".
export function formatRemaining(millis: number): string {
  const total = ceilSeconds(millis)
  const hours = Math.floor(total / HOUR)
  const minutes = Math.floor((total % HOUR) / MINUTE)
  const seconds = total % MINUTE
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`
}

// 오픈까지 남은 시간. 하루 이상 "2일 03:04:05", 미만 "03:04:05".
export function formatCountdown(millis: number): string {
  const total = ceilSeconds(millis)
  const days = Math.floor(total / DAY)
  const hours = Math.floor((total % DAY) / HOUR)
  const minutes = Math.floor((total % HOUR) / MINUTE)
  const clock = `${pad(hours)}:${pad(minutes)}:${pad(total % MINUTE)}`
  return days > 0 ? `${numberFormat.format(days)}일 ${clock}` : clock
}

// 예상 대기 시간. 정확한 약속처럼 보이지 않게 분 단위로 뭉뚱그린다.
export function formatWaitTime(seconds: number): string {
  if (seconds < MINUTE) {
    return '1분 미만'
  }
  const hours = Math.floor(seconds / HOUR)
  const minutes = Math.floor((seconds % HOUR) / MINUTE)
  if (hours === 0) {
    return `약 ${numberFormat.format(minutes)}분`
  }
  return minutes === 0
    ? `약 ${numberFormat.format(hours)}시간`
    : `약 ${numberFormat.format(hours)}시간 ${numberFormat.format(minutes)}분`
}

// 공연·오픈 일시. 보는 사람의 시간대와 상관없이 한국 시각으로 "2026.11.20 (금) 19:00"처럼 쓴다(디자인 샘플 형식).
// 서버는 Instant(`Z`)로 주므로 브라우저 시간대로 바꾸면 해외에서 날짜가 달라진다. 공연은 한국에서 열린다.
const dateTimeParts = new Intl.DateTimeFormat('ko-KR', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  weekday: 'short',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

export function formatDateTime(iso: string): string {
  const parts = kstParts(iso)
  return `${parts.year}.${parts.month}.${parts.day} (${parts.weekday}) ${parts.hour}:${parts.minute}`
}

// 한국 시각의 연·월·일·요일·시·분. 홈의 오픈 달력처럼 날짜를 나눠 그릴 때 쓴다
export interface KstParts {
  year: string
  month: string
  day: string
  weekday: string
  hour: string
  minute: string
}

export function kstParts(iso: string): KstParts {
  const parts = Object.fromEntries(
    dateTimeParts.formatToParts(instantMillis(iso)).map((part) => [part.type, part.value]),
  )
  return {
    year: parts.year,
    month: parts.month,
    day: parts.day,
    weekday: parts.weekday,
    hour: parts.hour,
    minute: parts.minute,
  }
}

// 날짜만 "2026.11.20 (금)"
export function formatDate(iso: string): string {
  const parts = kstParts(iso)
  return `${parts.year}.${parts.month}.${parts.day} (${parts.weekday})`
}

// 카드처럼 좁은 곳에 쓰는 "11.20 (금) 19:00". 연도는 뺀다
export function formatShortDateTime(iso: string): string {
  const parts = kstParts(iso)
  return `${parts.month}.${parts.day} (${parts.weekday}) ${parts.hour}:${parts.minute}`
}

// 한국 날짜로 며칠 남았는지. 같은 날이면 0, 지났으면 음수다. "D-3" 같은 표시에 쓴다
export function kstDaysUntil(iso: string, nowMillis: number): number {
  return kstDayNumber(instantMillis(iso)) - kstDayNumber(nowMillis)
}

const KST_OFFSET_MILLIS = 9 * 60 * 60 * 1000
const DAY_MILLIS = 24 * 60 * 60 * 1000

function kstDayNumber(millis: number): number {
  return Math.floor((millis + KST_OFFSET_MILLIS) / DAY_MILLIS)
}
