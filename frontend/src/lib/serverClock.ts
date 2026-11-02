import { useEffect, useReducer } from 'react'

export interface ServerClock {
  // 서버 시각으로 보정한 현재 시각(밀리초)
  now(): number
}

// Note: 응답을 받은 순간에 한 번만 차이를 잰다. receivedAtMillis에는 응답을 받은 시각(React Query의 dataUpdatedAt)을
// 넘긴다. 그린 순간의 Date.now()를 쓰면 캐시된 응답이나 다시 그린 화면에서 캐시 나이만큼 시계가 늦어진다.
// 서버가 시각을 적은 뒤 응답이 오기까지의 시간(왕복의 절반 안팎)만큼은 늦게 잡히지만, 오픈 카운트다운에서는 허용할 수 있다.
export function createServerClock(
  serverTimeIso: string,
  receivedAtMillis: number = Date.now(),
): ServerClock {
  const offsetMillis = new Date(serverTimeIso).getTime() - receivedAtMillis
  return { now: () => Date.now() + offsetMillis }
}

const BROWSER_CLOCK: ServerClock = { now: () => Date.now() }

// 주기마다 다시 그리고, 그릴 때 보정한 현재 시각을 읽는다. clock을 주지 않으면 브라우저 시각이다.
// Note: 시각을 상태로 들고 있지 않고 그릴 때 읽는다. 그래서 clock이 바뀌면 다음 주기를 기다리지 않고 바로 새 시계로 그리고,
// 렌더마다 새 clock 객체를 넘겨도 타이머를 다시 만들지 않는다.
export function useNow(intervalMillis: number, clock: ServerClock = BROWSER_CLOCK): number {
  const [, tick] = useReducer((count: number) => count + 1, 0)

  useEffect(() => {
    const timer = setInterval(tick, intervalMillis)
    // Note: 숨긴 탭은 브라우저가 타이머를 최대 1분까지 늦춘다. 다시 보이는 순간 바로 맞춘다.
    const onVisible = () => {
      if (document.visibilityState === 'visible') {
        tick()
      }
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [intervalMillis])

  return clock.now()
}
