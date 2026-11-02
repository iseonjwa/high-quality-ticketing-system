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

// 남은 시간. 1시간 미만 "04:30", 이상 "1:04:30", 음수는 "00:00".
export function formatRemaining(millis: number): string {
  const total = Math.max(0, Math.floor(millis / SECOND))
  const hours = Math.floor(total / HOUR)
  const minutes = Math.floor((total % HOUR) / MINUTE)
  const seconds = total % MINUTE
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`
}

// 오픈까지 남은 시간. 하루 이상 "2일 03:04:05", 미만 "03:04:05".
export function formatCountdown(millis: number): string {
  const total = Math.max(0, Math.floor(millis / SECOND))
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
