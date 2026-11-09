import {
  enterQueueAndWaitSeats,
  expectConfirmed,
  expectInMyReservations,
  expectPaymentChecking,
  expectSeatConflict,
  goToPayment,
  hold,
  login,
  openConcert,
  pay,
  pickOneSeat,
} from './flow.ts'
import { expect, test } from './fixtures.ts'

test.describe('시연 흐름', () => {
  test('정상: 로그인부터 결제, 내 예매 확인까지', async ({
    page,
    account,
    concertId,
    seats,
    entryPath,
    beforeHold,
    beforePay,
  }) => {
    await login(page, entryPath('normal'), account)
    await openConcert(page, concertId)
    await enterQueueAndWaitSeats(page)
    await pickOneSeat(page, seats.first)
    await beforeHold(seats.first)
    await hold(page)
    await goToPayment(page)
    await beforePay()
    await pay(page)
    const reservationNo = await expectConfirmed(page)
    await expectInMyReservations(page, reservationNo)
  })

  test('409: 뺏긴 좌석을 두고 다른 좌석을 다시 골라 결제', async ({
    page,
    account,
    concertId,
    seats,
    entryPath,
    beforeHold,
  }) => {
    await login(page, entryPath('seat-conflict'), account)
    await openConcert(page, concertId)
    await enterQueueAndWaitSeats(page)
    await pickOneSeat(page, seats.first)
    await beforeHold(seats.first)
    await hold(page)
    await expectSeatConflict(page, seats.first)

    await pickOneSeat(page, seats.second)
    await hold(page)
    await goToPayment(page)
    await pay(page)
    await expectConfirmed(page)
  })

  test('확인 중: 결제 결과가 불확실해도 확인 뒤 확정', async ({
    page,
    account,
    concertId,
    seats,
    entryPath,
    beforePay,
  }) => {
    await login(page, entryPath('payment-unknown'), account)
    await openConcert(page, concertId)
    await enterQueueAndWaitSeats(page)
    await pickOneSeat(page, seats.first)
    await hold(page)
    await goToPayment(page)
    await beforePay()
    await pay(page)
    await expectPaymentChecking(page)
    await expectConfirmed(page)
    await expect(page).toHaveURL(/\/complete/)
  })
})
