import type { TargetFixtures } from '../fixtures.ts'

// 개발 서버가 `--mode mock`으로 뜨고 MSW가 API를 대신한다. 데모 계정과 공연은 src/mocks/db.ts의 시드다.
// 앱 코드(`@/`)는 e2e에서 import하지 않아 값을 여기에 적는다.
export const mswTarget: TargetFixtures = {
  account: { email: 'demo@iseonjwa.dev', password: 'password1234' },
  concertId: 7,
  // 409 시나리오에서 목은 요청한 좌석 중 번호가 가장 작은 좌석을 남에게 넘긴다.
  seats: { first: 'A구역 1열 1번', second: 'A구역 1열 2번' },
  beforeHold: async () => {},
  beforePay: async () => {},
  skipReason: () => null,
  entryPath: (scenario) => (scenario === 'normal' ? '/login' : `/login?mock=${scenario}`),
}
