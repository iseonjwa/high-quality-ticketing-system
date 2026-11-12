// 발권 이벤트 부적(ISE-257)의 펫. 미리 그린 부품(종류·털색·머리 장식·든 것)을 겹쳐 SVG 문자열로 만든다.
// 좌표는 펫 상자(400×540) 기준이다. 몸통은 모든 종류가 같은 찹쌀떡 모양이고, 귀·꼬리·무늬·코만 종류마다 다르다.
// 그림 규칙: 외곽선은 INK 7px, 둥근 이음. 몸통 안 무늬는 몸통 모양으로 잘라 외곽선 밖으로 나가지 않게 한다.

const INK = '#14161E'
const BODY =
  'M50 400 C50 260 110 200 200 200 C290 200 350 260 350 400 C350 470 290 490 200 490 C110 490 50 470 50 400 Z'

export interface PetPalette {
  body: string
  // 귀 안쪽, 늘어진 귀, 줄무늬처럼 몸보다 진한 색
  shade: string
  // 배, 주둥이, 얼굴 가면처럼 몸보다 밝은 색
  light: string
  inner: string
}

interface SpeciesArt {
  id: string
  label: string
  palettes: [PetPalette, PetPalette, PetPalette, PetPalette]
  // 몸통 뒤(귀, 꼬리, 날개)
  back?: (p: PetPalette) => string
  // 몸통 안(배, 가면, 줄무늬). 몸통 모양으로 잘린다
  marks?: (p: PetPalette) => string
  // 몸통 외곽선 위(늘어진 귀, 수염, 날개)
  front?: (p: PetPalette) => string
  // 코와 입. 없으면 기본 입(ω)
  mouth?: (p: PetPalette) => string
  feet?: string
}

const ear = (
  cx: number,
  cy: number,
  rx: number,
  ry: number,
  deg: number,
  fill: string,
  stroke = true,
) =>
  `<ellipse cx="${cx}" cy="${cy}" rx="${rx}" ry="${ry}" transform="rotate(${deg} ${cx} ${cy})" fill="${fill}"${
    stroke ? ` stroke="${INK}" stroke-width="7"` : ''
  }/>`

// 왼쪽에 그린 path를 x=200 기준으로 뒤집어 오른쪽에도 둔다
const mirrored = (d: string, attrs: string) =>
  `<path d="${d}" ${attrs}/><path d="${d}" ${attrs} transform="matrix(-1 0 0 1 400 0)"/>`

const line = `stroke="${INK}" stroke-width="7" stroke-linejoin="round" stroke-linecap="round"`
const defaultMouth = (y = 390) =>
  `<path d="M185 ${y} Q192.5 ${y + 11} 200 ${y} Q207.5 ${y + 11} 215 ${y}" fill="none" stroke="${INK}" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>`

export const SPECIES: SpeciesArt[] = [
  {
    id: 'rabbit',
    label: '토끼',
    palettes: [
      { body: '#FFF8EC', shade: '#F3E3CC', light: '#FFFFFF', inner: '#FFC2CF' },
      { body: '#FFFFFF', shade: '#EDEDF3', light: '#FFFFFF', inner: '#FFB3C4' },
      { body: '#E6E3EC', shade: '#C9C5D3', light: '#F7F6FA', inner: '#FFC2CF' },
      { body: '#E9DCFF', shade: '#CDB8F5', light: '#F7F2FF', inner: '#FFC2E2' },
    ],
    back: (p) =>
      ear(138, 130, 40, 100, -12, p.body) +
      ear(262, 130, 40, 100, 12, p.body) +
      ear(138, 132, 17, 64, -12, p.inner, false) +
      ear(262, 132, 17, 64, 12, p.inner, false),
  },
  {
    id: 'cat',
    label: '고양이',
    palettes: [
      { body: '#FFF3E0', shade: '#E8CFA8', light: '#FFFFFF', inner: '#FFC2CF' },
      { body: '#FFCE99', shade: '#F0A35E', light: '#FFF1DE', inner: '#FFB3A7' },
      { body: '#D9DDE7', shade: '#A9AFBF', light: '#F4F5F9', inner: '#FFC2CF' },
      { body: '#F2E6D8', shade: '#8B6A55', light: '#FFFFFF', inner: '#FFC2CF' },
    ],
    back: (p) =>
      mirrored('M86 300 L96 168 L188 222 Z', `fill="${p.body}" ${line}`) +
      mirrored('M106 258 L112 198 L158 226 Z', `fill="${p.inner}"`),
    marks: (p) =>
      `<path d="M182 204 Q188 232 194 204 M206 204 Q212 232 218 204" fill="none" stroke="${p.shade}" stroke-width="8" stroke-linecap="round"/>`,
    front: () =>
      mirrored(
        'M86 392 L40 384 M86 406 L42 414',
        `fill="none" stroke="${INK}" stroke-width="4" stroke-linecap="round"`,
      ),
    mouth: () =>
      `<path d="M191 379 L209 379 L200 389 Z" fill="#FF8FA3" stroke="${INK}" stroke-width="3" stroke-linejoin="round"/>` +
      defaultMouth(395),
  },
  {
    id: 'dog',
    label: '강아지',
    palettes: [
      { body: '#FFF1DC', shade: '#C58B5B', light: '#FFFFFF', inner: '#FFC2CF' },
      { body: '#FFFFFF', shade: '#F2C9A0', light: '#FFFFFF', inner: '#FFC2CF' },
      { body: '#E8BE94', shade: '#9C6B45', light: '#FFF1DC', inner: '#FFC2CF' },
      { body: '#E6E6EE', shade: '#8E93A6', light: '#FFFFFF', inner: '#FFC2CF' },
    ],
    marks: (p) =>
      `<ellipse cx="248" cy="360" rx="36" ry="32" fill="${p.shade}" fill-opacity="0.35"/>`,
    front: (p) =>
      mirrored(
        'M106 232 C64 230 36 290 44 360 C50 404 92 400 102 364 C110 330 116 270 106 232 Z',
        `fill="${p.shade}" ${line}`,
      ),
    mouth: () =>
      `<ellipse cx="200" cy="410" rx="8" ry="9" fill="#FF8FA3" stroke="${INK}" stroke-width="3"/>` +
      `<ellipse cx="200" cy="384" rx="14" ry="10" fill="${INK}"/><circle cx="195" cy="380" r="3.5" fill="#FFFFFF"/>` +
      `<path d="M200 394 L200 400 M186 398 Q193 408 200 400 Q207 408 214 398" fill="none" stroke="${INK}" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>`,
  },
  {
    id: 'bear',
    label: '곰',
    palettes: [
      { body: '#D9A47F', shade: '#B67D55', light: '#F6E3CF', inner: '#B67D55' },
      { body: '#F2C27B', shade: '#D49A45', light: '#FFF0D2', inner: '#D49A45' },
      { body: '#FFFFFF', shade: '#E3E5EC', light: '#FFFFFF', inner: '#E3E5EC' },
      { body: '#FAC9D5', shade: '#EE9DB2', light: '#FFF0F4', inner: '#EE9DB2' },
    ],
    back: (p) =>
      `<circle cx="100" cy="230" r="44" fill="${p.body}" ${line}/><circle cx="300" cy="230" r="44" fill="${p.body}" ${line}/>` +
      `<circle cx="104" cy="232" r="22" fill="${p.inner}"/><circle cx="296" cy="232" r="22" fill="${p.inner}"/>`,
    front: (p) =>
      `<ellipse cx="200" cy="406" rx="40" ry="28" fill="${p.light}" stroke="${INK}" stroke-width="5"/>`,
    mouth: () =>
      `<ellipse cx="200" cy="394" rx="12" ry="8" fill="${INK}"/>` +
      `<path d="M188 408 Q194 416 200 408 Q206 416 212 408" fill="none" stroke="${INK}" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>`,
  },
  {
    id: 'hamster',
    label: '햄스터',
    palettes: [
      { body: '#F7D29C', shade: '#E2AE66', light: '#FFF8EC', inner: '#FFB3C4' },
      { body: '#DCDFE7', shade: '#B3B8C6', light: '#FFFFFF', inner: '#FFB3C4' },
      { body: '#FFFFFF', shade: '#EDEDF3', light: '#FFFFFF', inner: '#FFB3C4' },
      { body: '#EDB98A', shade: '#C98A55', light: '#FFF3E4', inner: '#FFB3C4' },
    ],
    back: (p) =>
      `<circle cx="104" cy="226" r="30" fill="${p.body}" ${line}/><circle cx="296" cy="226" r="30" fill="${p.body}" ${line}/>` +
      `<circle cx="106" cy="228" r="15" fill="${p.inner}"/><circle cx="294" cy="228" r="15" fill="${p.inner}"/>`,
    marks: (p) =>
      `<ellipse cx="200" cy="452" rx="112" ry="84" fill="${p.light}"/>` +
      `<ellipse cx="104" cy="410" rx="46" ry="38" fill="${p.light}"/><ellipse cx="296" cy="410" rx="46" ry="38" fill="${p.light}"/>`,
    mouth: () =>
      `<ellipse cx="200" cy="381" rx="7" ry="5" fill="#FF8FA3"/>` +
      `<path d="M186 390 Q193 398 200 390 Q207 398 214 390" fill="none" stroke="${INK}" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>` +
      `<rect x="193" y="396" width="14" height="12" rx="2" fill="#FFFFFF" stroke="${INK}" stroke-width="3"/><path d="M200 397 L200 407" stroke="${INK}" stroke-width="2"/>`,
  },
  {
    id: 'penguin',
    label: '펭귄',
    feet: '#FFA94D',
    palettes: [
      { body: '#2F3654', shade: '#1F2440', light: '#FFFFFF', inner: '#FFA94D' },
      { body: '#5B7FC7', shade: '#3F5E9E', light: '#FFFFFF', inner: '#FFA94D' },
      { body: '#6C7387', shade: '#4D5263', light: '#FFFFFF', inner: '#FFA94D' },
      { body: '#C76A92', shade: '#A24F73', light: '#FFF6FA', inner: '#FFA94D' },
    ],
    back: (p) => ear(56, 418, 22, 54, 24, p.body) + ear(344, 418, 22, 54, -24, p.body),
    marks: (p) =>
      `<path d="M200 270 C150 236 84 266 84 346 C84 430 140 494 200 494 C260 494 316 430 316 346 C316 266 250 236 200 270 Z" fill="${p.light}"/>`,
    mouth: () =>
      `<path d="M182 382 Q200 368 218 382 Q200 400 182 382 Z" fill="#FFA94D" stroke="${INK}" stroke-width="4" stroke-linejoin="round"/>`,
  },
  {
    id: 'fox',
    label: '여우',
    palettes: [
      { body: '#FFA25A', shade: '#3A2E2A', light: '#FFF6EC', inner: '#FFE3C8' },
      { body: '#F4F6FB', shade: '#9AA1B2', light: '#FFFFFF', inner: '#E3E6EE' },
      { body: '#BAC0D2', shade: '#5E6578', light: '#F4F5F9', inner: '#E3E6EE' },
      { body: '#F27A55', shade: '#5A2E22', light: '#FFF1EA', inner: '#FFD3C2' },
    ],
    back: (p) =>
      `<path d="M300 474 C384 474 424 380 390 300 C374 262 336 262 340 302 C346 352 330 422 276 442 Z" fill="${p.body}" ${line}/>` +
      `<path d="M390 300 C374 262 336 262 340 302 C352 296 378 292 390 300 Z" fill="${p.light}"/>` +
      `<path d="M300 474 C384 474 424 380 390 300 C374 262 336 262 340 302 C346 352 330 422 276 442 Z" fill="none" ${line}/>` +
      mirrored('M78 316 L84 116 L198 222 Z', `fill="${p.body}"`) +
      mirrored('M100 268 L102 166 L168 222 Z', `fill="${p.inner}"`) +
      mirrored('M84 116 L82 188 L134 164 Z', `fill="${p.shade}"`) +
      mirrored('M78 316 L84 116 L198 222 Z', `fill="none" ${line}`),
    marks: (p) =>
      `<path d="M40 404 C90 362 150 372 200 412 C250 372 310 362 360 404 L360 510 L40 510 Z" fill="${p.light}"/>`,
    mouth: () => `<ellipse cx="200" cy="392" rx="10" ry="7" fill="${INK}"/>` + defaultMouth(403),
  },
  {
    id: 'dragon',
    label: '아기 용',
    palettes: [
      { body: '#9EE3B5', shade: '#5DBB80', light: '#FFF3C4', inner: '#FFF3C4' },
      { body: '#C9B6FF', shade: '#9479E6', light: '#FFF1D6', inner: '#FFF1D6' },
      { body: '#9FD3FF', shade: '#5A9EE0', light: '#FFF6D6', inner: '#FFF6D6' },
      { body: '#FFB09E', shade: '#E2735E', light: '#FFF3C4', inner: '#FFF3C4' },
    ],
    back: (p) =>
      mirrored(
        'M74 384 C30 362 8 300 22 248 L50 282 L58 250 L80 296 Z',
        `fill="${p.shade}" ${line}`,
      ) +
      `<path d="M178 212 L190 170 L204 204 L216 166 L226 212 Z" fill="${p.shade}" ${line}/>` +
      mirrored(
        'M126 236 C112 196 116 160 140 138 C142 170 154 200 168 214 Z',
        `fill="${p.inner}" ${line}`,
      ),
    marks: (p) =>
      `<ellipse cx="200" cy="456" rx="94" ry="66" fill="${p.light}"/>` +
      `<path d="M126 428 L274 428 M118 452 L282 452 M126 476 L274 476" stroke="${p.shade}" stroke-width="4" stroke-opacity="0.55"/>`,
    mouth: () =>
      `<circle cx="190" cy="386" r="3.5" fill="${INK}"/><circle cx="210" cy="386" r="3.5" fill="${INK}"/>` +
      defaultMouth(396),
  },
  {
    id: 'tiger',
    label: '호랑이',
    palettes: [
      { body: '#FFB04A', shade: '#3A2E2A', light: '#FFF6EC', inner: '#3A2E2A' },
      { body: '#FFFFFF', shade: '#5D6375', light: '#FFFFFF', inner: '#5D6375' },
      { body: '#FFD46B', shade: '#8A5A1E', light: '#FFF8E1', inner: '#8A5A1E' },
      { body: '#FFC4CF', shade: '#A8475E', light: '#FFF2F5', inner: '#A8475E' },
    ],
    back: (p) =>
      `<circle cx="104" cy="226" r="34" fill="${p.body}" ${line}/><circle cx="296" cy="226" r="34" fill="${p.body}" ${line}/>` +
      `<circle cx="106" cy="228" r="16" fill="${p.inner}"/><circle cx="294" cy="228" r="16" fill="${p.inner}"/>`,
    marks: (p) =>
      `<path d="M188 202 Q200 246 212 202 Z" fill="${p.shade}"/>` +
      mirrored(
        'M146 214 Q162 238 176 222 M48 356 Q80 366 98 350 M50 392 Q84 400 102 386',
        `fill="none" stroke="${p.shade}" stroke-width="9" stroke-linecap="round"`,
      ) +
      `<ellipse cx="200" cy="408" rx="48" ry="32" fill="${p.light}"/>`,
    mouth: () =>
      `<path d="M189 386 L211 386 L200 397 Z" fill="${INK}" stroke="${INK}" stroke-width="3" stroke-linejoin="round"/>` +
      defaultMouth(402),
  },
  {
    id: 'chick',
    label: '병아리',
    feet: '#FFA94D',
    palettes: [
      { body: '#FFE066', shade: '#F5C431', light: '#FFF6C2', inner: '#FFA94D' },
      { body: '#FFF1A8', shade: '#F2D769', light: '#FFFBE0', inner: '#FFA94D' },
      { body: '#FFD2B0', shade: '#F4A97A', light: '#FFF1E6', inner: '#FFA94D' },
      { body: '#CDEFD8', shade: '#8FD3A8', light: '#F0FBF4', inner: '#FFA94D' },
    ],
    back: (p) =>
      `<path d="M194 206 C184 176 190 158 204 150 C200 172 206 188 214 206 Z" fill="${p.body}" ${line}/>` +
      `<path d="M206 206 C212 182 228 172 242 174 C230 188 222 198 218 208 Z" fill="${p.body}" ${line}/>`,
    front: (p) =>
      mirrored(
        'M60 392 C30 386 26 436 58 452 C70 440 70 412 60 392 Z',
        `fill="${p.shade}" ${line}`,
      ),
    mouth: () =>
      `<path d="M184 386 L200 374 L216 386 L200 398 Z" fill="#FFA94D" stroke="${INK}" stroke-width="4" stroke-linejoin="round"/>`,
  },
]

interface HeadwearArt {
  id: string
  // 펫 이름 앞에 붙는 말(예: 리듬 토끼)
  epithet: string
  draw: string
}

function starPath(cx: number, cy: number, r: number, inner: number) {
  const points: string[] = []
  for (let i = 0; i < 10; i++) {
    const radius = i % 2 === 0 ? r : inner
    const angle = -Math.PI / 2 + (i * Math.PI) / 5
    points.push(
      `${(cx + radius * Math.cos(angle)).toFixed(1)} ${(cy + radius * Math.sin(angle)).toFixed(1)}`,
    )
  }
  return `M${points.join(' L')} Z`
}

export const HEADWEAR: HeadwearArt[] = [
  {
    // 콘서트 예매 서비스라 음악을 듣는 헤드폰을 둔다. 띠는 머리 위로 넘기고 귀마개는 몸통 양옆에 붙인다
    id: 'headphones',
    epithet: '리듬',
    draw: `<path d="M64 336 C60 118 340 118 336 336" fill="none" stroke="${INK}" stroke-width="22" stroke-linecap="round"/>
      <path d="M64 336 C60 118 340 118 336 336" fill="none" stroke="#454A66" stroke-width="10" stroke-linecap="round"/>
      <rect x="34" y="292" width="50" height="86" rx="20" fill="#5B5BF0" stroke="${INK}" stroke-width="6"/>
      <rect x="316" y="292" width="50" height="86" rx="20" fill="#5B5BF0" stroke="${INK}" stroke-width="6"/>
      <rect x="46" y="308" width="14" height="54" rx="7" fill="#A9B0FF"/>
      <rect x="340" y="308" width="14" height="54" rx="7" fill="#A9B0FF"/>
      <path d="M368 250 L368 214 L388 208 L388 240" fill="none" stroke="#5B5BF0" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>
      <ellipse cx="362" cy="252" rx="8" ry="6" fill="#5B5BF0"/><ellipse cx="382" cy="242" rx="8" ry="6" fill="#5B5BF0"/>`,
  },
  {
    id: 'gradcap',
    epithet: '졸업',
    draw: `<path d="M146 216 L146 188 Q200 172 254 188 L254 216 Q200 200 146 216 Z" fill="#2B2F45" ${line}/>
      <path d="M92 174 L200 136 L308 174 L200 212 Z" fill="#373B54" ${line}/>
      <path d="M200 174 L288 196 L292 238" fill="none" stroke="#F59F00" stroke-width="5" stroke-linecap="round"/>
      <circle cx="200" cy="174" r="7" fill="#F59F00" stroke="${INK}" stroke-width="3"/>
      <rect x="283" y="234" width="18" height="26" rx="5" fill="#F59F00" stroke="${INK}" stroke-width="3"/>`,
  },
  {
    id: 'crown',
    epithet: '왕관 쓴',
    draw: `<path d="M140 216 L130 150 L170 184 L200 134 L230 184 L270 150 L260 216 Z" fill="#FFD43B" ${line}/>
      <circle cx="130" cy="148" r="8" fill="#FFD43B" stroke="${INK}" stroke-width="4"/>
      <circle cx="200" cy="132" r="8" fill="#FFD43B" stroke="${INK}" stroke-width="4"/>
      <circle cx="270" cy="148" r="8" fill="#FFD43B" stroke="${INK}" stroke-width="4"/>
      <circle cx="200" cy="194" r="9" fill="#D12B3A" stroke="${INK}" stroke-width="3"/>
      <circle cx="162" cy="202" r="6" fill="#3B3BD4"/><circle cx="238" cy="202" r="6" fill="#3B3BD4"/>`,
  },
  {
    id: 'bow',
    epithet: '리본 단',
    draw: `<g transform="translate(286 232) rotate(14)" stroke="${INK}" stroke-width="5" stroke-linejoin="round" fill="#FF6B9A">
        <path d="M0 0 C-28 -30 -48 8 -24 28 C-14 20 -6 10 0 0 Z"/>
        <path d="M0 0 C28 -30 48 8 24 28 C14 20 6 10 0 0 Z"/>
        <circle cx="0" cy="2" r="11"/>
      </g>`,
  },
  {
    id: 'sprout',
    epithet: '새싹',
    draw: `<path d="M200 206 C200 188 198 174 202 158" fill="none" stroke="#2B8A3E" stroke-width="7" stroke-linecap="round"/>
      <path d="M202 170 C186 146 156 148 152 166 C172 180 192 178 202 170 Z" fill="#8CE99A" stroke="${INK}" stroke-width="5" stroke-linejoin="round"/>
      <path d="M202 162 C214 136 246 136 250 154 C234 170 214 170 202 162 Z" fill="#69DB7C" stroke="${INK}" stroke-width="5" stroke-linejoin="round"/>`,
  },
  {
    id: 'starpin',
    epithet: '반짝',
    draw: `<path d="${starPath(282, 244, 26, 11)}" fill="#FFD43B" stroke="${INK}" stroke-width="5" stroke-linejoin="round"/>
      <path d="M322 206 Q322 216 332 216 Q322 216 322 226 Q322 216 312 216 Q322 216 322 206 Z" fill="#F59F00"/>`,
  },
]

interface ItemArt {
  id: string
  label: string
  // 앞발 두 개의 중심. 든 것 위에 그린다
  paws: [[number, number], [number, number]]
  // 앞발 뒤에 그리는 것과 앞발 위에 그리는 것
  under: string
  over?: string
}

export const ITEMS: ItemArt[] = [
  {
    id: 'ticket',
    label: '티켓',
    paws: [
      [140, 450],
      [260, 450],
    ],
    under: `<rect x="134" y="424" width="132" height="54" rx="9" fill="#3B3BD4" stroke="${INK}" stroke-width="5"/>
      <text x="200" y="457" text-anchor="middle" font-size="15" font-weight="800" letter-spacing="1" fill="#FFFFFF">ADMIT</text>`,
  },
  {
    id: 'lightstick',
    label: '응원봉',
    paws: [
      [150, 452],
      [298, 444],
    ],
    under: `<circle cx="354" cy="312" r="46" fill="#DDE1FF" fill-opacity="0.85"/>
      <path d="M298 446 L346 334" stroke="#373B4A" stroke-width="12" stroke-linecap="round"/>
      <circle cx="354" cy="314" r="30" fill="#A9B0FF" stroke="${INK}" stroke-width="6"/>
      <path d="${starPath(354, 314, 15, 6.5)}" fill="#FFFFFF"/>`,
  },
  {
    id: 'heart',
    label: '하트',
    paws: [
      [154, 448],
      [246, 448],
    ],
    under: `<path d="M200 478 C158 450 146 426 160 410 C174 394 194 402 200 418 C206 402 226 394 240 410 C254 426 242 450 200 478 Z" fill="#FF6B81" stroke="${INK}" stroke-width="5" stroke-linejoin="round"/>`,
  },
  {
    id: 'clover',
    label: '네잎클로버',
    paws: [
      [158, 454],
      [242, 454],
    ],
    under: `<path d="M210 470 Q222 488 236 490" fill="none" stroke="${INK}" stroke-width="5" stroke-linecap="round"/>
      <g fill="#51CF66" stroke="${INK}" stroke-width="4">
        <circle cx="186" cy="432" r="16"/><circle cx="214" cy="432" r="16"/><circle cx="186" cy="460" r="16"/><circle cx="214" cy="460" r="16"/>
      </g>
      <circle cx="200" cy="446" r="5" fill="#2B8A3E"/>`,
  },
  {
    id: 'coffee',
    label: '커피',
    paws: [
      [164, 456],
      [236, 456],
    ],
    under: `<path d="M172 424 L228 424 L222 484 Q200 490 178 484 Z" fill="#FFFFFF" stroke="${INK}" stroke-width="5" stroke-linejoin="round"/>
      <path d="M175 442 L225 442 L223 462 L177 462 Z" fill="#C58B5B"/>
      <rect x="164" y="410" width="72" height="16" rx="7" fill="#E9EBF0" stroke="${INK}" stroke-width="5"/>`,
  },
]

export interface PetSpec {
  species: number
  palette: number
  headwear: number
  item: number
}

// 펫 상자(400×540) 안에 그린 펫. id는 한 문서에 펫이 여럿일 때 잘라내기 경로가 겹치지 않게 붙인다
export function petSvg(spec: PetSpec, id: string): string {
  const species = SPECIES[spec.species]
  const p = species.palettes[spec.palette]
  const headwear = HEADWEAR[spec.headwear]
  const item = ITEMS[spec.item]
  const feet = species.feet ?? p.body
  const clip = `pet-body-${id}`
  const paw = ([cx, cy]: [number, number]) =>
    `<circle cx="${cx}" cy="${cy}" r="19" fill="${p.body}" stroke="${INK}" stroke-width="6"/>`
  return `<g>
    <defs><clipPath id="${clip}"><path d="${BODY}"/></clipPath></defs>
    <ellipse cx="200" cy="514" rx="160" ry="16" fill="#3B3BD4" fill-opacity="0.14"/>
    ${species.back?.(p) ?? ''}
    <ellipse cx="142" cy="492" rx="36" ry="20" fill="${feet}" ${line}/>
    <ellipse cx="258" cy="492" rx="36" ry="20" fill="${feet}" ${line}/>
    <path d="${BODY}" fill="${p.body}"/>
    <g clip-path="url(#${clip})">${species.marks?.(p) ?? ''}</g>
    <path d="${BODY}" fill="none" ${line}/>
    ${species.front?.(p) ?? ''}
    ${headwear.draw}
    <ellipse cx="155" cy="364" rx="13" ry="16" fill="${INK}"/><ellipse cx="245" cy="364" rx="13" ry="16" fill="${INK}"/>
    <circle cx="150" cy="357" r="5" fill="#FFFFFF"/><circle cx="240" cy="357" r="5" fill="#FFFFFF"/>
    <ellipse cx="120" cy="400" rx="22" ry="12" fill="#FF9BB0" fill-opacity="0.75"/>
    <ellipse cx="280" cy="400" rx="22" ry="12" fill="#FF9BB0" fill-opacity="0.75"/>
    ${species.mouth?.(p) ?? defaultMouth()}
    ${item.under}
    ${paw(item.paws[0])}${paw(item.paws[1])}
    ${item.over ?? ''}
  </g>`
}

export const PET_NAMES = [
  '콩떡',
  '모찌',
  '두부',
  '호떡',
  '감자',
  '보리',
  '쿠키',
  '젤리',
  '도토리',
  '망고',
  '라떼',
  '구름',
  '솜이',
  '땅콩',
  '만두',
  '찹쌀',
  '꼬미',
  '단추',
  '율무',
  '밤톨',
  '버터',
  '크림',
  '치즈',
  '깨비',
  '호두',
  '자두',
  '앵두',
  '꿀떡',
  '누룽지',
  '별이',
  '달이',
  '해피',
  '뭉치',
  '초코',
  '마루',
  '하루',
  '나리',
  '토리',
  '보송',
  '몽실',
]

export function petTitle(spec: PetSpec, nameIndex: number): string {
  return `${HEADWEAR[spec.headwear].epithet} ${SPECIES[spec.species].label} ${PET_NAMES[nameIndex]}`
}
