import type { TargetFixtures } from '../fixtures.ts'

// compose 대상(실제 스택 fixture)은 2부에서 구현한다. 그전까지 fixtures.ts가 compose 프로젝트의 테스트를 건너뛴다.

export const composeTarget: TargetFixtures = {
  account: { email: '', password: '' },
  concertId: 0,
  seats: { first: '', second: '' },
  beforeHold: async () => {},
  beforePay: async () => {},
  entryPath: () => '/login',
}
