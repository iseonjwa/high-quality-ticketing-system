import { z } from 'zod'
import { useStore } from 'zustand'
import { createJSONStorage, persist } from 'zustand/middleware'
import { createStore } from 'zustand/vanilla'

// POST /auth/login·/auth/refresh 응답 `data`(SA 11-5). 재발급도 Refresh 토큰을 새로 준다.
export const tokenPairSchema = z.object({
  accessToken: z.string().min(1),
  refreshToken: z.string().min(1),
  accessTokenExpiresIn: z.number().int().positive(),
})
export type TokenPair = z.infer<typeof tokenPairSchema>

interface SessionState {
  accessToken: string | null
  refreshToken: string | null
}

const SESSION_STORAGE_KEY = 'iseonjwa.session'

// Note: Access 토큰은 메모리에만 두고, Refresh 토큰만 탭 단위 sessionStorage에 남긴다(ADR 0040 4절).
// 새로고침해도 로그인이 유지되고 탭을 닫으면 사라진다. 서버는 사용자당 Refresh 토큰을 하나만 두고
// 재발급마다 바꾸므로(RedisSessionStore) 여러 탭이 같은 토큰을 나눠 쓰면 먼저 재발급한 탭만 이긴다.
export const sessionStore = createStore<SessionState>()(
  persist((): SessionState => ({ accessToken: null, refreshToken: null }), {
    name: SESSION_STORAGE_KEY,
    storage: createJSONStorage(() => sessionStorage),
    partialize: (state) => ({ refreshToken: state.refreshToken }),
  }),
)

export function saveTokens(tokens: TokenPair) {
  sessionStore.setState({ accessToken: tokens.accessToken, refreshToken: tokens.refreshToken })
}

export function clearSession() {
  sessionStore.setState({ accessToken: null, refreshToken: null })
}

// 화면이 로그인 여부로 경로를 막을 때 쓴다. Access 토큰이 없어도 Refresh 토큰이 있으면 첫 요청에서 재발급한다.
export function useIsSignedIn(): boolean {
  return useStore(sessionStore, (state) => state.refreshToken !== null)
}
