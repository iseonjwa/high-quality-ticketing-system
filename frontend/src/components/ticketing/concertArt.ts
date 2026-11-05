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

// 공연 포스터·배너 그림. 공연 API에 이미지가 없어(ISE-99) 공연 번호로 그림 하나를 고른다.
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

// 공연 1번이 첫 그림(보랏빛 무대 조명)이 되게 1을 뺀다. 배포 시드 공연이 보통 1번이다
export function concertArt(concertId: number): ConcertArt {
  const index = (((concertId - 1) % ARTS.length) + ARTS.length) % ARTS.length
  return ARTS[index]
}
