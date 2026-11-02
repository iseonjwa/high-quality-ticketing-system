import { describe, expect, it } from 'vitest'
import { loginSchema, passwordChangeSchema, signupSchema } from '@/auth/formSchemas'

function messages(result: {
  success: boolean
  error?: { issues: { path: PropertyKey[]; message: string }[] }
}) {
  return (result.error?.issues ?? []).map((issue) => `${issue.path.join('.')}: ${issue.message}`)
}

const validSignup = {
  email: 'new@iseonjwa.dev',
  password: 'password1234',
  passwordConfirm: 'password1234',
  nickname: '새 회원',
}

describe('signupSchema', () => {
  it('이메일과 닉네임의 앞뒤 공백을 지운다. 비밀번호는 그대로 둔다', () => {
    const parsed = signupSchema.parse({
      ...validSignup,
      email: '  new@iseonjwa.dev ',
      password: ' pass word ',
      passwordConfirm: ' pass word ',
      nickname: ' 새 회원 ',
    })

    expect(parsed).toMatchObject({
      email: 'new@iseonjwa.dev',
      password: ' pass word ',
      nickname: '새 회원',
    })
  })

  // BCrypt 한도 때문에 서버는 UTF-8 72바이트까지 받는다. 한글은 한 글자가 3바이트다
  it('비밀번호는 글자 수가 아니라 바이트로 72까지다', () => {
    const korean24 = '가'.repeat(24)
    const korean25 = '가'.repeat(25)

    expect(
      signupSchema.safeParse({ ...validSignup, password: korean24, passwordConfirm: korean24 })
        .success,
    ).toBe(true)
    expect(
      messages(
        signupSchema.safeParse({ ...validSignup, password: korean25, passwordConfirm: korean25 }),
      ),
    ).toEqual(['password: 너무 길어요. 영문은 72자, 한글은 24자까지 쓸 수 있어요'])
  })

  it('8자보다 짧은 비밀번호, 서로 다른 확인, 31자 닉네임을 각 칸 문구로 알린다', () => {
    const result = signupSchema.safeParse({
      email: 'new@iseonjwa.dev',
      password: 'short',
      passwordConfirm: 'shorter',
      nickname: '가'.repeat(31),
    })

    expect(messages(result)).toEqual(
      expect.arrayContaining([
        'password: 8자 이상 입력해 주세요',
        'nickname: 닉네임은 30자까지 쓸 수 있어요',
      ]),
    )
  })

  it('비밀번호 확인이 다르면 확인 칸에 알린다', () => {
    expect(
      messages(signupSchema.safeParse({ ...validSignup, passwordConfirm: 'password12345' })),
    ).toEqual(['passwordConfirm: 비밀번호가 서로 달라요'])
  })
})

describe('loginSchema', () => {
  it('비어 있으면 무엇을 입력할지 알린다', () => {
    expect(messages(loginSchema.safeParse({ email: ' ', password: '' }))).toEqual([
      'email: 이메일을 입력해 주세요',
      'password: 비밀번호를 입력해 주세요',
    ])
  })

  it('서버(@Email)가 받는 이메일은 로그인에서 막지 않는다. 가입은 더 엄격하다', () => {
    for (const email of ['admin@localhost', 'a!b@example.com', '홍길동@example.com']) {
      expect(loginSchema.safeParse({ email, password: 'x' }).success).toBe(true)
    }
    expect(messages(signupSchema.safeParse({ ...validSignup, email: 'admin@localhost' }))).toEqual([
      'email: 이메일 형식을 확인해 주세요',
    ])
  })

  it('공백만 있는 비밀번호는 비어 있는 것으로 본다(서버 @NotBlank)', () => {
    expect(
      messages(loginSchema.safeParse({ email: 'demo@iseonjwa.dev', password: '   ' })),
    ).toEqual(['password: 비밀번호를 입력해 주세요'])
  })

  it('형식이 틀린 이메일을 알린다', () => {
    expect(messages(loginSchema.safeParse({ email: 'demo', password: 'x' }))).toEqual([
      'email: 이메일 형식을 확인해 주세요',
    ])
  })
})

describe('passwordChangeSchema', () => {
  it('현재 비밀번호와 같은 새 비밀번호는 새 비밀번호 칸에 알린다', () => {
    const result = passwordChangeSchema.safeParse({
      email: 'demo@iseonjwa.dev',
      currentPassword: 'password1234',
      newPassword: 'password1234',
      newPasswordConfirm: 'password1234',
    })

    expect(messages(result)).toEqual(['newPassword: 현재 비밀번호와 다른 비밀번호를 입력해 주세요'])
  })
})
