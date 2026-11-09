import { test as base } from '@playwright/test'
import { composeTarget } from './targets/compose.ts'
import { mswTarget } from './targets/msw.ts'

export type E2eTarget = 'msw' | 'compose'
export type Scenario = 'normal' | 'seat-conflict' | 'payment-unknown'

export interface Account {
  email: string
  password: string
}

// 두 대상(MSW 모드, compose 스택)이 같은 spec을 쓰도록 spec이 아는 것은 이 인터페이스뿐이다.
export interface TargetFixtures {
  account: Account
  concertId: number
  // 고를 좌석 이름. 첫 좌석이 409 시나리오에서 빼앗기는 좌석이고, 둘째가 다시 고르는 좌석이다.
  seats: { first: string; second: string }
  // 좌석을 잡기 직전과 결제 직전에 대상이 할 준비(compose에서 다른 사용자가 좌석을 먼저 잡는 등). MSW에서는 없다.
  beforeHold: (seat: string) => Promise<void>
  beforePay: () => Promise<void>
  // 시나리오를 고른 첫 이동 주소. 흐름 중간에 goto하면 MSW 상태가 초기화되므로 첫 이동에만 쓴다.
  entryPath: (scenario: Scenario) => string
}

export interface E2eOptions {
  target: E2eTarget
}

export const test = base.extend<E2eOptions & TargetFixtures & { composeNotReady: void }>({
  target: ['msw', { option: true }],
  // 2부에서 compose 대상을 구현하면 이 fixture를 지운다.
  composeNotReady: [
    async ({ target }, use, testInfo) => {
      testInfo.skip(target === 'compose', 'compose 대상은 2부에서 구현')
      await use()
    },
    { auto: true },
  ],
  account: async ({ target }, use) => use(pick(target).account),
  concertId: async ({ target }, use) => use(pick(target).concertId),
  seats: async ({ target }, use) => use(pick(target).seats),
  beforeHold: async ({ target }, use) => use(pick(target).beforeHold),
  beforePay: async ({ target }, use) => use(pick(target).beforePay),
  entryPath: async ({ target }, use) => use(pick(target).entryPath),
})

export { expect } from '@playwright/test'

function pick(target: E2eTarget): TargetFixtures {
  return target === 'msw' ? mswTarget : composeTarget
}
