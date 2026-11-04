import { Check, Copy } from 'lucide-react'
import { useEffect, useId, useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

const COPIED_FEEDBACK_MS = 1500

// 읽기 전용 값과 복사 버튼(추첨 응모 주소, 임시 비밀번호). 칸을 누르면 값 전체를 고른다.
// Note: navigator.clipboard는 보안 출처(https·localhost)에서만 있다. 배포 화면은 http라 복사 버튼을 숨기고
// 칸을 골라 직접 복사하게 한다(RequestLogPanel의 traceId 복사와 같은 처리).
export function CopyField({
  label,
  value,
  monospace = false,
}: {
  label: string
  value: string
  monospace?: boolean
}) {
  const inputId = useId()
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    if (!copied) {
      return
    }
    const timer = window.setTimeout(() => setCopied(false), COPIED_FEEDBACK_MS)
    return () => window.clearTimeout(timer)
  }, [copied])

  async function copy() {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
    } catch {
      toast.error('복사하지 못했어요. 칸을 눌러 직접 복사해 주세요')
    }
  }

  return (
    <div className="grid gap-2">
      <Label htmlFor={inputId} className="text-gray-900">
        {label}
      </Label>
      <div className="flex gap-2">
        <Input
          id={inputId}
          readOnly
          value={value}
          onFocus={(event) => event.currentTarget.select()}
          className={monospace ? 'font-mono tracking-wider' : undefined}
        />
        {navigator.clipboard === undefined ? null : (
          <Button type="button" variant="outline" className="shrink-0" onClick={() => void copy()}>
            {copied ? <Check aria-hidden="true" /> : <Copy aria-hidden="true" />}
            {copied ? '복사함' : '복사'}
          </Button>
        )}
      </div>
    </div>
  )
}
