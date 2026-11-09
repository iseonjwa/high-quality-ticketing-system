import { test as base } from '@playwright/test'
import { prepareStack, prepareTarget, type ComposeStack } from './targets/compose.ts'
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
  // 이 대상이 시나리오를 돌릴 수 없는 이유. 돌릴 수 있으면 null이다(예: Mock PG 장애 주입이 닫혀 있다).
  skipReason: (scenario: Scenario) => string | null
}

export interface E2eOptions {
  target: E2eTarget
}

interface Prepared {
  fixtures: TargetFixtures
  dispose: () => Promise<void>
}

export const test = base.extend<
  TargetFixtures & { prepared: Prepared },
  E2eOptions & { stack: ComposeStack | null }
>({
  target: ['msw', { option: true, scope: 'worker' }],
  // compose일 때만 worker마다 한 번 공연·회차를 준비한다.
  stack: [
    async ({ target }, use) => use(target === 'compose' ? await prepareStack() : null),
    { scope: 'worker', timeout: 180_000 },
  ],
  // 테스트마다 사용자·좌석을 준비한다. compose는 끝날 때 장애 주입도 푼다.
  prepared: async ({ target, stack }, use) => {
    if (target === 'compose' && stack !== null) {
      const composed = await prepareTarget(stack)
      try {
        await use(composed)
      } finally {
        await composed.dispose()
      }
    } else {
      await use({ fixtures: mswTarget, dispose: async () => {} })
    }
  },
  account: async ({ prepared }, use) => use(prepared.fixtures.account),
  concertId: async ({ prepared }, use) => use(prepared.fixtures.concertId),
  seats: async ({ prepared }, use) => use(prepared.fixtures.seats),
  beforeHold: async ({ prepared }, use) => use(prepared.fixtures.beforeHold),
  beforePay: async ({ prepared }, use) => use(prepared.fixtures.beforePay),
  entryPath: async ({ prepared }, use) => use(prepared.fixtures.entryPath),
  skipReason: async ({ prepared }, use) => use(prepared.fixtures.skipReason),
})

export { expect } from '@playwright/test'
