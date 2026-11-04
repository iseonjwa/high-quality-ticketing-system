import { useMatches } from 'react-router'

// 예매 흐름 화면(대기열 → 좌석 → 결제 → 완료)은 경로의 handle에 단계 번호를 두고, 헤더가 그 번호로 단계 표시를 그린다.
// 단계 이름은 components/ticketing/bookingSteps.ts의 순서와 같다.

interface BookingStepHandle {
  bookingStep: number
}

export function bookingStepHandle(bookingStep: number): BookingStepHandle {
  return { bookingStep }
}

// 지금 화면이 예매 흐름이면 그 단계 번호, 아니면 null이다. 가장 안쪽 경로의 값을 쓴다
export function useBookingStep(): number | null {
  const handle = useMatches().findLast((match) => isBookingStepHandle(match.handle))?.handle
  return isBookingStepHandle(handle) ? handle.bookingStep : null
}

function isBookingStepHandle(handle: unknown): handle is BookingStepHandle {
  return (
    typeof handle === 'object' &&
    handle !== null &&
    typeof (handle as Partial<BookingStepHandle>).bookingStep === 'number'
  )
}
