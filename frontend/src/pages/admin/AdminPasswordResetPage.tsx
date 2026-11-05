import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery } from '@tanstack/react-query'
import { ChevronLeft } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router'
import { z } from 'zod'
import { resetUserPassword } from '@/api/adminUsers'
import { authQueryKeys, getMe } from '@/api/auth'
import { isApiError, isOutcomeUnknown } from '@/api/apiError'
import { paths } from '@/app/paths'
import { TextField } from '@/components/form/TextField'
import { CopyField } from '@/components/ticketing/CopyField'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogTitle,
} from '@/components/ui/dialog'

const userIdSchema = z.object({
  userId: z
    .string()
    .trim()
    .min(1, '회원 번호를 입력해 주세요')
    .regex(/^\d+$/, '회원 번호는 숫자로 입력해 주세요')
    .transform(Number)
    .refine((value) => value > 0 && Number.isSafeInteger(value), '회원 번호를 확인해 주세요'),
})
type UserIdInput = z.input<typeof userIdSchema>
type UserIdValues = z.output<typeof userIdSchema>

// 회원 비밀번호 초기화(ISE-124). 회원을 찾는 API가 없어 회원 번호로 부른다(회원은 내 정보에서 번호를 본다).
// 임시 비밀번호는 응답으로 한 번만 온다. 화면 상태에만 두고 저장하지 않는다(요청 로그에도 본문은 남지 않는다).
export function AdminPasswordResetPage() {
  const me = useQuery({ queryKey: authQueryKeys.me, queryFn: getMe })
  // 대화상자에 보일 회원 번호. 닫히는 동안(퇴장 애니메이션)에도 제목이 비지 않게 열림과 나눈다
  const [target, setTarget] = useState<number | null>(null)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [result, setResult] = useState<{ userId: number; temporaryPassword: string } | null>(null)
  const submitRef = useRef<HTMLButtonElement>(null)
  const resultTitleRef = useRef<HTMLHeadingElement>(null)
  // [다른 회원 초기화]로 결과 카드가 사라지면 회원 번호 칸으로 초점을 옮긴다
  const focusFieldNext = useRef(false)
  const form = useForm<UserIdInput, unknown, UserIdValues>({
    resolver: zodResolver(userIdSchema),
    defaultValues: { userId: '' },
  })
  const reset = useMutation({
    mutationFn: (userId: number) => resetUserPassword(userId),
    // 응답(임시 비밀번호)을 화면을 떠난 뒤 캐시에 남기지 않는다
    gcTime: 0,
    onSuccess: ({ temporaryPassword }, userId) => {
      setDialogOpen(false)
      setResult({ userId, temporaryPassword })
      form.reset()
    },
    onError: (error) => {
      if (isApiError(error, 'NOT_FOUND')) {
        setDialogOpen(false)
        form.setError('userId', { message: '이 번호의 회원이 없어요' }, { shouldFocus: true })
      }
    },
  })

  useEffect(() => {
    if (result === null && focusFieldNext.current) {
      focusFieldNext.current = false
      form.setFocus('userId')
    }
  }, [result, form])

  return (
    <main className="mx-auto grid w-full max-w-[560px] gap-6">
      <Link
        to={paths.admin}
        className="inline-flex w-fit items-center gap-1 rounded-sm type-body-sm text-gray-500 outline-none hover:text-gray-900 focus-visible:ring-2 focus-visible:ring-ring"
      >
        <ChevronLeft aria-hidden="true" className="size-4" />
        운영
      </Link>
      <div className="grid gap-1">
        <h1 className="type-title text-gray-900">비밀번호 초기화</h1>
        <p className="type-body-sm text-gray-500">
          임시 비밀번호를 만들어 회원에게 전해요. 회원은 그 비밀번호로 로그인한 뒤 새 비밀번호로
          바꿔야 해요.
        </p>
      </div>

      {result !== null ? (
        <Card className="gap-4 px-5 @min-[40rem]:px-6">
          <h2
            ref={resultTitleRef}
            tabIndex={-1}
            className="type-heading text-gray-900 outline-none"
          >
            회원 #{result.userId}의 임시 비밀번호
          </h2>
          <Alert variant="warning">
            이 화면을 떠나면 다시 볼 수 없어요. 회원에게 안전한 방법으로 전해 주세요
          </Alert>
          <CopyField label="임시 비밀번호" value={result.temporaryPassword} monospace />
          <ul className="grid list-disc gap-1 pl-5 type-body-sm text-gray-700">
            <li>이전 비밀번호로는 더 로그인할 수 없어요.</li>
            <li>이미 로그인한 기기는 30분 안에 로그아웃돼요.</li>
            <li>로그인 잠금도 풀렸어요.</li>
          </ul>
          <Button
            variant="outline"
            className="w-fit"
            onClick={() => {
              // 결과 카드와 함께 받은 응답도 지운다
              focusFieldNext.current = true
              reset.reset()
              setResult(null)
            }}
          >
            다른 회원 초기화
          </Button>
        </Card>
      ) : (
        <Card className="px-5 @min-[40rem]:px-6">
          <form
            noValidate
            onSubmit={form.handleSubmit((values) => {
              // Note: 서버는 자기 계정도 막지 않는다. 초기화하면 내 Refresh 토큰이 폐기돼 곧 로그아웃되고, 결과 카드와
              // 함께 임시 비밀번호도 사라져 다른 운영자가 없으면 들어올 수 없다. 내 비밀번호는 비밀번호 변경으로 바꾼다
              if (me.data !== undefined && values.userId === me.data.userId) {
                form.setError(
                  'userId',
                  {
                    message: '내 계정이에요. 내 비밀번호는 내 정보의 비밀번호 변경에서 바꿔 주세요',
                  },
                  { shouldFocus: true },
                )
                return
              }
              reset.reset()
              setTarget(values.userId)
              setDialogOpen(true)
            })}
            className="grid gap-5"
          >
            <TextField
              label="회원 번호"
              inputMode="numeric"
              error={form.formState.errors.userId?.message}
              description="회원이 내 정보에서 본 번호예요. 이메일로 찾는 기능은 아직 없어요"
              {...form.register('userId')}
            />
            <Button ref={submitRef} type="submit" className="w-full @min-[40rem]:w-fit">
              임시 비밀번호 만들기
            </Button>
          </form>
        </Card>
      )}

      <Dialog
        open={dialogOpen}
        onOpenChange={(open) => {
          if (!open && !reset.isPending) {
            setDialogOpen(false)
          }
        }}
      >
        <DialogContent
          showCloseButton={!reset.isPending}
          onCloseAutoFocus={(event) => {
            // 트리거 없이 연 대화상자라 초점을 돌려줄 곳을 직접 정한다. 성공하면 결과 제목으로,
            // 없는 회원이면 회원 번호 칸(오류 표시)으로, 취소하면 만들기 버튼으로 간다
            event.preventDefault()
            if (resultTitleRef.current) {
              resultTitleRef.current.focus()
            } else if (form.formState.errors.userId) {
              form.setFocus('userId')
            } else {
              submitRef.current?.focus()
            }
          }}
        >
          <DialogTitle className="type-heading text-gray-900">
            회원 #{target}의 비밀번호를 초기화할까요?
          </DialogTitle>
          <DialogDescription className="type-body-sm text-gray-500">
            지금 비밀번호로는 로그인할 수 없게 되고, 임시 비밀번호로 로그인한 뒤 새로 정해야 해요.
          </DialogDescription>
          {/* 실패는 대화상자 안에 둔다. 폼에 두면 모달 뒤에 가려져 읽히지 않는다. 없는 회원(404)은 닫고 칸에 알린다 */}
          {reset.isError && !isApiError(reset.error, 'NOT_FOUND') ? (
            isOutcomeUnknown(reset.error) ? (
              <Alert variant="warning">
                초기화됐는지 확인하지 못했어요. 다시 초기화하면 새 임시 비밀번호가 나와요
              </Alert>
            ) : (
              <ErrorNotice error={reset.error} />
            )
          ) : null}
          <DialogFooter>
            <Button
              variant="outline"
              disabled={reset.isPending}
              onClick={() => setDialogOpen(false)}
            >
              취소
            </Button>
            <Button
              variant="destructive"
              loading={reset.isPending}
              onClick={() => {
                if (target !== null) {
                  reset.mutate(target)
                }
              }}
            >
              초기화
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </main>
  )
}
