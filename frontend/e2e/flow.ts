import { expect, type Page } from '@playwright/test'
import type { Account, Scenario } from './fixtures.ts'

// 화면 단계 함수. 접근 가능한 이름으로만 요소를 잡고, 흐름 중간에는 page.goto를 쓰지 않는다
// (MSW 모드는 새로고침하면 목 상태가 처음으로 돌아간다).

export async function login(page: Page, entryPath: string, account: Account) {
  await page.goto(entryPath)
  await page.getByLabel('이메일').fill(account.email)
  await page.getByLabel('비밀번호', { exact: true }).fill(account.password)
  await page.getByRole('button', { name: '로그인', exact: true }).click()
}

export async function openConcert(page: Page, concertId: number) {
  // 로그인 직후 한 번만 goto한다. 로그인 응답 뒤 이동이 끝나기를 기다린 뒤 부른다.
  await expect(page).not.toHaveURL(/\/login/)
  await page.goto(`/concerts/${concertId}`)
  await page.getByRole('complementary').getByRole('link', { name: '예매하기' }).click()
}

export async function enterQueueAndWaitSeats(page: Page) {
  await expect(page).toHaveURL(/\/schedules\/\d+\/queue/)
  await expect(page).toHaveURL(/\/schedules\/\d+\/seats/)
  await expect(page.getByRole('radio', { name: /^A구역/ })).toBeVisible()
}

export async function pickOneSeat(page: Page, seat: string) {
  await page.getByRole('radiogroup', { name: '매수' }).getByRole('radio', { name: '1매' }).click()
  await page.getByRole('radio', { name: /^A구역/ }).click()
  await page.getByRole('button', { name: new RegExp(`^[^,]*${seat},`) }).click()
}

export async function hold(page: Page) {
  await page.getByRole('button', { name: '1매 선택 완료' }).click()
}

export async function expectSeatConflict(page: Page, seat: string) {
  await expect(page.getByText('이미 선택된 좌석입니다')).toBeVisible()
  await expect(
    page.getByRole('button', { name: new RegExp(`${seat},.*선택할 수 없음`) }),
  ).toBeDisabled()
}

export async function goToPayment(page: Page) {
  await page
    .getByRole('dialog', { name: '좌석을 잡았어요' })
    .getByRole('button', { name: '결제하러 가기' })
    .click()
  await expect(page).toHaveURL(/\/reservations\/\d+\/payment/)
}

export async function pay(page: Page) {
  await page.getByRole('button', { name: /원 결제하기$/ }).click()
}

export async function expectPaymentChecking(page: Page) {
  // 같은 문구가 sr-only status와 상자에 두 번 나온다. status만 본다.
  await expect(
    page.getByRole('status').filter({ hasText: '결제를 확인하고 있어요' }),
  ).toBeAttached()
}

export async function expectConfirmed(page: Page): Promise<string> {
  await expect(page.getByRole('heading', { level: 1, name: '예매가 확정됐어요' })).toBeVisible()
  // 확정 화면은 '예매 번호' 용어(dt) 바로 뒤 정의(dd)에 번호를 둔다.
  const reservationNo = await page
    .locator('dt', { hasText: /^예매 번호$/ })
    .locator('xpath=following-sibling::dd[1]')
    .innerText()
  expect(reservationNo).toMatch(/^R\d+-\d+$/)
  return reservationNo
}

export async function expectInMyReservations(page: Page, reservationNo: string) {
  await page.getByRole('link', { name: '내 예매 목록' }).click()
  await expect(page).toHaveURL(/\/me\/reservations/)
  await expect(
    page.getByRole('link', { name: new RegExp(`예매 번호 ${reservationNo}.*`) }),
  ).toContainText(/예매 확정|확정/)
}

export type { Scenario }
