import { http, HttpResponse } from 'msw'
import { fail, ok, readBody, requireUser } from '@/mocks/respond'

// 내부 AI 상담(docs/design/assistant/README.md 2절)의 목. 서버의 키워드 검색 대신 질문 낱말로 절을 고른다.
// 질문에 "검색"이 들어가면 LLM이 꺼진 검색 모드, "지연"이 들어가면 LLM 장애로 강등된 검색 모드를 돌려준다.
// 스트리밍(2-1절)은 같은 낱말에 더해 "끊김"(답하다 검색 모드로 강등), "중단"(done 없이 연결이 끊김),
// "많이"(요청 제한 429)를 고른다.

const QUESTION_MAX_LENGTH = 500

interface MockSection {
  id: string
  title: string
  excerpt: string
  keywords: string[]
}

const SECTIONS: MockSection[] = [
  {
    id: 'open-time.when',
    title: '오픈 시각 > 언제 열리나요',
    excerpt: '회차마다 예매 오픈 시각이 달라요. 공연 상세의 회차 표에서 오픈 시각을 확인해 주세요.',
    keywords: ['오픈', '시각', '언제', '열려'],
  },
  {
    id: 'purchase-limit.per-user',
    title: '매수 제한 > 한 사람당 매수',
    excerpt:
      '회차마다 한 사람이 예매할 수 있는 매수가 정해져 있고, 공연 상세에 "1인 최대 N매"로 보여요. 이미 예매한 매수도 함께 세요.',
    keywords: ['매수', '몇 장', '최대', '제한'],
  },
  {
    id: 'payment.auto-refund',
    title: '결제 > 시간이 지난 뒤 승인된 결제',
    excerpt:
      '좌석을 잡아 둔 시간이 지난 뒤에 결제가 승인되면 예매는 확정되지 않고 자동으로 환불돼요.',
    keywords: ['환불', '취소', '돈'],
  },
  {
    id: 'queue.position',
    title: '대기열 > 순번과 입장',
    excerpt:
      '예매하기를 누르면 대기열에 들어가요. 순번이 되면 좌석 화면으로 넘어가고, 창을 닫으면 순번을 잃을 수 있어요.',
    keywords: ['대기', '순번', '줄', '입장'],
  },
]

function pick(question: string): MockSection[] {
  const matched = SECTIONS.filter((section) =>
    section.keywords.some((keyword) => question.includes(keyword)),
  )
  return (matched.length > 0 ? matched : SECTIONS).slice(0, 3)
}

function toSource({ id, title, excerpt }: MockSection) {
  return { id, title, excerpt }
}

const SEARCH_ANSWER = '관련 안내를 찾았어요. 아래 내용을 확인해 주세요.'

// 조각 사이 지연. 브라우저 목에서는 글자가 흐르는 것이 보이게 두고, 테스트에서는 없앤다.
const CHUNK_DELAY_MILLIS = import.meta.env.MODE === 'test' ? 0 : 40
const CHUNK_SIZE = 3

type StreamEvent = [event: string, data: unknown]

function chunksOf(text: string): string[] {
  const chunks: string[] = []
  for (let start = 0; start < text.length; start += CHUNK_SIZE) {
    chunks.push(text.slice(start, start + CHUNK_SIZE))
  }
  return chunks
}

const deltas = (text: string): StreamEvent[] =>
  chunksOf(text).map((chunk) => ['delta', { text: chunk }])

// 질문 낱말로 이벤트 순서를 고른다. 마지막이 done이 아니면 서버가 done 없이 연결을 닫은 것이다.
function streamScenario(question: string, sections: MockSection[]): StreamEvent[] {
  const sources = sections.map(toSource)
  const [first] = sections
  const answer = `${first.excerpt} 더 궁금한 점은 공연 상세의 예매 안내를 함께 봐 주세요.`
  const searchDone = (fallbackReason: string): StreamEvent[] => [
    ['sources', { sources }],
    ['done', { answer: SEARCH_ANSWER, mode: 'SEARCH', fallbackReason }],
  ]

  if (question.includes('검색')) {
    return [
      ['meta', { mode: 'SEARCH', fallbackReason: 'LLM_DISABLED' }],
      ...searchDone('LLM_DISABLED'),
    ]
  }
  if (question.includes('지연')) {
    return [
      ['meta', { mode: 'SEARCH', fallbackReason: 'LLM_UNAVAILABLE' }],
      ...searchDone('LLM_UNAVAILABLE'),
    ]
  }
  if (question.includes('끊김')) {
    return [
      ['meta', { mode: 'AI', fallbackReason: null }],
      ...deltas(answer).slice(0, 4),
      ['fallback', { fallbackReason: 'LLM_UNAVAILABLE' }],
      ...searchDone('LLM_UNAVAILABLE'),
    ]
  }
  if (question.includes('중단')) {
    return [['meta', { mode: 'AI', fallbackReason: null }], ...deltas(answer).slice(0, 4)]
  }
  return [
    ['meta', { mode: 'AI', fallbackReason: null }],
    ...deltas(answer),
    ['sources', { sources: [toSource(first)] }],
    ['done', { answer, mode: 'AI', fallbackReason: null }],
  ]
}

function eventStream(events: StreamEvent[]): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  let cancelled = false
  return new ReadableStream({
    async start(controller) {
      for (const [event, data] of events) {
        if (cancelled) {
          return
        }
        controller.enqueue(encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`))
        if (CHUNK_DELAY_MILLIS > 0) {
          await new Promise((resolve) => setTimeout(resolve, CHUNK_DELAY_MILLIS))
        }
      }
      if (!cancelled) {
        controller.close()
      }
    },
    cancel() {
      cancelled = true
    },
  })
}

export const assistantHandlers = [
  http.post('/api/v1/assistant/messages/stream', async ({ request }) => {
    requireUser(request)
    const { question } = await readBody(request)
    if (
      typeof question !== 'string' ||
      question.trim() === '' ||
      question.length > QUESTION_MAX_LENGTH
    ) {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
        errors: [{ field: 'question', reason: '1자 이상 500자 이하로 입력해 주세요' }],
      })
    }
    // 요청 제한은 스트림 전에 JSON으로 거절한다(2-1절)
    if (question.includes('많이')) {
      return fail(429, 'RATE_LIMITED', '요청이 너무 많습니다', { headers: { 'Retry-After': '30' } })
    }
    return new HttpResponse(eventStream(streamScenario(question, pick(question))), {
      headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
    })
  }),

  http.post('/api/v1/assistant/messages', async ({ request }) => {
    requireUser(request)
    const { question } = await readBody(request)
    if (
      typeof question !== 'string' ||
      question.trim() === '' ||
      question.length > QUESTION_MAX_LENGTH
    ) {
      return fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
        errors: [{ field: 'question', reason: '1자 이상 500자 이하로 입력해 주세요' }],
      })
    }

    const sections = pick(question)
    if (question.includes('검색') || question.includes('지연')) {
      return ok({
        answer: '관련 안내를 찾았어요. 아래 내용을 확인해 주세요.',
        mode: 'SEARCH',
        fallbackReason: question.includes('검색') ? 'LLM_DISABLED' : 'LLM_UNAVAILABLE',
        sources: sections.map(toSource),
      })
    }
    const [first] = sections
    return ok({
      answer: `${first.excerpt} 더 궁금한 점은 공연 상세의 예매 안내를 함께 봐 주세요.`,
      mode: 'AI',
      fallbackReason: null,
      sources: [toSource(first)],
    })
  }),
]
