/// <reference types="vite/client" />

// 개발 서버 전용 빠른 로그인 계정(auth/quickLogin.ts). `frontend/.env.local`에 둔다(git에 올리지 않는다)
interface ImportMetaEnv {
  readonly VITE_QUICK_LOGIN_USER_EMAIL?: string
  readonly VITE_QUICK_LOGIN_USER_PASSWORD?: string
  readonly VITE_QUICK_LOGIN_ADMIN_EMAIL?: string
  readonly VITE_QUICK_LOGIN_ADMIN_PASSWORD?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
