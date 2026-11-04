import { adminHandlers } from '@/mocks/handlers/admin'
import { authHandlers } from '@/mocks/handlers/auth'
import { concertHandlers } from '@/mocks/handlers/concerts'
import { paymentHandlers } from '@/mocks/handlers/payments'
import { queueHandlers } from '@/mocks/handlers/queue'
import { reservationHandlers } from '@/mocks/handlers/reservations'

// 일반 예매 흐름(SA 2-4)의 API. 취소·접수형·추첨 API는 그 화면 이슈(ISE-119~124)에서 더한다.
export const handlers = [
  ...authHandlers,
  ...adminHandlers,
  ...concertHandlers,
  ...queueHandlers,
  ...reservationHandlers,
  ...paymentHandlers,
]
