import { useMemo } from 'react'
import { encode } from 'uqr'

// QR을 SVG 경로 하나로 그린다. 화면에 띄운 QR은 찢기거나 가려질 일이 적어 오류 정정을 L(7%)로 두고 칸을 키운다.
// 다크 모드에서도 폰 카메라가 읽도록 색을 흰 바탕·검은 칸으로 고정한다
export function QrCode({
  value,
  label,
  className,
}: {
  value: string
  label: string
  className?: string
}) {
  const { size, path } = useMemo(() => {
    const qr = encode(value, { ecc: 'L', border: 2 })
    let d = ''
    qr.data.forEach((row, y) => {
      row.forEach((dark, x) => {
        if (dark) d += `M${x} ${y}h1v1h-1z`
      })
    })
    return { size: qr.size, path: d }
  }, [value])
  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`0 0 ${size} ${size}`}
      shapeRendering="crispEdges"
      data-slot="qr-code"
      className={className}
    >
      <rect width={size} height={size} fill="#FFFFFF" />
      <path d={path} fill="#14161E" />
    </svg>
  )
}
