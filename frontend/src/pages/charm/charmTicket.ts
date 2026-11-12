// 발권 이벤트 부적(ISE-257) 한 장. 1080×1920(9:16)은 인스타그램 스토리 크기다. 틀은 예매 상세 티켓(TicketsCard)을
// 세로로 세운 모양이고, 펫은 charmPets.ts의 부품을 조합한다. 같은 티켓은 언제 열어도 같은 부적이 나온다.

import { NICKNAME_SHOWN } from './charmLink'
import { petSvg, petTitle, type PetSpec } from './charmPets'

export const CHEERS: [string, string][] = [
  ['대기열은 결국 줄어들어요.', '당신 차례도 반드시 와요.'],
  ['천천히 가도 괜찮아요.', '공연은 당신을 기다려요.'],
  ['오늘 하루도', '매진시킬 만큼 잘했어요.'],
  ['원하는 자리에 앉는 날이', '생각보다 가까워요.'],
  ['지친 날엔 잠깐 쉬어도 돼요.', '좌석은 사라지지 않아요.'],
  ['당신의 이야기는', '이제 막 1막이에요.'],
  ['비가 와도 공연은 열려요.', '당신의 하루도 그래요.'],
  ['잘하고 있어요.', '생각보다 훨씬 더요.'],
  ['박수는 끝에 나와요.', '조금만 더 가 봐요.'],
  ['오늘의 실수는', '리허설이었을 뿐이에요.'],
  ['응원하는 사람이', '객석에 가득해요.'],
  ['앙코르는 끝까지', '남은 사람에게 와요.'],
  ['마음이 급할수록', '한 번 크게 숨 쉬어요.'],
  ['좋아하는 마음을', '끝까지 믿어 봐요.'],
  ['당신이 고른 길이', '가장 좋은 좌석이에요.'],
  ['오늘 웃은 만큼', '내일은 더 가벼워요.'],
  ['무대 조명은', '결국 당신을 비춰요.'],
  ['느려도 멈추지 않으면', '결국 입장해요.'],
  ['반짝이는 날은', '꼭 다시 와요.'],
  ['지금 이 순간도', '언젠가 좋은 추억이 돼요.'],
  ['당신이라는 공연,', '끝까지 보고 싶어요.'],
  ['새로고침은 그만해도 돼요.', '이미 충분히 빨라요.'],
  ['이선좌는 오늘까지만.', '다음 자리는 당신 거예요.'],
  ['좋은 자리는 늦게도 풀려요.', '기다린 만큼 가까이 앉아요.'],
  ['오늘 한 걸음이', '내일의 입장권이 돼요.'],
  ['포기하지 않은 사람에게', '취소표는 꼭 돌아와요.'],
  ['당신의 좌석은', '이미 예매되어 있어요.'],
  ['기다리는 시간도', '당신을 키우는 시간이에요.'],
  ['이번이 아니어도 괜찮아요.', '더 좋은 회차가 남았어요.'],
  ['좋은 소식은', '늘 갑자기 와요.'],
  ['연습한 만큼', '손이 먼저 움직일 거예요.'],
  ['오늘도 한 칸', '앞으로 나아갔어요.'],
  ['늦게 열린 문이', '더 좋은 자리로 이어져요.'],
  ['응원하는 마음 하나,', '오늘 여기 두고 가요.'],
  ['작은 행운이', '오늘 당신 곁에 앉았어요.'],
  ['하고 싶은 일이 있다면', '지금이 가장 좋은 회차예요.'],
  ['넘어져도 괜찮아요.', '공연은 다시 시작돼요.'],
  ['남과 비교하지 말아요.', '자리마다 보이는 게 달라요.'],
  ['오늘의 나에게', '박수 한 번 보내 줘요.'],
  ['당신이 꿈꾸는 무대는', '생각보다 가까이 있어요.'],
]

// 펫 조합 수: 종류 10 × 털색 4 × 머리 장식 6 × 든 것 5
const COMBOS = 1200
// gcd(A, 1200) = 1이라 s → (A·s + B) mod 1200이 일대일이다. A는 연속 ID 50개(중간에 빈 번호가 있는 경우 포함)에서
// 네 속성이 가장 고르게 섞이고 옆 사람과 같은 값이 덜 이어지는 수로 골랐다(charmTicket.test.ts가 그 성질을 지킨다)
const A = 911
const B = 517
export const SHINY_EVERY = 16

// 떼는 칸에 찍는 도장. 부적의 붉은 인을 본떴다. 예매·좌석 말장난 두 개는 서비스 주 색으로 찍는다
export const STAMPS: {
  lines: [string, string]
  shape: 'square' | 'round'
  color: string
  tilt: number
}[] = [
  { lines: ['소원', '성취'], shape: 'square', color: '#D12B3A', tilt: -8 },
  { lines: ['만사', '형통'], shape: 'round', color: '#D12B3A', tilt: 6 },
  { lines: ['행운', '가득'], shape: 'square', color: '#D12B3A', tilt: 5 },
  { lines: ['대박', '기원'], shape: 'round', color: '#D12B3A', tilt: -6 },
  { lines: ['꽃길', '예약'], shape: 'square', color: '#3B3BD4', tilt: -8 },
  { lines: ['행복', '예매'], shape: 'round', color: '#3B3BD4', tilt: 7 },
  { lines: ['건강', '기원'], shape: 'square', color: '#D12B3A', tilt: -4 },
  { lines: ['일취', '월장'], shape: 'round', color: '#D12B3A', tilt: -7 },
]

// 부적의 글자 한 줄. SVG로 보일 때는 <text>가 되고, PNG로 내보낼 때는 canvas가 같은 값으로 직접 쓴다.
// Note: SVG를 <img>로 불러 canvas에 그리면 SVG 안에서는 웹 글꼴(Pretendard)을 쓸 수 없다. 그래서 그림(art)과 글자를 나눈다
export interface CharmText {
  x: number
  y: number
  size: number
  weight: number
  fill: string
  text: string
  anchor?: 'middle'
  spacing?: number
  opacity?: number
  rotate?: { deg: number; cx: number; cy: number }
  // 같은 줄에 이어 쓰는 작은 글자(예: 펫 이름 뒤 "나의 수호펫")
  tail?: { text: string; size: number; weight: number; fill: string; gap: number }
}

export function textSvg(t: CharmText): string {
  const attrs = [
    `x="${t.x}" y="${t.y}" font-size="${t.size}" font-weight="${t.weight}" fill="${t.fill}"`,
    t.anchor ? `text-anchor="${t.anchor}"` : '',
    t.spacing ? `letter-spacing="${t.spacing}"` : '',
    t.opacity !== undefined ? `fill-opacity="${t.opacity}"` : '',
    t.rotate ? `transform="rotate(${t.rotate.deg} ${t.rotate.cx} ${t.rotate.cy})"` : '',
  ]
  const tail = t.tail
    ? `<tspan font-size="${t.tail.size}" font-weight="${t.tail.weight}" fill="${t.tail.fill}" dx="${t.tail.gap}">${escapeXml(t.tail.text)}</tspan>`
    : ''
  return `<text ${attrs.filter(Boolean).join(' ')}>${escapeXml(t.text)}${tail}</text>`
}

// 도장 하나. (cx, cy)가 가운데다
function stampArt(index: number, cx: number, cy: number): string {
  const { shape, color, tilt } = STAMPS[index]
  const frame =
    shape === 'square'
      ? `<rect x="${cx - 85}" y="${cy - 85}" width="170" height="170" rx="16" fill="${color}" fill-opacity="0.06" stroke="${color}" stroke-width="7"/>
    <rect x="${cx - 71}" y="${cy - 71}" width="142" height="142" rx="9" fill="none" stroke="${color}" stroke-width="2.5"/>`
      : `<circle cx="${cx}" cy="${cy}" r="88" fill="${color}" fill-opacity="0.06" stroke="${color}" stroke-width="7"/>
    <circle cx="${cx}" cy="${cy}" r="76" fill="none" stroke="${color}" stroke-width="2.5"/>`
  return `<g transform="rotate(${tilt} ${cx} ${cy})">${frame}</g>`
}

function stampTexts(index: number, cx: number, cy: number): CharmText[] {
  const { lines, shape, color, tilt } = STAMPS[index]
  // 원형은 위아래가 좁아 글자를 조금 줄이고 가운데로 모은다
  const [size, y1, y2] = shape === 'square' ? [52, cy - 8, cy + 54] : [46, cy - 12, cy + 44]
  const base = { x: cx, size, weight: 800, fill: color, anchor: 'middle', spacing: 4 } as const
  const rotate = { deg: tilt, cx, cy }
  return [
    { ...base, y: y1, text: lines[0], rotate },
    { ...base, y: y2, text: lines[1], rotate },
  ]
}

export function stampSvg(index: number, cx: number, cy: number): string {
  return stampArt(index, cx, cy) + stampTexts(index, cx, cy).map(textSvg).join('')
}

export interface CharmPick {
  pet: PetSpec
  nameIndex: number
  cheerIndex: number
  stampIndex: number
  power: number
  shiny: boolean
}

// Note: 무작위로 뽑으면 50명 중 같은 펫이 생길 확률이 크다(조합 1200개여도 생일 문제로 약 64%).
// 예매 ID는 DB가 1씩 늘려 주므로(IDENTITY), 일대일 대응식에 넣으면 연속한 1200건 안에서는 펫이 겹치지 않고 순서도 섞여 보인다.
// 반짝이 카드도 같은 방식으로 연속한 16건마다 정확히 한 장 나온다. 이름·응원 문장·도장도 각자의 주기로 돌아 옆 사람과 겹치지 않는다.
// 한 예매의 둘째 장부터는 300씩 떨어진 값을 써서(최대 4장이 1200 안에서 서로 다르다) 같은 예매 안의 펫이 겹치지 않게 한다.
export function pickCharm(reservationId: number, ticketIndex: number): CharmPick {
  const s = reservationId + ticketIndex * 300
  const combo = (((A * s + B) % COMBOS) + COMBOS) % COMBOS
  const pet: PetSpec = {
    species: combo % 10,
    palette: Math.floor(combo / 10) % 4,
    headwear: Math.floor(combo / 40) % 6,
    item: Math.floor(combo / 240) % 5,
  }
  const shiny = (((s * 5 + 3) % SHINY_EVERY) + SHINY_EVERY) % SHINY_EVERY === 0
  const noise = mix(s)
  return {
    pet,
    nameIndex: (((s * 17 + 5) % 40) + 40) % 40,
    cheerIndex: (((s * 23 + 11) % CHEERS.length) + CHEERS.length) % CHEERS.length,
    stampIndex: (((s * 3 + 2) % STAMPS.length) + STAMPS.length) % STAMPS.length,
    power: shiny ? 100_000 + (noise % 90_000) : 12_000 + (noise % 87_000),
    shiny,
  }
}

function mix(value: number): number {
  let x = Math.imul(value ^ 0x9e3779b9, 0x85ebca6b)
  x = Math.imul(x ^ (x >>> 13), 0xc2b2ae35)
  return (x ^ (x >>> 16)) >>> 0
}

export interface CharmData {
  nickname: string
  pick: CharmPick
  concertTitle: string
  // 떼는 칸의 큰 날짜(예: 11.28)와 그 아래 줄(예: 토요일 19:00)
  dateLabel: string
  timeLabel: string
  seatLabel: string
  barcodeValue: string
}

const escapeXml = (text: string) =>
  text.replace(
    /[&<>"']/g,
    (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!,
  )

// 글자 폭 어림값(em). 화면에서 재지 않고 SVG 문자열만 만들므로 Pretendard ExtraBold 기준으로 넉넉히 잡는다
export function estimateEm(text: string): number {
  let em = 0
  for (const char of text) {
    if (/[가-힣㄰-㆏一-鿿]/.test(char)) em += 1
    else if (char === ' ') em += 0.3
    else if (/[A-Z0-9]/.test(char)) em += 0.66
    else em += 0.56
  }
  return em
}

// 한글 8자는 46px로 닉네임 칸(폭 420)에 들어간다
export function charmNickname(nickname: string): { size: number; text: string } {
  const text = [...nickname].slice(0, NICKNAME_SHOWN).join('')
  return { size: Math.min(46, Math.floor(420 / estimateEm(text))), text }
}

function fitText(
  text: string,
  width: number,
  max: number,
  min: number,
): { size: number; text: string } {
  const size = Math.min(max, Math.floor(width / estimateEm(text)))
  if (size >= min) return { size, text }
  const chars = [...text]
  while (chars.length > 1 && estimateEm(chars.join('') + '…') * min > width) chars.pop()
  return { size: min, text: chars.join('') + '…' }
}

// 예매 상세 TicketBarcode와 같은 규칙. 꾸밈이라 읽히는 바코드가 아니다
function barcodeRects(value: string, x0: number, y: number, width: number, height: number): string {
  const bars: [number, number][] = []
  let x = 0
  for (const char of value) {
    const code = char.charCodeAt(0)
    for (const shift of [0, 2, 4]) {
      const w = ((code >> shift) % 3) + 1
      bars.push([x, w])
      x += w + (((code >> (shift + 1)) % 2) + 1)
    }
  }
  const scale = width / x
  return bars
    .map(
      ([bx, bw]) =>
        `<rect x="${(x0 + bx * scale).toFixed(2)}" y="${y}" width="${(bw * scale).toFixed(2)}" height="${height}"/>`,
    )
    .join('')
}

const sparkle = (x: number, y: number, s: number, fill: string, opacity = 1) =>
  `<path d="M${x} ${y - s} Q${x} ${y} ${x + s} ${y} Q${x} ${y} ${x} ${y + s} Q${x} ${y} ${x - s} ${y} Q${x} ${y} ${x} ${y - s} Z" fill="${fill}" fill-opacity="${opacity}"/>`

// 부적 한 장의 그림(글자 없음)과 글자 목록
export function charmLayout(data: CharmData): { art: string; texts: CharmText[] } {
  const { pick } = data
  const shiny = pick.shiny
  const nick = charmNickname(data.nickname)
  const title = fitText(data.concertTitle, 560, 30, 20)
  const [cheer1, cheer2] = CHEERS[pick.cheerIndex]

  const ticketFill = shiny ? 'url(#c-holo)' : '#FFFFFF'
  const panelFill = shiny ? 'url(#c-holo-panel)' : '#EEF0FF'
  const holoDefs = shiny
    ? `<linearGradient id="c-holo" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#FFE9F4"/><stop offset="0.28" stop-color="#E9F1FF"/>
        <stop offset="0.52" stop-color="#EAFFF3"/><stop offset="0.76" stop-color="#FFF8DC"/><stop offset="1" stop-color="#F2E9FF"/>
      </linearGradient>
      <linearGradient id="c-holo-panel" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#FFD3EA"/><stop offset="0.3" stop-color="#D3E4FF"/>
        <stop offset="0.55" stop-color="#D4FBE6"/><stop offset="0.8" stop-color="#FFF0B8"/><stop offset="1" stop-color="#E4D3FF"/>
      </linearGradient>
      <linearGradient id="c-foil" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#F7C948"/><stop offset="0.33" stop-color="#FF8FC8"/>
        <stop offset="0.66" stop-color="#7CC4FF"/><stop offset="1" stop-color="#7EE2B8"/>
      </linearGradient>
      <linearGradient id="c-gold" x1="0" y1="0" x2="1" y2="1">
        <stop offset="0" stop-color="#FFE7A3"/><stop offset="1" stop-color="#F5B83D"/>
      </linearGradient>
      <linearGradient id="c-sheen" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0" stop-color="#FFFFFF" stop-opacity="0"/><stop offset="0.5" stop-color="#FFFFFF" stop-opacity="0.75"/>
        <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
      </linearGradient>
      <clipPath id="c-ticket"><rect x="80" y="160" width="920" height="1600" rx="44"/></clipPath>`
    : ''
  // 반짝이 카드: 무지개 박 위에 대각선 빛줄기와 별을 얹는다. 글자 자리는 피한다
  const holoLayer = shiny
    ? `<g clip-path="url(#c-ticket)" mask="url(#c-notch)">
        <rect x="-200" y="560" width="1480" height="120" fill="url(#c-sheen)" transform="rotate(-28 540 620)" opacity="0.9"/>
        <rect x="-200" y="1420" width="1480" height="60" fill="url(#c-sheen)" transform="rotate(-28 540 1450)" opacity="0.7"/>
      </g>
      ${sparkle(118, 250, 14, '#FFFFFF')}${sparkle(962, 330, 18, '#FFFFFF')}${sparkle(950, 900, 12, '#F7C948')}
      ${sparkle(116, 1120, 12, '#FFFFFF')}${sparkle(964, 1600, 16, '#FFFFFF')}${sparkle(112, 1680, 10, '#F7C948')}`
    : ''
  const chip = shiny
    ? `<rect x="776" y="204" width="160" height="46" rx="23" fill="url(#c-gold)" stroke="#E0A21C" stroke-width="2"/>
       ${sparkle(800, 227, 9, '#8A5300')}
`
    : `<rect x="790" y="204" width="146" height="46" rx="23" fill="#EEF0FF"/>
`

  const art = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1080 1920" width="1080" height="1920" font-family="'Pretendard Variable', Pretendard, -apple-system, sans-serif">
  <defs>
    <linearGradient id="c-bg" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="${shiny ? '#3E2FB8' : '#4545E0'}"/><stop offset="1" stop-color="${shiny ? '#1F1E7A' : '#2F2FAE'}"/>
    </linearGradient>
    <radialGradient id="c-glow" cx="0.5" cy="0.18" r="0.6">
      <stop offset="0" stop-color="${shiny ? '#FF9BD2' : '#7B7BFF'}" stop-opacity="0.45"/>
      <stop offset="1" stop-color="#7B7BFF" stop-opacity="0"/>
    </radialGradient>
    <pattern id="c-seats" width="48" height="44" patternUnits="userSpaceOnUse">
      <rect x="15" y="15" width="18" height="14" rx="4" fill="#FFFFFF" fill-opacity="0.08"/>
    </pattern>
    <pattern id="c-seats-panel" width="40" height="36" patternUnits="userSpaceOnUse" x="4" y="6">
      <rect x="12" y="12" width="16" height="12" rx="3.5" fill="${shiny ? '#FFFFFF' : '#DDE1FF'}" fill-opacity="${shiny ? 0.5 : 0.75}"/>
    </pattern>
    <filter id="c-shadow" x="-15%" y="-10%" width="130%" height="125%">
      <feDropShadow dx="0" dy="28" stdDeviation="30" flood-color="#0B0B3A" flood-opacity="0.38"/>
    </filter>
    <mask id="c-notch">
      <rect width="1080" height="1920" fill="#FFFFFF"/>
      <circle cx="80" cy="1350" r="26" fill="#000000"/><circle cx="1000" cy="1350" r="26" fill="#000000"/>
    </mask>
    <clipPath id="c-panel"><rect x="144" y="284" width="792" height="576" rx="36"/></clipPath>
    ${holoDefs}
  </defs>

  <rect width="1080" height="1920" fill="url(#c-bg)"/>
  <rect width="1080" height="1920" fill="url(#c-seats)"/>
  <rect width="1080" height="1920" fill="url(#c-glow)"/>

  <g filter="url(#c-shadow)">
    <rect x="80" y="160" width="920" height="1600" rx="44" fill="${ticketFill}" mask="url(#c-notch)"/>
  </g>
  ${shiny ? `<rect x="84" y="164" width="912" height="1592" rx="40" fill="none" stroke="url(#c-foil)" stroke-width="8" mask="url(#c-notch)"/>` : ''}
  ${holoLayer}
  <line x1="132" y1="1350" x2="948" y2="1350" stroke="${shiny ? '#C9C2E6' : '#D7DAE2'}" stroke-width="4" stroke-dasharray="14 12" stroke-linecap="round"/>

  ${chip}

  <rect x="144" y="284" width="792" height="576" rx="36" fill="${panelFill}"/>
  <g clip-path="url(#c-panel)">
    <rect x="144" y="284" width="792" height="576" fill="url(#c-seats-panel)"/>
    <circle cx="540" cy="610" r="250" fill="#FFFFFF" fill-opacity="${shiny ? 0.35 : 0}"/>
    <circle cx="540" cy="610" r="250" fill="none" stroke="${shiny ? '#FFFFFF' : '#DDE1FF'}" stroke-width="3"/>
    <circle cx="540" cy="610" r="205" fill="${shiny ? '#FFFFFF' : '#DDE1FF'}" fill-opacity="${shiny ? 0.55 : 1}"/>
  </g>
  ${sparkle(258, 380, 28, '#5B5BF0')}${sparkle(828, 428, 36, '#F59F00')}${sparkle(810, 748, 20, '#5B5BF0')}${sparkle(262, 740, 16, '#F59F00')}
  <g transform="translate(340 300)">${petSvg(pick.pet, 'charm')}</g>

  <line x1="144" y1="962" x2="936" y2="962" stroke="${shiny ? '#D9D2EE' : '#E9EBF0'}" stroke-width="2"/>

  <path d="M614 1024 L596 1050 L610 1050 L602 1072 L626 1040 L612 1040 L620 1024 Z" fill="#F59F00" stroke="#9A5200" stroke-width="2" stroke-linejoin="round"/>
  <line x1="144" y1="1102" x2="936" y2="1102" stroke="${shiny ? '#D9D2EE' : '#E9EBF0'}" stroke-width="2"/>

  <rect x="144" y="1130" width="140" height="42" rx="21" fill="#FFF4E5"/>

  ${stampArt(pick.stampIndex, 836, 1478)}
  <g fill="#14161E">${barcodeRects(data.barcodeValue, 144, 1666, 792, 48)}</g>
</svg>`

  const ink = '#14161E'
  const muted = '#676C7E'
  const texts: CharmText[] = [
    {
      x: 540,
      y: 104,
      size: 26,
      weight: 700,
      fill: '#FFFFFF',
      opacity: 0.82,
      anchor: 'middle',
      spacing: 6,
      text: shiny ? 'ISEONJWA · RARE TICKET' : 'ISEONJWA · LUCKY TICKET',
    },
    { x: 144, y: 238, size: 24, weight: 700, fill: muted, spacing: 5, text: 'ADMIT 1' },
    shiny
      ? {
          x: 868,
          y: 236,
          size: 23,
          weight: 800,
          fill: '#6B3E00',
          anchor: 'middle',
          text: '레어 부적',
        }
      : {
          x: 863,
          y: 236,
          size: 23,
          weight: 700,
          fill: '#2F2FAE',
          anchor: 'middle',
          text: '행운 부적',
        },
    {
      x: 144,
      y: 922,
      size: 34,
      weight: 700,
      fill: ink,
      text: petTitle(pick.pet, pick.nameIndex),
      tail: { text: '나의 수호펫', size: 26, weight: 500, fill: muted, gap: 14 },
    },
    { x: 144, y: 1008, size: 24, weight: 600, fill: muted, text: '닉네임' },
    { x: 144, y: 1064, size: nick.size, weight: 800, fill: ink, spacing: -1, text: nick.text },
    { x: 600, y: 1008, size: 24, weight: 600, fill: muted, text: '전투력' },
    {
      x: 638,
      y: 1064,
      size: 46,
      weight: 800,
      fill: '#3B3BD4',
      spacing: -1,
      text: pick.power.toLocaleString('ko-KR'),
    },
    {
      x: 214,
      y: 1159,
      size: 22,
      weight: 700,
      fill: '#9A5200',
      anchor: 'middle',
      text: '오늘의 응원',
    },
    { x: 144, y: 1232, size: 50, weight: 800, fill: ink, spacing: -1.5, text: cheer1 },
    { x: 144, y: 1298, size: 50, weight: 800, fill: ink, spacing: -1.5, text: cheer2 },
    { x: 144, y: 1486, size: 112, weight: 800, fill: ink, spacing: -4, text: data.dateLabel },
    { x: 148, y: 1534, size: 30, weight: 600, fill: '#373B4A', text: data.timeLabel },
    { x: 144, y: 1596, size: title.size, weight: 700, fill: ink, text: title.text },
    { x: 144, y: 1638, size: 26, weight: 500, fill: '#4D5263', text: data.seatLabel },
    ...stampTexts(pick.stampIndex, 836, 1478),
    {
      x: 540,
      y: 1852,
      size: 24,
      weight: 600,
      fill: '#FFFFFF',
      opacity: 0.75,
      anchor: 'middle',
      spacing: 2,
      text: shiny ? '발권 기념 레어 부적 · 이선좌' : '발권 기념 행운 부적 · 이선좌',
    },
  ]
  return { art, texts }
}

// 화면에 바로 넣는 SVG(글자 포함). 글꼴은 문서의 Pretendard를 쓴다
export function charmSvg(data: CharmData): string {
  const { art, texts } = charmLayout(data)
  return art.replace('</svg>', `${texts.map(textSvg).join('')}</svg>`)
}
