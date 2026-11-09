// WHATWG Server-Sent Events 본문 파서(https://html.spec.whatwg.org/multipage/server-sent-events.html)의 필요한 부분.
// `event`·`data`와 주석만 다루고 `id`·`retry`는 무시한다. 브라우저 `EventSource`는 GET만 되고 인증 헤더를
// 붙일 수 없어, 화면은 `fetch`로 받은 본문 조각을 이 파서에 넣는다.

export interface SseFrame {
  event: string
  data: string
}

export function createSseParser(onFrame: (frame: SseFrame) => void): {
  push(chunk: string): void
  end(): void
} {
  // 줄 끝을 아직 못 본 조각
  let pending = ''
  // 직전 조각이 `\r`로 끝났다. 다음 조각이 `\n`으로 시작하면 같은 줄 끝(`\r\n`)이라 건너뛴다.
  let skipLineFeed = false
  let eventName = ''
  let dataLines: string[] = []

  function dispatch() {
    // data가 하나도 없는 프레임은 보내지 않는다
    if (dataLines.length > 0) {
      onFrame({ event: eventName === '' ? 'message' : eventName, data: dataLines.join('\n') })
    }
    eventName = ''
    dataLines = []
  }

  function readLine(line: string) {
    if (line === '') {
      dispatch()
      return
    }
    if (line.startsWith(':')) {
      return
    }
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) {
      value = value.slice(1)
    }
    if (field === 'event') {
      eventName = value
    } else if (field === 'data') {
      dataLines.push(value)
    }
  }

  return {
    push(chunk) {
      if (chunk === '') {
        return
      }
      let start = 0
      if (skipLineFeed && chunk.startsWith('\n')) {
        start = 1
      }
      skipLineFeed = false
      for (let index = start; index < chunk.length; index++) {
        const char = chunk[index]
        if (char !== '\n' && char !== '\r') {
          continue
        }
        readLine(pending + chunk.slice(start, index))
        pending = ''
        if (char === '\r') {
          if (chunk[index + 1] === '\n') {
            index++
          } else if (index + 1 === chunk.length) {
            skipLineFeed = true
          }
        }
        start = index + 1
      }
      pending += chunk.slice(start)
    },
    // 빈 줄로 끝나지 않은 마지막 프레임은 버린다(명세와 같다). 끊긴 연결의 반쪽 프레임을 답으로 쓰지 않는다.
    end() {
      pending = ''
      eventName = ''
      dataLines = []
      skipLineFeed = false
    },
  }
}
