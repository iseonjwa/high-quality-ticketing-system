export const paths = {
  home: '/',
  concert: '/concerts/:concertId',
  login: '/login',
  signup: '/signup',
  passwordChange: '/password-change',
  me: '/me',
  queue: '/schedules/:scheduleId/queue',
  seats: '/schedules/:scheduleId/seats',
  payment: '/reservations/:reservationId/payment',
  paymentTossReturn: '/reservations/:reservationId/payment/toss/:result',
  reservation: '/reservations/:reservationId',
  reservationComplete: '/reservations/:reservationId/complete',
  myReservations: '/me/reservations',
  lottery: '/lotteries/:lotteryId',
  admin: '/admin',
  adminConcertNew: '/admin/concerts/new',
  adminConcert: '/admin/concerts/:concertId',
  adminLotteryNew: '/admin/concerts/:concertId/schedules/:scheduleId/lottery',
  adminLottery: '/admin/lotteries/:lotteryId',
  adminPasswordReset: '/admin/users/password-reset',
  adminDemoFaults: '/admin/demo/faults',
  // 발권 이벤트 부적(ISE-257). 값은 주소 조각(#)에 담는다(pages/charm/charmLink.ts)
  charm: '/charm',
} as const

export function concertPath(concertId: number): string {
  return `/concerts/${concertId}`
}

export function adminConcertPath(concertId: number): string {
  return `/admin/concerts/${concertId}`
}

// 회차에 추첨을 설정하는 화면. 회차 시각을 공연 상세 조회로 받으므로 공연 번호도 경로에 둔다
export function adminLotteryNewPath(concertId: number, scheduleId: number): string {
  return `/admin/concerts/${concertId}/schedules/${scheduleId}/lottery`
}

export function adminLotteryPath(lotteryId: number): string {
  return `/admin/lotteries/${lotteryId}`
}

export function queuePath(scheduleId: number): string {
  return `/schedules/${scheduleId}/queue`
}

export function seatsPath(scheduleId: number): string {
  return `/schedules/${scheduleId}/seats`
}

export function paymentPath(reservationId: number): string {
  return `/reservations/${reservationId}/payment`
}

// 토스 결제창이 결제 뒤 돌려보내는 주소
export function tossReturnPath(reservationId: number, result: 'success' | 'fail'): string {
  return `/reservations/${reservationId}/payment/toss/${result}`
}

// 토스는 오리진을 포함한 절대 주소만 받는다
export function tossReturnUrl(reservationId: number, result: 'success' | 'fail'): string {
  return window.location.origin + tossReturnPath(reservationId, result)
}

export function reservationPath(reservationId: number): string {
  return `/reservations/${reservationId}`
}

// 결제 직후의 예매 결과 화면(예매 단계 "완료"). 내 예매에서 여는 상세는 reservationPath다
export function reservationCompletePath(reservationId: number): string {
  return `/reservations/${reservationId}/complete`
}

// 추첨 응모 화면. 공연 상세에서 회차를 고르면 그 회차의 추첨(GET /schedules/{id}/lottery)으로 찾아 들어온다(ISE-227)
export function lotteryPath(lotteryId: number): string {
  return `/lotteries/${lotteryId}`
}
