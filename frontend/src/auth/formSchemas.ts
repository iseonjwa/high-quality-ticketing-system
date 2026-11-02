import { z } from 'zod'

// user-service의 검증 규칙(AuthDtos, AuthService)을 화면에서 먼저 확인해 요청 전에 입력 칸 아래로 안내한다.
// 서버도 같은 규칙으로 다시 확인하므로 여기가 정본은 아니다. 규칙이 바뀌면 둘을 함께 고친다.

// config-repo/user-service.yml `auth.max-login-failures`. 안내 문구에만 쓴다
export const MAX_LOGIN_FAILURES = 5

const MIN_PASSWORD_LENGTH = 8
// Note: BCrypt는 72바이트까지만 비교해서 서버가 UTF-8로 72바이트를 넘는 비밀번호를 거절한다
// (AuthService.validatePasswordBytes). 글자 수가 아니라 바이트라 한글은 24자까지다.
const MAX_PASSWORD_BYTES = 72
const MAX_EMAIL_LENGTH = 255
const MAX_NICKNAME_LENGTH = 30

const encoder = new TextEncoder()

export function utf8Length(value: string): number {
  return encoder.encode(value).length
}

const PASSWORD_TOO_LONG = '너무 길어요. 영문은 72자, 한글은 24자까지 쓸 수 있어요'

// 서버는 저장할 때 앞뒤 공백을 지우고 소문자로 바꾸지만, 형식 검사(@Email)는 공백이 붙은 값 그대로 한다.
// 그래서 앞뒤 공백은 보내기 전에 지운다. 비밀번호는 공백도 비밀번호의 일부라 지우지 않는다.
function emailWith(format: z.ZodType<string, string>) {
  return z
    .string()
    .trim()
    .min(1, '이메일을 입력해 주세요')
    .max(MAX_EMAIL_LENGTH, '이메일이 너무 길어요')
    .pipe(format)
}

const EMAIL_FORMAT_MESSAGE = '이메일 형식을 확인해 주세요'
// 가입은 오타를 줄이려고 서버(@Email)보다 엄격하게 본다(도메인에 점이 있어야 한다)
const newEmail = emailWith(z.email(EMAIL_FORMAT_MESSAGE))
// Note: 로그인·비밀번호 변경은 서버만큼 느슨하게 본다. 서버는 `a!b@example.com`이나 `admin@localhost`도
// 받아서, 여기서 가입 기준으로 막으면 API로 만든 그런 계정은 화면에서 로그인할 수 없다.
const existingEmail = emailWith(z.string().regex(/^[^\s@]+@[^\s@]+$/, EMAIL_FORMAT_MESSAGE))

// 서버는 공백만 있는 비밀번호를 @NotBlank로 거절한다. 빈 칸과 같은 안내를 보인다
function enteredPassword(requiredMessage: string) {
  return z
    .string()
    .refine((value) => value.trim() !== '', requiredMessage)
    .refine((value) => utf8Length(value) <= MAX_PASSWORD_BYTES, PASSWORD_TOO_LONG)
}

function newPassword(requiredMessage: string) {
  return enteredPassword(requiredMessage).refine(
    (value) => value.length >= MIN_PASSWORD_LENGTH,
    `${MIN_PASSWORD_LENGTH}자 이상 입력해 주세요`,
  )
}

export const loginSchema = z.object({
  email: existingEmail,
  password: enteredPassword('비밀번호를 입력해 주세요'),
})
export type LoginValues = z.infer<typeof loginSchema>

export const signupSchema = z
  .object({
    email: newEmail,
    password: newPassword('비밀번호를 입력해 주세요'),
    passwordConfirm: z.string().min(1, '비밀번호를 한 번 더 입력해 주세요'),
    nickname: z
      .string()
      .trim()
      .min(1, '닉네임을 입력해 주세요')
      .max(MAX_NICKNAME_LENGTH, `닉네임은 ${MAX_NICKNAME_LENGTH}자까지 쓸 수 있어요`),
  })
  .refine((values) => values.password === values.passwordConfirm, {
    path: ['passwordConfirm'],
    message: '비밀번호가 서로 달라요',
  })
export type SignupInput = z.input<typeof signupSchema>
export type SignupValues = z.output<typeof signupSchema>

export const passwordChangeSchema = z
  .object({
    email: existingEmail,
    currentPassword: enteredPassword('비밀번호를 입력해 주세요'),
    newPassword: newPassword('새 비밀번호를 입력해 주세요'),
    newPasswordConfirm: z.string().min(1, '새 비밀번호를 한 번 더 입력해 주세요'),
  })
  // 서버는 같은 비밀번호로 바꾸는 요청을 칸 구분 없는 400으로 거절한다. 어느 칸 문제인지 여기서 알려 준다
  .refine((values) => values.newPassword !== values.currentPassword, {
    path: ['newPassword'],
    message: '현재 비밀번호와 다른 비밀번호를 입력해 주세요',
  })
  .refine((values) => values.newPassword === values.newPasswordConfirm, {
    path: ['newPasswordConfirm'],
    message: '새 비밀번호가 서로 달라요',
  })
export type PasswordChangeInput = z.input<typeof passwordChangeSchema>
export type PasswordChangeValues = z.output<typeof passwordChangeSchema>
