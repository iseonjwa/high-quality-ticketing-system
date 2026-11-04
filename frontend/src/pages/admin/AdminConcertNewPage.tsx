import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { ChevronLeft } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { toast } from 'sonner'
import {
  concertFormSchema,
  toConcertInput,
  type ConcertFormInput,
  type ConcertFormValues,
} from '@/admin/formSchemas'
import { createConcert } from '@/api/admin'
import { adminConcertPath, paths } from '@/app/paths'
import { applyFieldErrors } from '@/components/form/applyFieldErrors'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { ErrorNotice } from '@/components/ticketing/ErrorNotice'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'

export function AdminConcertNewPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<unknown>(null)
  const form = useForm<ConcertFormInput, unknown, ConcertFormValues>({
    resolver: zodResolver(concertFormSchema),
    defaultValues: { title: '', description: '' },
  })
  const { errors, isSubmitting } = form.formState

  async function handleCreate(values: ConcertFormValues) {
    setFormError(null)
    try {
      const { concertId } = await createConcert(toConcertInput(values))
      void queryClient.invalidateQueries({ queryKey: ['concerts'] })
      toast('공연을 등록했어요. 회차를 추가해 주세요')
      // 등록 화면은 history에 남기지 않는다. 뒤로 가면 운영 첫 화면이다
      await navigate(adminConcertPath(concertId), { replace: true })
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, ['title', 'description'])) {
        setFormError(error)
      }
    }
  }

  return (
    <main className="mx-auto grid w-full max-w-[560px] gap-5">
      <Link
        to={paths.admin}
        className="inline-flex w-fit items-center gap-1 rounded-sm type-body-sm text-gray-500 outline-none hover:text-gray-900 focus-visible:ring-2 focus-visible:ring-ring"
      >
        <ChevronLeft aria-hidden="true" className="size-4" />
        운영
      </Link>
      <h1 className="type-title text-gray-900">공연 등록</h1>
      <Card className="px-5 @min-[40rem]:px-6">
        <form noValidate onSubmit={form.handleSubmit(handleCreate)} className="grid gap-5">
          {formError === null ? null : <ErrorNotice error={formError} />}
          <TextField
            label="공연 제목"
            error={errors.title?.message}
            description={errors.title ? undefined : '200자까지'}
            {...form.register('title')}
          />
          <TextAreaField
            label="공연 설명 (선택)"
            error={errors.description?.message}
            {...form.register('description')}
          />
          <Button type="submit" size="lg" className="w-full" loading={isSubmitting}>
            등록하기
          </Button>
        </form>
      </Card>
    </main>
  )
}
