// 주소의 ID 칸(`/concerts/:concertId` 등)을 양의 정수로 읽는다. 읽을 수 없으면 null이고 화면은 "없음"으로 다룬다.
// `0x10`, `1e3`처럼 Number가 받아 주는 다른 표기와 안전 범위를 넘는 수는 받지 않는다
export function readRouteId(value: string | undefined): number | null {
  if (value === undefined || !/^\d+$/.test(value)) {
    return null
  }
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}
