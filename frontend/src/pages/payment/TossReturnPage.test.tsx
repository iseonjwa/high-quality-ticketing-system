import { render, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { describe, expect, it } from 'vitest'
import { paths, tossReturnPath } from '@/app/paths'
import { readPaymentAttempt, readTossCheckout, saveTossCheckout } from '@/auth/paymentAttempts'
import { TossReturnPage } from '@/pages/payment/TossReturnPage'

const RESERVATION_ID = 77
const CHECKOUT = { token: 'mock-payment.77.1', orderId: 'order-77-abcdef', amount: 2000 }

interface Visit {
  pathname: string
  state: unknown
  action: string
}

// 결제 화면은 자리만 둔다. 이동한 위치와 저장소만 본다
function renderReturn(result: 'success' | 'fail', query: string) {
  const router = createMemoryRouter(
    [
      { path: paths.paymentTossReturn, element: <TossReturnPage /> },
      { path: paths.payment, element: <p>결제 화면</p> },
      { path: paths.myReservations, element: <p>내 예매</p> },
    ],
    { initialEntries: [`${tossReturnPath(RESERVATION_ID, result)}?${query}`] },
  )
  const visited: Visit[] = []
  router.subscribe(({ location, historyAction }) =>
    visited.push({ pathname: location.pathname, state: location.state, action: historyAction }),
  )
  render(<RouterProvider router={router} />)
  return visited
}

describe('TossReturnPage', () => {
  it('성공이고 기록과 같으면 시도를 저장하고 결제 화면으로 바꿔 들어간다', async () => {
    saveTossCheckout(RESERVATION_ID, CHECKOUT)

    const visited = renderReturn('success', 'paymentKey=tgen_1&orderId=order-77-abcdef&amount=2000')

    expect(await screen.findByText('결제 화면')).toBeInTheDocument()
    const visit = visited.find((v) => v.pathname === `/reservations/${RESERVATION_ID}/payment`)
    expect(visit?.action).toBe('REPLACE')
    expect(visit?.state).toBeNull()
    const attempt = readPaymentAttempt(RESERVATION_ID)
    expect(attempt).toMatchObject({ token: CHECKOUT.token, clientPaymentRef: 'tgen_1' })
    expect(attempt?.key).toBeTruthy()
    expect(readTossCheckout(RESERVATION_ID)).toBeNull()
  })

  it('금액이 다르면 승인 시도를 저장하지 않고 mismatch 안내와 함께 이동한다', async () => {
    saveTossCheckout(RESERVATION_ID, CHECKOUT)

    const visited = renderReturn('success', 'paymentKey=tgen_1&orderId=order-77-abcdef&amount=1')

    await screen.findByText('결제 화면')
    expect(visited.at(-1)?.state).toEqual({ tossReturn: 'mismatch' })
    expect(readPaymentAttempt(RESERVATION_ID)).toBeNull()
    expect(readTossCheckout(RESERVATION_ID)).toBeNull()
  })

  it('fail이면 코드와 함께 이동하고 결제창 기록을 지운다', async () => {
    saveTossCheckout(RESERVATION_ID, CHECKOUT)

    const visited = renderReturn('fail', 'code=REJECT_CARD_COMPANY&message=임의')

    await screen.findByText('결제 화면')
    expect(visited.at(-1)?.state).toEqual({ tossReturn: 'failed', tossCode: 'REJECT_CARD_COMPANY' })
    expect(readTossCheckout(RESERVATION_ID)).toBeNull()
  })

  it('구매자 취소면 canceled로 이동한다', async () => {
    const visited = renderReturn('fail', 'code=PAY_PROCESS_CANCELED&message=취소')

    await screen.findByText('결제 화면')
    expect(visited.at(-1)?.state).toEqual({ tossReturn: 'canceled' })
  })

  it('성공 주소를 새로고침해 결제창 기록이 없어도 같은 paymentKey의 시도가 있으면 이어 간다', async () => {
    sessionStorage.setItem(
      `iseonjwa.paymentAttempt.${RESERVATION_ID}`,
      JSON.stringify({ token: CHECKOUT.token, key: 'k1', clientPaymentRef: 'tgen_1' }),
    )

    const visited = renderReturn('success', 'paymentKey=tgen_1&orderId=order-77-abcdef&amount=2000')

    await screen.findByText('결제 화면')
    expect(visited.at(-1)?.state).toBeNull()
    expect(readPaymentAttempt(RESERVATION_ID)?.key).toBe('k1')
  })

  it('잘못된 주소 값이면 내 예매로 보낸다', async () => {
    const router = createMemoryRouter(
      [
        { path: paths.paymentTossReturn, element: <TossReturnPage /> },
        { path: paths.myReservations, element: <p>내 예매</p> },
      ],
      { initialEntries: ['/reservations/77/payment/toss/other'] },
    )
    render(<RouterProvider router={router} />)

    await waitFor(() => expect(screen.getByText('내 예매')).toBeInTheDocument())
  })
})
