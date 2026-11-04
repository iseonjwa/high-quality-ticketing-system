// 좌석 화면은 ISE-115, 내 예매 화면은 ISE-117에서 만든다. 그 전까지 그 경로는 NotFound다.
export const paths = {
  home: '/',
  concert: '/concerts/:concertId',
  login: '/login',
  signup: '/signup',
  passwordChange: '/password-change',
  me: '/me',
  queue: '/schedules/:scheduleId/queue',
  myReservations: '/me/reservations',
} as const

export function concertPath(concertId: number): string {
  return `/concerts/${concertId}`
}

export function queuePath(scheduleId: number): string {
  return `/schedules/${scheduleId}/queue`
}

export function seatsPath(scheduleId: number): string {
  return `/schedules/${scheduleId}/seats`
}
