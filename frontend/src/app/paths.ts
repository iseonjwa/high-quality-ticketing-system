// 내 예매 화면은 ISE-117에서 만든다. 그 전까지는 없는 경로라 NotFound가 뜬다.
export const paths = {
  home: '/',
  login: '/login',
  signup: '/signup',
  passwordChange: '/password-change',
  me: '/me',
  myReservations: '/me/reservations',
} as const
