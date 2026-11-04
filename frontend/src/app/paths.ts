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
  reservation: '/reservations/:reservationId',
  reservationComplete: '/reservations/:reservationId/complete',
  myReservations: '/me/reservations',
  lottery: '/lotteries/:lotteryId',
  admin: '/admin',
  adminConcertNew: '/admin/concerts/new',
  adminConcert: '/admin/concerts/:concertId',
} as const

export function concertPath(concertId: number): string {
  return `/concerts/${concertId}`
}

export function adminConcertPath(concertId: number): string {
  return `/admin/concerts/${concertId}`
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

export function reservationPath(reservationId: number): string {
  return `/reservations/${reservationId}`
}

// 결제 직후의 예매 결과 화면(예매 단계 "완료"). 내 예매에서 여는 상세는 reservationPath다
export function reservationCompletePath(reservationId: number): string {
  return `/reservations/${reservationId}/complete`
}

// 추첨 응모 화면. 공연·회차에서 추첨을 찾는 API가 없어 운영자가 추첨을 설정하며 받은 이 주소로만 들어온다
export function lotteryPath(lotteryId: number): string {
  return `/lotteries/${lotteryId}`
}
