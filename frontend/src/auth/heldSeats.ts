import { z } from 'zod'
import { entityId, won } from '@/api/envelope'
import { heldSeatHoldSchema } from '@/api/reservations'

// 좌석 화면에서 잡은 선점(결제 토큰 포함)을 회차마다 sessionStorage에 둔다(ISE-115).
// 좌석 확보 대화상자가 떠 있는 사이 뒤로 가거나 새로고침하면 화면 상태가 사라져 결제로 갈 길이 없어진다.
// 좌석 화면이 다시 열릴 때 결제 가능 시간이 남았으면 이 값으로 대화상자를 다시 띄운다. 결제 화면이 결제를 끝내면 지운다.

const KEY_PREFIX = 'iseonjwa.heldSeats.'

// 화면에 보일 좌석 한 칸. 결제 화면 이동 값도 같은 모양을 쓴다
export const heldSeatSchema = z.object({
  seatId: entityId,
  section: z.string(),
  grade: z.string(),
  price: won,
  row: z.number().int().positive(),
  number: z.number().int().positive(),
})
export type HeldSeat = z.infer<typeof heldSeatSchema>

const storedHoldSchema = z.object({ hold: heldSeatHoldSchema, seats: z.array(heldSeatSchema) })
export type StoredHold = z.infer<typeof storedHoldSchema>

export function saveHeldSeats(scheduleId: number, value: StoredHold) {
  sessionStorage.setItem(keyOf(scheduleId), JSON.stringify(value))
}

// 읽을 수 없는 값은 지우고 null을 준다. 만료는 보지 않는다(화면이 결제 가능 시각으로 판단한다)
export function readHeldSeats(scheduleId: number): StoredHold | null {
  const raw = sessionStorage.getItem(keyOf(scheduleId))
  if (raw === null) {
    return null
  }
  let parsed: unknown
  try {
    parsed = JSON.parse(raw)
  } catch {
    parsed = undefined
  }
  const result = storedHoldSchema.safeParse(parsed)
  if (!result.success) {
    removeHeldSeats(scheduleId)
    return null
  }
  return result.data
}

export function removeHeldSeats(scheduleId: number) {
  sessionStorage.removeItem(keyOf(scheduleId))
}

// 로그아웃 때 부른다. 결제 토큰의 사용자가 로그아웃한 사용자다
export function removeAllHeldSeats() {
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
