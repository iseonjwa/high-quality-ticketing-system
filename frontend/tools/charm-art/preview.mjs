// 발권 이벤트 부적(ISE-257)의 펫과 부적을 PNG로 찍어 눈으로 확인한다. 그림을 바꿀 때만 손으로 돌린다(빌드에 넣지 않는다).
// 준비: Chrome이 있어야 한다(CHROME 환경변수로 경로를 넘길 수 있다). 글꼴은 Pretendard CDN을 쓴다.
// 사용: node tools/charm-art/preview.mjs <출력 폴더>
//   pets-sheet.png  펫 종류 10가지와 무작위 조합 10개
//   stamps-sheet.png  떼는 칸 도장 8가지
//   ticket-normal.png, ticket-shiny.png  1080×1920 부적 두 장
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { rolldown } from 'rolldown'

const here = dirname(fileURLToPath(import.meta.url))
const outDir = resolve(process.argv[2] ?? join(here, 'out'))
mkdirSync(outDir, { recursive: true })
const work = mkdtempSync(join(tmpdir(), 'charm-art-'))
const chrome = process.env.CHROME ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const FONT =
  'https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.min.css'

try {
  // 화면 코드(TypeScript)를 그대로 쓰려고 한 파일로 묶어 읽는다
  const src = join(here, '../../src/pages/charm')
  const bundle = await rolldown({
    input: { ticket: join(src, 'charmTicket.ts'), pets: join(src, 'charmPets.ts') },
  })
  await bundle.write({ dir: work, format: 'esm', entryFileNames: '[name].mjs' })
  const { charmSvg, pickCharm, CHEERS, STAMPS, stampSvg } = await import(
    pathToFileURL(join(work, 'ticket.mjs')).href
  )
  const { petSvg, petTitle, SPECIES } = await import(pathToFileURL(join(work, 'pets.mjs')).href)

  const shot = (name, html, width, height) => {
    const page = join(work, `${name}.html`)
    writeFileSync(
      page,
      `<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="${FONT}"><style>html,body{margin:0;padding:0;background:#FBFBFD}svg{display:block}</style></head><body>${html}</body></html>`,
    )
    execFileSync(
      chrome,
      [
        '--headless=new',
        '--disable-gpu',
        '--hide-scrollbars',
        '--force-device-scale-factor=1',
        `--window-size=${width},${height}`,
        '--virtual-time-budget=6000',
        `--screenshot=${join(outDir, `${name}.png`)}`,
        pathToFileURL(page).href,
      ],
      { stdio: 'ignore' },
    )
    console.log(join(outDir, `${name}.png`))
  }

  // 펫 시트: 첫 줄 둘은 종류 10가지(털색 0, 헤드폰, 티켓), 아래 둘은 예매 ID 1001~1010의 실제 배정
  const cells = []
  for (let i = 0; i < 10; i++)
    cells.push({
      spec: { species: i, palette: 0, headwear: 0, item: 0 },
      name: 0,
      caption: SPECIES[i].label,
    })
  for (let id = 1001; id <= 1010; id++) {
    const pick = pickCharm(id, 0)
    cells.push({
      spec: pick.pet,
      name: pick.nameIndex,
      caption: `#${id}${pick.shiny ? ' 반짝' : ''}`,
    })
  }
  const cw = 280
  const ch = 400
  const sheet = cells
    .map((cell, i) => {
      const x = (i % 5) * cw
      const y = Math.floor(i / 5) * ch
      return `<g transform="translate(${x} ${y})">
        <rect x="10" y="10" width="${cw - 20}" height="${ch - 20}" rx="24" fill="#EEF0FF"/>
        <g transform="translate(30 28) scale(0.55)">${petSvg(cell.spec, `s${i}`)}</g>
        <text x="${cw / 2}" y="${ch - 64}" text-anchor="middle" font-size="18" font-weight="700" fill="#14161E">${petTitle(cell.spec, cell.name)}</text>
        <text x="${cw / 2}" y="${ch - 38}" text-anchor="middle" font-size="15" font-weight="500" fill="#676C7E">${cell.caption}</text>
      </g>`
    })
    .join('')
  shot(
    'pets-sheet',
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${cw * 5} ${ch * 4}" width="${cw * 5}" height="${ch * 4}" font-family="'Pretendard Variable', Pretendard, sans-serif">${sheet}</svg>`,
    cw * 5,
    ch * 4,
  )

  // 도장 시트: 도장 8가지를 한 줄에
  const stamps = STAMPS.map((_, i) => stampSvg(i, 110 + i * 210, 120)).join('')
  shot(
    'stamps-sheet',
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${STAMPS.length * 210 + 10} 240" width="${STAMPS.length * 210 + 10}" height="240" font-family="'Pretendard Variable', Pretendard, sans-serif"><rect width="100%" height="100%" fill="#FFFFFF"/>${stamps}</svg>`,
    STAMPS.length * 210 + 10,
    240,
  )

  const sample = {
    concertTitle: '노을섬 단독 콘서트 〈새벽의 좌석〉',
    dateLabel: '11.28',
    timeLabel: '토요일 19:00',
    seatLabel: 'VIP석 · A구역 3열 12번',
    barcodeValue: 'T20261128-7K3QX9MA',
  }
  // 반짝이는 16건마다 한 장이므로 처음 나오는 ID(2009)를 찾아 쓴다. 일반은 도장이 다르게 나오는 2002를 쓴다
  let shinyId = 2000
  while (!pickCharm(shinyId, 0).shiny) shinyId++
  shot(
    'ticket-normal',
    charmSvg({
      ...sample,
      nickname: '꿈꾸는개발자',
      pick: pickCharm(2002, 0),
    }),
    1080,
    1920,
  )
  shot(
    'ticket-shiny',
    charmSvg({ ...sample, nickname: '반짝이는하루', pick: pickCharm(shinyId, 0) }),
    1080,
    1920,
  )
  shot(
    'ticket-long-nickname',
    charmSvg({
      ...sample,
      nickname: '닉네임이아주아주길어서서른자를꽉채운티켓팅고수입니다',
      pick: pickCharm(2003, 0),
    }),
    1080,
    1920,
  )
  console.log(`응원 문장 ${CHEERS.length}개`)
} finally {
  rmSync(work, { recursive: true, force: true })
}
