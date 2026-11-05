import { instantMillis } from '@/lib/instant'

// 운영자가 `<input type="datetime-local">`에 넣은 시각을 한국 시각으로 읽어 서버가 받는 ISO 8601로 바꾼다.
// 공연은 한국에서 열리므로 보는 사람의 시간대와 상관없이 같은 결과여야 한다(함수가 시간대를 쓰지 않는다).
const LOCAL_FORMAT = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/

export function kstLocalToIso(local: string): string | null {
  const match = LOCAL_FORMAT.exec(local)
  if (match === null) {
    return null
  }
  const [year, month, day, hour, minute] = match.slice(1).map(Number)
  // 2월 30일이나 25시처럼 달력에 없는 값은 UTC로 한 번 만들어 되돌려 보며 걸러낸다
  const check = new Date(Date.UTC(year, month - 1, day, hour, minute))
  const valid =
    check.getUTCFullYear() === year &&
    check.getUTCMonth() === month - 1 &&
    check.getUTCDate() === day &&
    check.getUTCHours() === hour &&
    check.getUTCMinutes() === minute
  return valid ? `${local}:00+09:00` : null
}

const KST_OFFSET_MILLIS = 9 * 60 * 60_000

// 서버 시각(ISO 8601)을 datetime-local 칸에 넣을 한국 시각("2026-11-20T10:00")으로 바꾼다. 초는 버린다
export function isoToKstLocal(iso: string): string {
  return new Date(instantMillis(iso) + KST_OFFSET_MILLIS).toISOString().slice(0, 16)
}
