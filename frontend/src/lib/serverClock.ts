import { useEffect, useState } from 'react'

export interface ServerClock {
  // 서버 시각으로 보정한 현재 시각(밀리초)
  now(): number
}

// Note: 응답을 받은 순간에 한 번만 차이를 잰다. 서버가 시각을 적은 뒤 응답이 오기까지의 시간(왕복의 절반 안팎)만큼
// 서버 시각이 실제보다 늦게 잡힌다. 오픈 카운트다운에서는 허용할 수 있는 오차라 보정하지 않는다.
export function createServerClock(
  serverTimeIso: string,
  receivedAtMillis: number = Date.now(),
): ServerClock {
  const offsetMillis = new Date(serverTimeIso).getTime() - receivedAtMillis
  return { now: () => Date.now() + offsetMillis }
}

const BROWSER_CLOCK: ServerClock = { now: () => Date.now() }

// 주기마다 다시 그리고 보정한 현재 시각을 준다. clock을 주지 않으면 브라우저 시각이다.
export function useNow(intervalMillis: number, clock: ServerClock = BROWSER_CLOCK): number {
  const [now, setNow] = useState(() => clock.now())

  useEffect(() => {
    const timer = setInterval(() => setNow(clock.now()), intervalMillis)
    return () => clearInterval(timer)
  }, [intervalMillis, clock])

  return now
}
