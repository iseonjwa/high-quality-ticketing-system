import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useLocation, useNavigate } from 'react-router'
import { toast } from 'sonner'
import { isApiError } from '@/api/apiError'
import { changePassword, login } from '@/api/auth'
import { paths } from '@/app/paths'
import {
  MAX_LOGIN_FAILURES,
  passwordChangeSchema,
  type PasswordChangeInput,
  type PasswordChangeValues,
} from '@/auth/formSchemas'
import { readAuthState, returnPathOf, type AuthNavigationState } from '@/auth/navigation'
import { clearSession, useIsSignedIn } from '@/auth/session'
import { applyFieldErrors } from '@/components/form/applyFieldErrors'
import { PasswordField } from '@/components/form/PasswordField'
import { TextField } from '@/components/form/TextField'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { AuthCard, AuthFooterLine, AuthFooterLink } from '@/pages/auth/AuthCard'
import { AuthErrorNotice } from '@/pages/auth/AuthErrorNotice'

// 비밀번호 변경은 로그인 없이 이메일과 현재 비밀번호로 확인하는 공개 API다(임시 비밀번호로는 로그인할 수 없다).
// 그래서 로그인한 사용자도, 운영자가 초기화해 임시 비밀번호를 받은 사용자도 같은 화면을 쓴다.
export function PasswordChangePage() {
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const isSignedIn = useIsSignedIn()
  const authState = readAuthState(location.state)
  const temporary = authState.temporaryPassword === true
  const currentLabel = temporary ? '임시 비밀번호' : '현재 비밀번호'
  const [failedOnce, setFailedOnce] = useState(false)
  const [formError, setFormError] = useState<unknown>(null)
  const form = useForm<PasswordChangeInput, unknown, PasswordChangeValues>({
    resolver: zodResolver(passwordChangeSchema),
    defaultValues: {
      email: authState.email ?? '',
      currentPassword: '',
      newPassword: '',
      newPasswordConfirm: '',
    },
  })
  const { errors, isSubmitting } = form.formState

  async function handlePasswordChange(values: PasswordChangeValues) {
    setFormError(null)
    try {
      await changePassword({
        email: values.email,
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      })
    } catch (error) {
      // 현재 비밀번호가 틀려도 로그인 실패로 센다(AuthService.authenticate). 잠금 안내를 함께 보인다
      if (isApiError(error, 'INVALID_CREDENTIALS')) {
        setFailedOnce(true)
        form.setError(
          'currentPassword',
          { type: 'server', message: `이메일 또는 ${currentLabel}를 확인해 주세요` },
          { shouldFocus: true },
        )
        return
      }
      // 잠긴 뒤에는 "5번 틀리면 잠겨요"가 맞지 않는다. 잠금 안내만 남긴다
      if (isApiError(error, 'ACCOUNT_LOCKED')) {
        setFailedOnce(false)
      }
      if (!applyFieldErrors(error, form.setError, ['email', 'currentPassword', 'newPassword'])) {
        setFormError(error)
      }
      return
    }

    // Note: 서버는 비밀번호를 바꾸면서 이 사용자의 Refresh 토큰을 폐기한다. 로그인해 있던 탭의 토큰은 더 쓸 수 없으니
    // 먼저 지우고 새 비밀번호로 바로 로그인해 이어 간다. 지우지 않으면 아래 로그인이 실패했을 때 로그인 화면이
    // 옛 토큰을 로그인 상태로 보고 돌려보내 다시 로그인할 길이 없다. 변경은 이미 끝났으니 실패로 보이지 않는다.
    clearSession()
    queryClient.clear()
    try {
      await login({ email: values.email, password: values.newPassword })
      toast('비밀번호를 바꿨어요')
      await navigate(returnPathOf(authState), { replace: true })
    } catch {
      toast('비밀번호를 바꿨어요. 새 비밀번호로 로그인해 주세요')
      const state: AuthNavigationState = { from: authState.from, email: values.email }
      await navigate(paths.login, { replace: true, state })
    }
  }

  const loginState: AuthNavigationState = { from: authState.from }

  return (
    <AuthCard
      title="비밀번호 변경"
      description={temporary ? undefined : '현재 비밀번호를 확인한 뒤 새 비밀번호로 바꿔요'}
      footer={
        isSignedIn ? (
          <AuthFooterLine>
            <AuthFooterLink to={returnPathOf(authState)}>바꾸지 않고 돌아가기</AuthFooterLink>
          </AuthFooterLine>
        ) : (
          <AuthFooterLine>
            {temporary ? '다른 계정으로' : '바꾸지 않고'}
            <AuthFooterLink to={paths.login} state={loginState}>
              로그인
            </AuthFooterLink>
          </AuthFooterLine>
        )
      }
    >
      <form noValidate onSubmit={form.handleSubmit(handlePasswordChange)} className="grid gap-5">
        {temporary && formError === null ? (
          <Alert variant="warning">
            <p>임시 비밀번호로는 로그인할 수 없어요</p>
            <p className="mt-1 type-caption">새 비밀번호로 바꾸면 바로 로그인돼요</p>
          </Alert>
        ) : null}
        {formError === null ? null : <AuthErrorNotice error={formError} />}
        <TextField
          label="이메일"
          type="email"
          autoComplete="email"
          inputMode="email"
          placeholder="name@example.com"
          error={errors.email?.message}
          {...form.register('email')}
        />
        <PasswordField
          label={currentLabel}
          autoComplete="current-password"
          error={errors.currentPassword?.message}
          description={
            failedOnce ? `비밀번호를 ${MAX_LOGIN_FAILURES}번 틀리면 계정이 잠겨요` : undefined
          }
          // 칸을 넘는 오류(현재와 같음, 확인과 다름)는 상대 칸을 고쳐도 다시 검사해야 지워진다
          {...form.register('currentPassword', { deps: ['newPassword'] })}
        />
        <PasswordField
          label="새 비밀번호"
          autoComplete="new-password"
          error={errors.newPassword?.message}
          description={errors.newPassword ? undefined : '8자 이상'}
          {...form.register('newPassword', { deps: ['newPasswordConfirm'] })}
        />
        <PasswordField
          label="새 비밀번호 확인"
          autoComplete="new-password"
          error={errors.newPasswordConfirm?.message}
          {...form.register('newPasswordConfirm')}
        />
        <Button type="submit" size="lg" className="mt-1 w-full" loading={isSubmitting}>
          비밀번호 바꾸기
        </Button>
      </form>
    </AuthCard>
  )
}
