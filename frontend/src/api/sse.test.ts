import { describe, expect, it } from 'vitest'
import { createSseParser, type SseFrame } from '@/api/sse'

function parse(chunks: string[], { end = true }: { end?: boolean } = {}): SseFrame[] {
  const frames: SseFrame[] = []
  const parser = createSseParser((frame) => frames.push(frame))
  for (const chunk of chunks) {
    parser.push(chunk)
  }
  if (end) {
    parser.end()
  }
  return frames
}

// 같은 본문을 한 글자씩 잘라 넣어도 결과가 같아야 한다
const bySingleChars = (text: string) => Array.from(text)

describe('createSseParser', () => {
  it('빈 줄로 프레임 하나를 끝내고 이벤트 이름과 data를 읽는다', () => {
    expect(parse(['event: delta\ndata: {"text":"안"}\n\n'])).toEqual([
      { event: 'delta', data: '{"text":"안"}' },
    ])
  })

  it('이벤트 이름이 없으면 message다', () => {
    expect(parse(['data: hello\n\n'])).toEqual([{ event: 'message', data: 'hello' }])
  })

  it('콜론 뒤 공백은 하나만 뺀다', () => {
    expect(parse(['data:no-space\n\n', 'data:  two\n\n'])).toEqual([
      { event: 'message', data: 'no-space' },
      { event: 'message', data: ' two' },
    ])
  })

  it('data 여러 줄은 줄바꿈으로 잇는다', () => {
    expect(parse(['data: 첫째\ndata: 둘째\ndata:\n\n'])).toEqual([
      { event: 'message', data: '첫째\n둘째\n' },
    ])
  })

  it('주석, id, retry는 무시하고 data가 없는 프레임은 보내지 않는다', () => {
    expect(
      parse([': keep-alive\n\nid: 7\nretry: 1000\n\nevent: x\n\n: 주석\nevent: y\ndata: 1\n\n']),
    ).toEqual([{ event: 'y', data: '1' }])
  })

  it('줄 끝은 \\n, \\r\\n, \\r 모두 받는다', () => {
    expect(parse(['event: a\r\ndata: 1\r\n\r\nevent: b\rdata: 2\r\r'])).toEqual([
      { event: 'a', data: '1' },
      { event: 'b', data: '2' },
    ])
  })

  it('한 프레임 안에서 이벤트 이름은 다음 프레임에 이어지지 않는다', () => {
    expect(parse(['event: a\ndata: 1\n\ndata: 2\n\n'])).toEqual([
      { event: 'a', data: '1' },
      { event: 'message', data: '2' },
    ])
  })

  it('한 조각에 프레임 여러 개가 와도 모두 보낸다', () => {
    expect(parse(['event: a\ndata: 1\n\nevent: b\ndata: 2\n\nevent: c\ndata: 3\n\n'])).toEqual([
      { event: 'a', data: '1' },
      { event: 'b', data: '2' },
      { event: 'c', data: '3' },
    ])
  })

  it('조각이 필드 이름 중간에서 잘려도 읽는다', () => {
    expect(parse(['ev', 'ent: de', 'lta\nda', 'ta: 값\n', '\n'])).toEqual([
      { event: 'delta', data: '값' },
    ])
  })

  it('조각이 값 중간에서 잘려도 읽는다(여러 바이트 글자 사이 포함)', () => {
    expect(parse(['data: 예매 문', '의 답변', '입니다\n\n'])).toEqual([
      { event: 'message', data: '예매 문의 답변입니다' },
    ])
  })

  it('\\r과 \\n 사이에서 잘려도 한 줄 끝으로 센다', () => {
    // 줄 끝으로 세지 않으면 \n이 빈 줄이 돼 data: 1 뒤에 프레임이 끊긴다
    expect(parse(['event: a\r', '\ndata: 1\r', '\n\r', '\n'])).toEqual([{ event: 'a', data: '1' }])
  })

  it('조각 경계가 \\r 바로 뒤이고 다음이 \\n이 아니면 \\r만으로 줄을 끝낸다', () => {
    expect(parse(['data: 1\r', 'data: 2\r\r'])).toEqual([{ event: 'message', data: '1\n2' }])
  })

  it('한 글자씩 잘라 넣어도 같은 프레임을 낸다', () => {
    const body = 'event: meta\r\ndata: {"a":1}\r\n\r\n: 주석\n\nevent: delta\ndata: 안녕\n\n'
    expect(parse(bySingleChars(body))).toEqual(parse([body]))
    expect(parse([body])).toHaveLength(2)
  })

  it('끝에 빈 줄이 없는 마지막 프레임은 버린다', () => {
    expect(parse(['event: a\ndata: 1\n\nevent: b\ndata: 2\n'])).toEqual([{ event: 'a', data: '1' }])
    expect(parse(['data: 끝없음'])).toEqual([])
  })

  it('end 뒤에는 남은 조각을 이어 붙이지 않는다', () => {
    const frames: SseFrame[] = []
    const parser = createSseParser((frame) => frames.push(frame))
    parser.push('data: 반쪽')
    parser.end()
    parser.push('\n\n')

    expect(frames).toEqual([])
  })
})
