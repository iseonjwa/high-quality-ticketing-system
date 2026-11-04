import { Eye, EyeOff } from 'lucide-react'
import { useState } from 'react'
import { TextField, type TextFieldProps } from '@/components/form/TextField'

// 비밀번호 칸. 오른쪽 버튼으로 입력한 글자를 보이거나 가린다.
// 버튼 이름은 그대로 두고 눌림 상태(aria-pressed)로 지금 보이는지 알린다(토글 버튼 패턴).
export function PasswordField(props: Omit<TextFieldProps, 'type' | 'trailing'>) {
  const [visible, setVisible] = useState(false)
  const Icon = visible ? EyeOff : Eye

  return (
    <TextField
      {...props}
      type={visible ? 'text' : 'password'}
      trailing={
        <button
          type="button"
          aria-label={`${props.label} 보기`}
          aria-pressed={visible}
          onClick={() => setVisible((current) => !current)}
          className="absolute inset-y-0 right-0 flex w-11 cursor-pointer items-center justify-center rounded-r-md text-gray-400 outline-none hover:text-gray-700 focus-visible:ring-2 focus-visible:ring-ring"
        >
          <Icon className="size-[18px]" aria-hidden="true" />
        </button>
      }
    />
  )
}
