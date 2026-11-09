import { http } from 'msw'
import { fail, ok, readBody, requireUser } from '@/mocks/respond'

// 내부 AI 상담(docs/design/assistant/README.md 2절)의 목. 서버의 키워드 검색 대신 질문 낱말로 절을 고른다.
// 질문에 "검색"이 들어가면 LLM이 꺼진 검색 모드, "지연"이 들어가면 LLM 장애로 강등된 검색 모드를 돌려준다.

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

export const assistantHandlers = [
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
