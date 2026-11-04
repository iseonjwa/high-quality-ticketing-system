import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { toast } from 'sonner'
import { isApiError } from '@/api/apiError'
import { login, signup } from '@/api/auth'
import { describeError } from '@/api/errorMessages'
import { paths } from '@/app/paths'
import { signupSchema, type SignupInput, type SignupValues } from '@/auth/formSchemas'
import { readAuthState, returnPathOf, type AuthNavigationState } from '@/auth/navigation'
import { useIsSignedIn } from '@/auth/session'
import { applyFieldErrors } from '@/components/form/applyFieldErrors'
import { PasswordField } from '@/components/form/PasswordField'
import { TextField } from '@/components/form/TextField'
import { Button } from '@/components/ui/button'
import { AuthCard, AuthFooterLine, AuthFooterLink } from '@/pages/auth/AuthCard'
import { AuthErrorNotice } from '@/pages/auth/AuthErrorNotice'

const EMAIL_DUPLICATED = 'duplicated'

export function SignupPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const isSignedIn = useIsSignedIn()
  const authState = readAuthState(location.state)
  const [formError, setFormError] = useState<unknown>(null)
  const form = useForm<SignupInput, unknown, SignupValues>({
    resolver: zodResolver(signupSchema),
    defaultValues: { email: '', password: '', passwordConfirm: '', nickname: '' },
  })
  const { errors, isSubmitting } = form.formState

  // 가입 뒤 자동 로그인에 성공하면 토큰이 저장되면서 여기서 돌아갈 경로로 보낸다(로그인 화면과 같은 길)
  if (isSignedIn) {
    return <Navigate to={returnPathOf(authState)} replace />
  }

  async function handleSignup(values: SignupValues) {
    setFormError(null)
    try {
      await signup({ email: values.email, password: values.password, nickname: values.nickname })
    } catch (error) {
      if (isApiError(error, 'EMAIL_DUPLICATED')) {
        form.setError(
          'email',
          { type: EMAIL_DUPLICATED, message: describeError(error).message },
          { shouldFocus: true },
        )
        return
      }
      if (!applyFieldErrors(error, form.setError, ['email', 'password', 'nickname'])) {
        setFormError(error)
      }
      return
    }

    // 가입 응답에는 토큰이 없다. 같은 값으로 바로 로그인해 한 번 더 입력하지 않게 한다.
    // 가입은 이미 끝났으므로 로그인이 실패해도 가입 실패로 보이지 않고 로그인 화면으로 넘긴다.
    queryClient.clear()
    try {
      await login({ email: values.email, password: values.password })
      toast('가입을 마쳤어요')
    } catch {
      toast('가입을 마쳤어요. 로그인해 주세요')
      const state: AuthNavigationState = { from: authState.from, email: values.email }
      await navigate(paths.login, { replace: true, state })
    }
  }

  const duplicated = errors.email?.type === EMAIL_DUPLICATED
  const loginState: AuthNavigationState = {
    from: authState.from,
    email: duplicated ? form.getValues('email').trim() : undefined,
  }

  return (
    <AuthCard
      title="회원가입"
      description="이메일과 비밀번호만 있으면 바로 예매할 수 있어요"
      footer={
        <AuthFooterLine>
          이미 회원이에요?
          <AuthFooterLink to={paths.login} state={loginState}>
            로그인
          </AuthFooterLink>
        </AuthFooterLine>
      }
    >
      <form noValidate onSubmit={form.handleSubmit(handleSignup)} className="grid gap-5">
        {formError === null ? null : <AuthErrorNotice error={formError} />}
        <TextField
          label="이메일"
          type="email"
          autoComplete="email"
          inputMode="email"
          placeholder="name@example.com"
          error={errors.email?.message}
          description={
            duplicated ? (
              <AuthFooterLink to={paths.login} state={loginState}>
                이 이메일로 로그인하기
              </AuthFooterLink>
            ) : undefined
          }
          {...form.register('email')}
        />
        <PasswordField
          label="비밀번호"
          autoComplete="new-password"
          error={errors.password?.message}
          description={errors.password ? undefined : '8자 이상'}
          // 확인 칸의 "서로 달라요"는 이 칸을 고쳐도 다시 검사해야 지워진다
          {...form.register('password', { deps: ['passwordConfirm'] })}
        />
        <PasswordField
          label="비밀번호 확인"
          autoComplete="new-password"
          error={errors.passwordConfirm?.message}
          {...form.register('passwordConfirm')}
        />
        <TextField
          label="닉네임"
          autoComplete="nickname"
          error={errors.nickname?.message}
          description={errors.nickname ? undefined : '30자까지'}
          {...form.register('nickname')}
        />
        <Button type="submit" size="lg" className="mt-1 w-full" loading={isSubmitting}>
          가입하기
        </Button>
      </form>
    </AuthCard>
  )
}
