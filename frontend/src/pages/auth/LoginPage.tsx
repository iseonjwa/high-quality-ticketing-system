import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useId, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { isApiError } from '@/api/apiError'
import { login } from '@/api/auth'
import { describeError, ERROR_NOTICES } from '@/api/errorMessages'
import { paths } from '@/app/paths'
import { loginSchema, MAX_LOGIN_FAILURES, type LoginValues } from '@/auth/formSchemas'
import { readAuthState, returnPathOf, type AuthNavigationState } from '@/auth/navigation'
import { quickLoginAccounts, type QuickLoginAccount } from '@/auth/quickLogin'
import { useIsSessionExpired, useIsSignedIn } from '@/auth/session'
import { applyFieldErrors } from '@/components/form/applyFieldErrors'
import { PasswordField } from '@/components/form/PasswordField'
import { TextField } from '@/components/form/TextField'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { AuthCard, AuthFooterLine, AuthFooterLink } from '@/pages/auth/AuthCard'
import { AuthErrorNotice } from '@/pages/auth/AuthErrorNotice'

export function LoginPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const isSignedIn = useIsSignedIn()
  const sessionExpired = useIsSessionExpired()
  const authState = readAuthState(location.state)
  const returnPath = returnPathOf(authState)
  const [failedOnce, setFailedOnce] = useState(false)
  const [formError, setFormError] = useState<unknown>(null)
  const form = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: authState.email ?? '', password: '' },
  })
  const { errors, isSubmitting, submitCount } = form.formState
  // DEV를 여기서도 직접 검사해야 운영 빌드에서 아래 버튼 묶음이 통째로 빠진다
  const quickAccounts = import.meta.env.DEV ? quickLoginAccounts() : []
  const quickLoginLabelId = useId()
  const [quickRole, setQuickRole] = useState<QuickLoginAccount['role'] | null>(null)

  // Note: 로그인에 성공하면 토큰이 저장되면서 이 화면이 다시 그려지고 여기서 돌아갈 경로로 보낸다.
  // 성공 처리에서 따로 이동하지 않아 이동이 한 번만 일어난다. 이미 로그인한 채로 들어와도 같은 길이다.
  if (isSignedIn) {
    return <Navigate to={returnPath} replace />
  }

  async function handleLogin(values: LoginValues) {
    setFormError(null)
    // 세션이 끝난 뒤 다른 계정으로 로그인해도 이전 사용자의 조회 결과가 보이지 않게 한다
    queryClient.clear()
    try {
      await login(values)
    } catch (error) {
      if (isApiError(error, 'INVALID_CREDENTIALS')) {
        setFailedOnce(true)
        form.setError(
          'password',
          { type: 'server', message: describeError(error).message },
          { shouldFocus: true },
        )
        return
      }
      if (isApiError(error, 'PASSWORD_CHANGE_REQUIRED')) {
        const state: AuthNavigationState = {
          from: authState.from,
          email: values.email,
          temporaryPassword: true,
        }
        // 바꾼 뒤 뒤로 가기가 로그인 화면에 걸려 다시 돌아오지 않게 이 기록을 바꿔 끼운다
        await navigate(paths.passwordChange, { replace: true, state })
        return
      }
      // 잠긴 뒤에는 "5번 틀리면 잠겨요"가 맞지 않는다. 잠금 안내만 남긴다
      if (isApiError(error, 'ACCOUNT_LOCKED')) {
        setFailedOnce(false)
      }
      if (!applyFieldErrors(error, form.setError, ['email', 'password'])) {
        setFormError(error)
      }
    }
  }

  // 빠른 로그인은 칸에 계정을 채운 뒤 같은 로그인 처리를 탄다. 무엇으로 로그인하는지 칸에 보이고,
  // 잠금·임시 비밀번호 같은 실패도 직접 입력했을 때와 같게 안내한다.
  async function handleQuickLogin(account: QuickLoginAccount) {
    if (isSubmitting) {
      return
    }
    setQuickRole(account.role)
    setFailedOnce(false)
    form.reset({ email: account.email, password: account.password })
    try {
      await form.handleSubmit(handleLogin)()
    } finally {
      setQuickRole(null)
    }
  }

  const nextState: AuthNavigationState = { from: authState.from }

  return (
    <AuthCard
      title="로그인"
      description={
        returnPath === paths.home
          ? '예매하려면 로그인해 주세요'
          : '로그인하면 보던 화면으로 돌아가요'
      }
      footer={
        <>
          <AuthFooterLine>
            아직 회원이 아니에요?
            <AuthFooterLink to={paths.signup} state={nextState}>
              회원가입
            </AuthFooterLink>
          </AuthFooterLine>
          <AuthFooterLine>
            비밀번호를 바꾸려면
            <AuthFooterLink to={paths.passwordChange} state={nextState}>
              비밀번호 변경
            </AuthFooterLink>
          </AuthFooterLine>
        </>
      }
    >
      <form noValidate onSubmit={form.handleSubmit(handleLogin)} className="grid gap-5">
        {sessionExpired && submitCount === 0 ? (
          <Alert variant="warning">{ERROR_NOTICES.TOKEN_EXPIRED.message}</Alert>
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
          label="비밀번호"
          autoComplete="current-password"
          error={errors.password?.message}
          description={
            failedOnce ? `비밀번호를 ${MAX_LOGIN_FAILURES}번 틀리면 계정이 잠겨요` : undefined
          }
          {...form.register('password')}
        />
        <Button
          type="submit"
          size="lg"
          className="mt-1 w-full"
          loading={isSubmitting && quickRole === null}
        >
          로그인
        </Button>
      </form>
      {import.meta.env.DEV && quickAccounts.length > 0 ? (
        <div role="group" aria-labelledby={quickLoginLabelId} className="mt-6 grid gap-3">
          <div className="flex items-center gap-3 type-caption text-gray-400">
            <span aria-hidden="true" className="h-px flex-1 bg-gray-100" />
            <span id={quickLoginLabelId}>개발 서버 전용 빠른 로그인</span>
            <span aria-hidden="true" className="h-px flex-1 bg-gray-100" />
          </div>
          <div className="grid gap-2 sm:grid-cols-2">
            {quickAccounts.map((account) => (
              <Button
                key={account.role}
                type="button"
                variant="outline"
                size="sm"
                loading={quickRole === account.role}
                onClick={() => void handleQuickLogin(account)}
              >
                {account.label}
              </Button>
            ))}
          </div>
        </div>
      ) : null}
    </AuthCard>
  )
}
