import { z } from 'zod'
import { apiRequest } from '@/api/client'

// 운영자 사용자 관리(user-service AdminUserController). ADMIN만 쓴다.
// Note: 회원을 찾는 API(목록·이메일 검색)가 없어 회원 번호로만 부른다. 회원은 내 정보에서 자기 번호를 본다.

export const passwordResetSchema = z.object({ temporaryPassword: z.string().min(1) })

// 16자 임시 비밀번호를 만들어 돌려준다. 응답으로 한 번만 볼 수 있다(서버 Cache-Control: no-store).
// 대상의 Refresh 토큰을 폐기하고 잠금을 푼다. 이미 받은 Access 토큰은 만료(30분)까지 쓸 수 있다
export function resetUserPassword(userId: number) {
  return apiRequest({
    method: 'POST',
    path: `/admin/users/${userId}/password-reset`,
    schema: passwordResetSchema,
  })
}
