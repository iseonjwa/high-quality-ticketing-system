// 공연 그림(art.html)을 headless Chrome으로 그려 WebP로 저장한다. 그림을 바꿀 때만 손으로 돌린다(빌드에 넣지 않는다).
// 준비: Chrome을 원격 디버깅으로 띄우고(--headless=new --remote-debugging-port=<포트>), cwebp(libwebp)를 설치한다.
// 사용: node tools/concert-art/render.mjs <포트> [출력 폴더, 기본 src/assets/concert-art]
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const [port, outArg] = process.argv.slice(2)
if (!port) {
  console.error('사용: node tools/concert-art/render.mjs <Chrome 원격 디버깅 포트> [출력 폴더]')
  process.exit(1)
}
const outDir = resolve(outArg ?? join(here, '../../src/assets/concert-art'))
mkdirSync(outDir, { recursive: true })
const work = mkdtempSync(join(tmpdir(), 'concert-art-'))

// 그림 개수는 art.html의 ARTS 길이와 같다
const ART_COUNT = 8
// 포스터는 카드(최대 약 260px)를 2배 화면에서도 선명하게, 배너는 1440px 화면 폭을 덮게 둔다
const SIZES = [
  { kind: 'poster', render: [600, 900], out: [480, 720], quality: 74 },
  { kind: 'wide', render: [1920, 800], out: [1600, 667], quality: 70 },
]

const target = await (
  await fetch(`http://127.0.0.1:${port}/json/new?about:blank`, { method: 'PUT' })
).json()
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise((r) => ws.addEventListener('open', r, { once: true }))
let id = 0
const pending = new Map()
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data)
  if (m.id && pending.has(m.id)) {
    pending.get(m.id)(m)
    pending.delete(m.id)
  }
})
const send = (method, params = {}) =>
  new Promise((r) => {
    id += 1
    pending.set(id, r)
    ws.send(JSON.stringify({ id, method, params }))
  })
const evaluate = async (expression) =>
  (await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result
    .result?.value
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

await send('Page.enable')
const page = `file://${join(here, 'art.html')}`
for (let art = 0; art < ART_COUNT; art++) {
  for (const size of SIZES) {
    const [w, h] = size.render
    // 같은 주소에서 해시만 바꾸면 다시 그리지 않으므로 빈 페이지를 거친다
    await send('Page.navigate', { url: 'about:blank' })
    await sleep(100)
    await send('Page.navigate', { url: `${page}#art=${art}&w=${w}&h=${h}` })
    for (let i = 0; i < 100 && (await evaluate('document.title')) !== 'done'; i++) await sleep(100)
    const dataUrl = await evaluate(`document.getElementById('c').toDataURL('image/png')`)
    const png = join(work, `${size.kind}-${art}.png`)
    writeFileSync(png, Buffer.from(dataUrl.split(',')[1], 'base64'))
    const out = join(outDir, `${size.kind}-${art}.webp`)
    execFileSync('cwebp', [
      '-quiet',
      '-q',
      String(size.quality),
      '-m',
      '6',
      '-sharp_yuv',
      '-resize',
      String(size.out[0]),
      String(size.out[1]),
      png,
      '-o',
      out,
    ])
    console.log(out)
  }
}
ws.close()
await fetch(`http://127.0.0.1:${port}/json/close/${target.id}`)
rmSync(work, { recursive: true, force: true })
process.exit(0)
