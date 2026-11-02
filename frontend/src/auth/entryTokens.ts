import { z } from 'zod'
import { isoDateTime } from '@/api/envelope'

// 입장 토큰은 회차마다 sessionStorage에 둔다(`docs/design/queue/contracts.md` 2절, SA 11-5).
// 만료 전이면 대기열을 건너뛰고, 403 ENTRY_TOKEN_INVALID를 받으면 지우고 대기열부터 다시 한다.
//
// Note: 저장한 토큰을 지우는 것은 Gateway의 403뿐이다. 브라우저 시계로 만료를 판단해 지우면, 시계가 빠른 사용자는
// 대기열에서 같은 토큰을 다시 받아도(재발급은 같은 토큰·같은 만료 시각이다, ADR 0031) 받자마자 또 지워져
// 좌석 화면과 대기열을 끝없이 오간다. 브라우저 시계의 만료는 "대기열을 건너뛸지"와 남은 시간 표시에만 쓴다.

const KEY_PREFIX = 'iseonjwa.entryToken.'

const storedEntryTokenSchema = z.object({ token: z.string().min(1), expiresAt: isoDateTime })
export type StoredEntryToken = z.infer<typeof storedEntryTokenSchema>

export function saveEntryToken(scheduleId: number, entry: StoredEntryToken) {
  sessionStorage.setItem(keyOf(scheduleId), JSON.stringify(entry))
}

// 저장한 토큰을 그대로 준다. 만료는 보지 않는다. 읽을 수 없는 값만 지우고 null을 준다.
export function readEntryToken(scheduleId: number): StoredEntryToken | null {
  const raw = sessionStorage.getItem(keyOf(scheduleId))
  if (raw === null) {
    return null
  }

  const parsed = storedEntryTokenSchema.safeParse(safeJsonParse(raw))
  if (!parsed.success) {
    removeEntryToken(scheduleId)
    return null
  }
  return parsed.data
}

// 대기열을 건너뛸지 정할 때 쓴다. 서버 시각으로 보정한 값을 nowMillis로 넘기면 더 정확하다.
export function isEntryTokenFresh(entry: StoredEntryToken, nowMillis = Date.now()): boolean {
  return Date.parse(entry.expiresAt) > nowMillis
}

// rejectedToken을 주면 저장한 토큰이 그것일 때만 지운다. 오래 걸린 요청의 403이
// 그 사이 새로 받은 토큰을 지우지 않게 하기 위해서다.
export function removeEntryToken(scheduleId: number, rejectedToken?: string) {
  if (rejectedToken !== undefined && readEntryToken(scheduleId)?.token !== rejectedToken) {
    return
  }
  sessionStorage.removeItem(keyOf(scheduleId))
}

// 로그아웃 때 부른다. 토큰의 sub가 로그아웃한 사용자라 다음 사용자에게 쓸모가 없다.
export function removeAllEntryTokens() {
  const keys: string[] = []
  for (let index = 0; index < sessionStorage.length; index++) {
    const key = sessionStorage.key(index)
    if (key?.startsWith(KEY_PREFIX)) {
      keys.push(key)
    }
  }
  keys.forEach((key) => sessionStorage.removeItem(key))
}

function keyOf(scheduleId: number): string {
  return KEY_PREFIX + scheduleId
}

function safeJsonParse(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return undefined
  }
}
