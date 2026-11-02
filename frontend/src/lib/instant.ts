// 서버가 준 시각 문자열(ISO 8601)을 밀리초로 바꾼다. API 시각은 모두 이 함수로 읽는다.
// Note: 서버는 Instant를 나노초 9자리까지 준다("…38.508856924Z", ISE-99). ECMAScript의 날짜 문자열 형식은
// 밀리초 3자리까지만 정하고 더 긴 값은 구현마다 다르게 읽을 수 있어, 소수점 아래를 3자리로 자른 뒤 읽는다.
export function instantMillis(iso: string): number {
  return Date.parse(iso.replace(/(\.\d{3})\d+/, '$1'))
}
