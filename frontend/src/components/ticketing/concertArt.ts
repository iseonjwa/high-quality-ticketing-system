import poster0 from '@/assets/concert-art/poster-0.webp'
import poster1 from '@/assets/concert-art/poster-1.webp'
import poster2 from '@/assets/concert-art/poster-2.webp'
import poster3 from '@/assets/concert-art/poster-3.webp'
import poster4 from '@/assets/concert-art/poster-4.webp'
import poster5 from '@/assets/concert-art/poster-5.webp'
import poster6 from '@/assets/concert-art/poster-6.webp'
import poster7 from '@/assets/concert-art/poster-7.webp'
import wide0 from '@/assets/concert-art/wide-0.webp'
import wide1 from '@/assets/concert-art/wide-1.webp'
import wide2 from '@/assets/concert-art/wide-2.webp'
import wide3 from '@/assets/concert-art/wide-3.webp'
import wide4 from '@/assets/concert-art/wide-4.webp'
import wide5 from '@/assets/concert-art/wide-5.webp'
import wide6 from '@/assets/concert-art/wide-6.webp'
import wide7 from '@/assets/concert-art/wide-7.webp'
import showcase from '@/data/showcase.json'

// 공연 포스터·배너 그림. 공연 API에 이미지가 없어(ISE-99) 공연 번호로 그림 하나를 고른다.
// 배포 서버를 채우는 공연(data/showcase.json, ISE-302)은 제목으로 공연마다 고른 사진을 쓴다. 사진은 모두 CC0이고
// 출처는 assets/concert-art/photo/CREDITS.md에 있다.
// 그림은 tools/concert-art/art.html이 캔버스로 그린 원본이라 저작권 문제가 없고, 번들에 들어가 /assets/에서
// 해시 이름·1년 캐시로 내려간다(외부 이미지 서버에 기대지 않는다). 포스터 480×720, 배너 1600×667이다.
export interface ConcertArt {
  poster: string
  wide: string
  // 그림 위 글자 색을 정한다. light 그림에는 어두운 글자와 옅은 막을 쓴다
  tone: 'dark' | 'light'
}

export const POSTER_SIZE = { width: 480, height: 720 } as const
export const WIDE_SIZE = { width: 1600, height: 667 } as const

const ARTS: readonly ConcertArt[] = [
  { poster: poster0, wide: wide0, tone: 'dark' },
  { poster: poster1, wide: wide1, tone: 'dark' },
  { poster: poster2, wide: wide2, tone: 'dark' },
  { poster: poster3, wide: wide3, tone: 'dark' },
  { poster: poster4, wide: wide4, tone: 'dark' },
  { poster: poster5, wide: wide5, tone: 'light' },
  { poster: poster6, wide: wide6, tone: 'dark' },
  { poster: poster7, wide: wide7, tone: 'dark' },
]

// 빌드가 사진마다 해시 이름의 주소를 만든다. 키는 이 파일 기준 상대 경로다
const PHOTOS = import.meta.glob<string>('../../assets/concert-art/photo/*.webp', {
  eager: true,
  import: 'default',
})

// 사진이 밝아 어두운 글자를 얹는 공연(포스터 평균 밝기 128 이상)
const LIGHT_PHOTOS = new Set(['acoustic', 'asteria-fancon', 'fanmeet', 'hiphop', 'jazz'])

const SHOWCASE_ARTS = new Map(
  showcase.concerts.map((concert) => [concert.title, photoArt(concert.art)] as const),
)

function photoArt(key: string): ConcertArt {
  const poster = PHOTOS[`../../assets/concert-art/photo/${key}-poster.webp`]
  const wide = PHOTOS[`../../assets/concert-art/photo/${key}-wide.webp`]
  if (poster === undefined || wide === undefined) {
    throw new Error(`공연 사진이 없습니다: ${key}`)
  }
  return { poster, wide, tone: LIGHT_PHOTOS.has(key) ? 'light' : 'dark' }
}

// 대표 공연(시연 공연)의 그림. 로그인 화면과 보일 공연이 없을 때의 배너처럼 특정 공연이 없는 자리에 쓴다
export const FEATURED_ART: ConcertArt = photoArt(
  showcase.concerts.find((concert) => concert.demo)?.art ?? showcase.concerts[0].art,
)

// 제목이 배포 공연과 같으면 그 사진을 쓰고, 아니면 공연 번호로 그린 그림을 고른다.
// 공연 1번이 첫 그림(보랏빛 무대 조명)이 되게 1을 뺀다
export function concertArt(concertId: number, title?: string): ConcertArt {
  const photo = title === undefined ? undefined : SHOWCASE_ARTS.get(title)
  if (photo !== undefined) {
    return photo
  }
  const index = (((concertId - 1) % ARTS.length) + ARTS.length) % ARTS.length
  return ARTS[index]
}
