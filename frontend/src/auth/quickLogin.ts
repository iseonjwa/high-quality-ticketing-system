// 개발 중에 로그인 화면에서 한 번에 로그인하는 계정. 운영 빌드에는 버튼도 계정도 들어가지 않는다.
//
// Note: 배포한 화면은 누구나 열 수 있다. 관리자 비밀번호를 번들에 넣으면 아무나 관리자로 들어오므로
// 개발 서버에서만 쓴다. `import.meta.env.DEV`를 함수 인자로 넘기면 빌드 때 상수로 접히지 않아 목 계정이
// 번들에 남는다(확인함). 그래서 quickLoginAccounts와 화면이 `import.meta.env.DEV`를 직접 검사한다.

export interface QuickLoginAccount {
  role: 'USER' | 'ADMIN'
  label: string
  email: string
  password: string
}

type QuickLoginEnv = Pick<
  ImportMetaEnv,
  | 'MODE'
  | 'VITE_QUICK_LOGIN_USER_EMAIL'
  | 'VITE_QUICK_LOGIN_USER_PASSWORD'
  | 'VITE_QUICK_LOGIN_ADMIN_EMAIL'
  | 'VITE_QUICK_LOGIN_ADMIN_PASSWORD'
>

// 목 모드(`npm run dev:mock`) 계정. 목 데이터(mocks/db.ts)와 같은 지어낸 값이다
const MOCK_ACCOUNTS = {
  user: { email: 'demo@iseonjwa.dev', password: 'password1234' },
  admin: { email: 'admin@iseonjwa.dev', password: 'password1234' },
}

// 실제 백엔드에 붙인 `npm run dev`에서는 `.env.local`에 적은 계정을 쓴다. 관리자는 루트 `.env`의
// ADMIN_EMAIL·ADMIN_PASSWORD로 user-service가 만든 계정이고, 일반 사용자는 가입 화면에서 한 번 만들어 둔다.
// 이메일과 비밀번호가 다 있는 계정만 버튼으로 보인다.
export function quickLoginAccounts(): QuickLoginAccount[] {
  if (!import.meta.env.DEV) {
    return []
  }
  return resolveQuickLoginAccounts(import.meta.env)
}

export function resolveQuickLoginAccounts(env: QuickLoginEnv): QuickLoginAccount[] {
  const mock = env.MODE === 'mock'
  const candidates: QuickLoginAccount[] = [
    {
      role: 'USER',
      label: '일반 사용자로 로그인',
      email: env.VITE_QUICK_LOGIN_USER_EMAIL ?? (mock ? MOCK_ACCOUNTS.user.email : ''),
      password: env.VITE_QUICK_LOGIN_USER_PASSWORD ?? (mock ? MOCK_ACCOUNTS.user.password : ''),
    },
    {
      role: 'ADMIN',
      label: '관리자로 로그인',
      email: env.VITE_QUICK_LOGIN_ADMIN_EMAIL ?? (mock ? MOCK_ACCOUNTS.admin.email : ''),
      password: env.VITE_QUICK_LOGIN_ADMIN_PASSWORD ?? (mock ? MOCK_ACCOUNTS.admin.password : ''),
    },
  ]
  return candidates.filter((account) => account.email !== '' && account.password !== '')
}
