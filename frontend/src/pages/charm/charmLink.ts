import { z } from 'zod'
import { paths } from '@/app/paths'

// 발권 이벤트 부적(ISE-257)의 QR 주소. 부적에 필요한 값을 주소 조각(# 뒤)에 담는다.
// 조각은 서버로 보내지지 않아 새 API가 필요 없고, QR을 찍은 폰은 로그인 없이 이 값만으로 부적을 그린다.
// Note: 주소를 고치면 다른 닉네임으로도 부적을 만들 수 있다. 재미용 이미지라 받아들였다(ISE-257 "정할 것")

// 부적에 보이는 닉네임 길이. 가입 때는 30자까지 받지만 부적에는 앞 8자만 줄임표 없이 보인다
export const NICKNAME_SHOWN = 8

// 키를 한 글자로 줄여 QR을 작게 한다. QR이 빽빽하면 화면에 띄운 QR을 폰이 잘 못 읽는다
const charmPayloadSchema = z.object({
  v: z.literal(1),
  // 예매 ID와 그 예매 안의 티켓 순서. 펫·문장·도장은 이 둘로 정해진다(charmTicket.ts pickCharm)
  r: z.number().int().positive(),
  i: z.number().int().min(0).max(9),
  // 닉네임 앞 8자
  n: z.string().max(NICKNAME_SHOWN * 2),
  // 공연 제목과 시작 시각. 주소로 연 예매 상세에는 없어서(화면 이동 값) 빠질 수 있다
  t: z.string().max(120).optional(),
  s: z
    .string()
    .refine((value) => !Number.isNaN(Date.parse(value)))
    .optional(),
  // 좌석 표시와 티켓 번호
  z: z.string().max(80),
  k: z.string().max(20),
})
export type CharmPayload = z.infer<typeof charmPayloadSchema>

function toBase64Url(text: string): string {
  let binary = ''
  for (const byte of new TextEncoder().encode(text)) binary += String.fromCharCode(byte)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function fromBase64Url(encoded: string): string {
  const base64 = encoded.replace(/-/g, '+').replace(/_/g, '/')
  const binary = atob(base64 + '='.repeat((4 - (base64.length % 4)) % 4))
  return new TextDecoder().decode(Uint8Array.from(binary, (char) => char.charCodeAt(0)))
}

export function charmHash(payload: Omit<CharmPayload, 'v' | 'n'> & { n: string }): string {
  const body: CharmPayload = {
    ...payload,
    v: 1,
    n: [...payload.n].slice(0, NICKNAME_SHOWN).join(''),
  }
  return `#${toBase64Url(JSON.stringify(body))}`
}

// QR은 다른 기기(폰)가 열므로 오리진을 포함한 절대 주소다
export function charmUrl(origin: string, hash: string): string {
  return `${origin}${paths.charm}${hash}`
}

// 조각이 없거나 깨졌거나 모양이 다르면 null
export function readCharmPayload(hash: string): CharmPayload | null {
  const encoded = hash.replace(/^#/, '')
  if (encoded === '') return null
  try {
    const parsed = charmPayloadSchema.safeParse(JSON.parse(fromBase64Url(encoded)))
    return parsed.success ? parsed.data : null
  } catch {
    return null
  }
}
