import { charmLayout, type CharmData, type CharmText } from '@/pages/charm/charmTicket'

// 부적을 1080×1920 PNG로 만든다. 그림(글자 없는 SVG)을 이미지로 불러 canvas에 그린 뒤 글자를 canvas가 직접 쓴다.
// Note: <img>나 Image로 연 SVG 안에서는 문서의 웹 글꼴을 쓸 수 없다. 글자까지 SVG로 그리면 한글이 기본 글꼴로 찍힌다

export const CHARM_WIDTH = 1080
export const CHARM_HEIGHT = 1920
const FAMILY = '"Pretendard Variable", Pretendard, -apple-system, sans-serif'

const fontOf = (weight: number, size: number) => `${weight} ${size}px ${FAMILY}`

function setSpacing(context: CanvasRenderingContext2D, pixels: number) {
  // letterSpacing이 없는 브라우저는 자간 없이 쓴다
  if ('letterSpacing' in context) context.letterSpacing = `${pixels}px`
}

function drawText(context: CanvasRenderingContext2D, text: CharmText) {
  context.save()
  if (text.rotate) {
    const { deg, cx, cy } = text.rotate
    context.translate(cx, cy)
    context.rotate((deg * Math.PI) / 180)
    context.translate(-cx, -cy)
  }
  context.globalAlpha = text.opacity ?? 1
  context.fillStyle = text.fill
  context.font = fontOf(text.weight, text.size)
  context.textAlign = text.anchor === 'middle' ? 'center' : 'left'
  context.textBaseline = 'alphabetic'
  setSpacing(context, text.spacing ?? 0)
  context.fillText(text.text, text.x, text.y)
  if (text.tail) {
    const width = context.measureText(text.text).width
    context.font = fontOf(text.tail.weight, text.tail.size)
    context.fillStyle = text.tail.fill
    setSpacing(context, 0)
    context.fillText(text.tail.text, text.x + width + text.tail.gap, text.y)
  }
  context.restore()
}

export async function renderCharmPng(data: CharmData): Promise<Blob> {
  const { art, texts } = charmLayout(data)
  const image = new Image()
  image.src = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(art)}`
  await image.decode()

  const canvas = document.createElement('canvas')
  canvas.width = CHARM_WIDTH
  canvas.height = CHARM_HEIGHT
  const context = canvas.getContext('2d')
  if (context === null) throw new Error('canvas 2d를 쓸 수 없다')
  context.drawImage(image, 0, 0, CHARM_WIDTH, CHARM_HEIGHT)

  // Pretendard는 글자 범위별로 나뉜 파일이라 쓰는 글자의 파일을 먼저 받는다. canvas는 글꼴을 스스로 받지 않는다
  await Promise.all(
    texts.flatMap((text) => [
      document.fonts.load(fontOf(text.weight, text.size), text.text),
      text.tail
        ? document.fonts.load(fontOf(text.tail.weight, text.tail.size), text.tail.text)
        : null,
    ]),
  )
  for (const text of texts) drawText(context, text)

  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (blob) => (blob ? resolve(blob) : reject(new Error('PNG를 만들지 못했다'))),
      'image/png',
    )
  })
}
