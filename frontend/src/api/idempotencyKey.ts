// 선점·결제·응모·취소 요청의 Idempotency-Key(UUID, SA 11-1)를 만든다.
//
// 키는 "같은 의도"마다 하나다. 응답을 받지 못해 다시 보내거나 504 뒤 다시 보낼 때는 같은 키를 쓰고,
// 고른 좌석이 바뀌는 것처럼 본문이 바뀌면 새 키를 만든다(같은 키에 다른 본문은 409 IDEMPOTENCY_KEY_REUSED).
//
// Note: crypto.randomUUID()는 HTTPS나 localhost에서만 있다. 배포는 http://<EC2 주소>라서(ADR 0040 4절)
// 어디서나 쓸 수 있는 crypto.getRandomValues()로 UUID v4를 만든다.
export function newIdempotencyKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40 // 버전 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80 // RFC 9562 variant

  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
