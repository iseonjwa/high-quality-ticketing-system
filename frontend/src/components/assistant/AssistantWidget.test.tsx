import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { routes } from '@/app/routes'
import { fail } from '@/mocks/respond'
import { mockServer } from '@/mocks/server'
import { recordRequests, signInAsAdmin, signInAsDemo } from '@/test/api'
import { renderRoutes, renderRoutesReady } from '@/test/render'

const STREAM_PATH = '/api/v1/assistant/messages/stream'
const ON_SALE_SCHEDULE_ID = 31

const launcher = () => screen.getByRole('button', { name: '예매 문의 열기' })
const panel = () => screen.getByRole('dialog', { name: '예매 문의' })
const conversation = () => within(panel()).getByRole('list', { name: '대화' })

async function openWidget(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: '예매 문의 열기' }))
  return panel()
}

async function ask(user: ReturnType<typeof userEvent.setup>, question: string) {
  await user.type(within(panel()).getByLabelText('질문'), question)
  await user.click(within(panel()).getByRole('button', { name: '보내기' }))
}

// 답을 내보내다 멈춘 채로 두는 스트림. release()를 부르기 전까지 sources·done을 보내지 않는다.
// 클라이언트가 끊었는지(스트림 cancel 또는 요청 signal)는 wasCut()으로 본다
function holdStream() {
  const encoder = new TextEncoder()
  let release: () => void = () => {}
  const released = new Promise<void>((resolve) => {
    release = resolve
  })
  let cut = false
  mockServer.use(
    http.post(STREAM_PATH, ({ request }) => {
      request.signal.addEventListener('abort', () => {
        cut = true
      })
      const stream = new ReadableStream<Uint8Array>({
        async start(controller) {
          controller.enqueue(
            encoder.encode(
              'event: meta\ndata: {"mode":"AI","fallbackReason":null}\n\n' +
                'event: delta\ndata: {"text":"좌석을 잡아 둔 "}\n\n',
            ),
          )
          await released
          if (cut) {
            return
          }
          controller.enqueue(
            encoder.encode(
              'event: sources\ndata: {"sources":[]}\n\n' +
                'event: done\ndata: {"answer":"좌석을 잡아 둔 뒤에 결제해 주세요","mode":"AI","fallbackReason":null}\n\n',
            ),
          )
          controller.close()
        },
        cancel() {
          cut = true
        },
      })
      return new HttpResponse(stream, { headers: { 'Content-Type': 'text/event-stream' } })
    }),
  )
  return { release, wasCut: () => cut }
}

// meta 한 프레임만 보내고 done 없이 닫는 스트림(받은 글 없이 끊긴 경우)
function metaOnlyStream() {
  mockServer.use(
    http.post(
      STREAM_PATH,
      () =>
        new HttpResponse('event: meta\ndata: {"mode":"AI","fallbackReason":null}\n\n', {
          headers: { 'Content-Type': 'text/event-stream' },
        }),
    ),
  )
}

describe('예매 문의 위젯', () => {
  it('오른쪽 아래 버튼으로 열고, 열기만 해서는 요청하지 않는다. 예시 질문을 누르면 스트리밍 답과 출처를 보이고 끝났을 때 한 번 알린다', async () => {
    const user = userEvent.setup()
    const requests = recordRequests(STREAM_PATH)
    await signInAsDemo()
    renderRoutes(routes, '/')

    await user.click(await screen.findByRole('button', { name: '예매 문의 열기' }))
    const dialog = panel()
    expect(within(dialog).getByLabelText('질문')).toHaveFocus()
    expect(requests).toHaveLength(0)

    await user.click(within(dialog).getByRole('button', { name: '결제가 늦게 되면 환불되나요?' }))

    // 출처 발췌에도 같은 문장이 있어 답 본문(p)만 찾는다
    expect(
      await within(conversation()).findByText(/자동으로 환불돼요/, { selector: 'p' }),
    ).toBeInTheDocument()
    expect(within(conversation()).getByText('AI 답변')).toBeInTheDocument()
    expect(
      within(conversation()).getByText('결제 > 시간이 지난 뒤 승인된 결제'),
    ).toBeInTheDocument()
    await waitFor(() =>
      expect(within(dialog).getByRole('status')).toHaveTextContent(/^답변: .*자동으로 환불돼요/),
    )
    expect(requests).toHaveLength(1)
  })

  it('받는 동안에는 보내기 대신 멈추기 버튼이 있고, 멈추면 받던 글을 두고 "답변을 멈췄어요"를 보인다', async () => {
    const user = userEvent.setup()
    const { release } = holdStream()
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)

    await ask(user, '환불은 언제 되나요?')

    expect(await within(conversation()).findByText('좌석을 잡아 둔')).toBeInTheDocument()
    // 받는 동안의 답은 aria-busy로 묶어 낭독기가 조각마다 읽지 않게 한다
    expect(within(conversation()).getAllByRole('listitem')[0]).toHaveAttribute('aria-busy', 'true')
    expect(within(panel()).queryByRole('button', { name: '보내기' })).not.toBeInTheDocument()
    await user.click(within(panel()).getByRole('button', { name: '답변 멈추기' }))

    expect(await within(conversation()).findByText('답변을 멈췄어요')).toBeInTheDocument()
    expect(within(conversation()).getByText('좌석을 잡아 둔')).toBeInTheDocument()
    expect(within(panel()).getByRole('button', { name: '보내기' })).toBeInTheDocument()
    release()
  })

  it('LLM이 꺼져 검색 모드면 이유와 관련 안내를 보인다', async () => {
    const user = userEvent.setup()
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)

    await ask(user, '대기 순번 검색')

    expect(
      await within(conversation()).findByText('AI 답변이 꺼져 있어 관련 안내만 보여 드려요.'),
    ).toBeInTheDocument()
    expect(within(conversation()).getByText('관련 안내')).toBeInTheDocument()
    expect(within(conversation()).getByText('대기열 > 순번과 입장')).toBeInTheDocument()
  })

  it('답을 받다가 강등(fallback)되면 받던 AI 글을 버리고 검색 모드 답으로 바꾼다', async () => {
    const user = userEvent.setup()
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)

    await ask(user, '대기 순번 끊김')

    expect(
      await within(conversation()).findByText('AI 답변이 잠시 어려워 관련 안내만 보여 드려요.'),
    ).toBeInTheDocument()
    expect(within(conversation()).getByText('관련 안내')).toBeInTheDocument()
    expect(within(conversation()).queryByText('AI 답변')).not.toBeInTheDocument()
    expect(
      within(conversation()).getByText('관련 안내를 찾았어요. 아래 내용을 확인해 주세요.'),
    ).toBeInTheDocument()
  })

  it('답이 끝나기 전에 연결이 끊기면 받은 글을 두고 알리며, [다시 묻기]로 같은 질문을 다시 보낸다(자동으로 다시 보내지 않는다)', async () => {
    const user = userEvent.setup()
    const requests = recordRequests(STREAM_PATH)
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)

    await ask(user, '환불 중단')

    expect(await within(conversation()).findByText('답변이 중간에 끊겼어요')).toBeInTheDocument()
    expect(requests).toHaveLength(1)
    await user.click(within(conversation()).getByRole('button', { name: '다시 묻기' }))

    await waitFor(() => expect(requests).toHaveLength(2))
    expect(within(conversation()).getAllByText('환불 중단')).toHaveLength(2)
  })

  it('요청 제한(429)이면 기다릴 시간을 알리고 [다시 묻기]를 두지 않는다', async () => {
    const user = userEvent.setup()
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)

    await ask(user, '질문을 많이 하면')

    expect(await within(conversation()).findByText('질문이 많아요')).toBeInTheDocument()
    expect(within(conversation()).getByText('30초 뒤 다시 물어봐 주세요.')).toBeInTheDocument()
    expect(
      within(conversation()).queryByRole('button', { name: '다시 묻기' }),
    ).not.toBeInTheDocument()
  })

  it('Enter로 보내고 Shift+Enter는 줄만 바꾼다', async () => {
    const user = userEvent.setup()
    const requests = recordRequests(STREAM_PATH)
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)
    const input = within(panel()).getByLabelText('질문')

    await user.type(input, '환불{Shift>}{Enter}{/Shift}언제')
    expect(input).toHaveValue('환불\n언제')
    expect(requests).toHaveLength(0)
    await user.keyboard('{Enter}')

    await waitFor(() => expect(requests).toHaveLength(1))
    expect(input).toHaveValue('')
  })

  it('Esc나 닫기 버튼으로 닫으면 연 버튼으로 초점이 돌아가고, 닫아도 대화는 남는다', async () => {
    const user = userEvent.setup()
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)
    await ask(user, '환불은 언제 되나요?')
    await waitFor(() => expect(within(panel()).getByRole('status')).toHaveTextContent(/^답변: /))

    await user.keyboard('{Escape}')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(launcher()).toHaveFocus()
    await user.click(launcher())
    expect(within(conversation()).getByText('환불은 언제 되나요?')).toBeInTheDocument()
    await user.click(within(panel()).getByRole('button', { name: '예매 문의 닫기' }))
    expect(launcher()).toHaveFocus()
  })

  it('헤더 메뉴의 [예매 문의]로도 연다', async () => {
    const user = userEvent.setup()
    await signInAsDemo()
    renderRoutes(routes, '/')

    const nav = await screen.findByRole('navigation', { name: '둘러보기' })
    await user.click(within(nav).getByRole('button', { name: '예매 문의' }))

    expect(panel()).toBeInTheDocument()
  })

  it('로그인하지 않았으면 질문 칸 대신 로그인 안내를 보인다', async () => {
    const user = userEvent.setup()
    renderRoutes(routes, '/')
    await openWidget(user)

    expect(within(panel()).getByRole('link', { name: '로그인하고 물어보기' })).toHaveAttribute(
      'href',
      '/login',
    )
    expect(within(panel()).queryByLabelText('질문')).not.toBeInTheDocument()
  })

  it.each([
    ['로그인 화면', '/login', () => Promise.resolve()],
    ['운영자 화면', '/admin', signInAsAdmin],
    ['예매 흐름(대기열)', `/schedules/${ON_SALE_SCHEDULE_ID}/queue`, signInAsDemo],
  ])('%s에는 위젯을 두지 않는다', async (_name, path, signIn) => {
    await signIn()
    // 세 화면 모두 lazy 경로다. 화면 코드를 받아 그린 뒤에 확인한다
    await renderRoutesReady(routes, path)
    await act(() => Promise.resolve())
    expect(screen.queryByRole('button', { name: '예매 문의 열기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '예매 문의' })).not.toBeInTheDocument()
  })

  it('받는 중에 위젯을 두지 않는 화면으로 옮기면 창을 닫고 받던 답을 끊는다. 돌아와 열면 멈춘 답이 남아 있다', async () => {
    const user = userEvent.setup()
    const { wasCut } = holdStream()
    await signInAsDemo()
    const { router } = renderRoutes(routes, '/')
    await openWidget(user)
    await ask(user, '환불은 언제 되나요?')
    expect(await within(conversation()).findByText('좌석을 잡아 둔')).toBeInTheDocument()

    await act(() => router.navigate(`/schedules/${ON_SALE_SCHEDULE_ID}/queue`))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(wasCut()).toBe(true))
    await act(() => router.navigate('/'))
    await user.click(await screen.findByRole('button', { name: '예매 문의 열기' }))
    expect(await within(conversation()).findByText('답변을 멈췄어요')).toBeInTheDocument()
    expect(within(conversation()).getByText('좌석을 잡아 둔')).toBeInTheDocument()
  })

  it('로그아웃하면 대화를 지운다. 다시 로그인해 열면 예시 질문이 보인다', async () => {
    const user = userEvent.setup()
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)
    await ask(user, '환불은 언제 되나요?')
    await waitFor(() => expect(within(panel()).getByRole('status')).toHaveTextContent(/^답변: /))

    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    await screen.findByRole('link', { name: '회원가입' })
    await user.click(within(panel()).getByRole('button', { name: '예매 문의 닫기' }))
    await act(() => signInAsDemo())
    await user.click(await screen.findByRole('button', { name: '예매 문의 열기' }))

    expect(within(panel()).queryByRole('list', { name: '대화' })).not.toBeInTheDocument()
    expect(
      within(panel()).getByRole('button', { name: '결제가 늦게 되면 환불되나요?' }),
    ).toBeInTheDocument()
  })

  it('한글 조합 중의 Enter는 글자를 끝내는 것이라 보내지 않는다', async () => {
    const user = userEvent.setup()
    const requests = recordRequests(STREAM_PATH)
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)
    const input = within(panel()).getByLabelText('질문')
    await user.type(input, '환불')

    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
    await act(() => Promise.resolve())

    expect(requests).toHaveLength(0)
    expect(input).toHaveValue('환불')
  })

  it('상담 서비스가 꺼져 있으면(503) "지금 상담을 쓸 수 없어요"와 예매 안내 위치, [다시 묻기]를 보인다', async () => {
    const user = userEvent.setup()
    mockServer.use(
      http.post(STREAM_PATH, () =>
        fail(503, 'SERVICE_UNAVAILABLE', '일시적으로 서비스를 사용할 수 없습니다'),
      ),
    )
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)

    await ask(user, '환불은 언제 되나요?')

    expect(await within(conversation()).findByText('지금 상담을 쓸 수 없어요')).toBeInTheDocument()
    expect(within(conversation()).getByText(/알아 두세요/)).toBeInTheDocument()
    expect(within(conversation()).getByRole('button', { name: '다시 묻기' })).toBeInTheDocument()
  })

  it('질문이 거절되면(400) 대화에서 빼고 입력 칸에 질문을 다시 채우며 이유를 alert로 알린다', async () => {
    const user = userEvent.setup()
    mockServer.use(
      http.post(STREAM_PATH, () =>
        fail(400, 'VALIDATION_FAILED', '요청 값이 올바르지 않습니다', {
          errors: [{ field: 'question', reason: '질문에 쓸 수 없는 글자가 있어요' }],
        }),
      ),
    )
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)

    await ask(user, '고쳐 보낼 질문')

    expect(await within(panel()).findByRole('alert')).toHaveTextContent(
      '질문에 쓸 수 없는 글자가 있어요',
    )
    expect(within(panel()).getByLabelText('질문')).toHaveValue('고쳐 보낼 질문')
    expect(within(panel()).queryByRole('list', { name: '대화' })).not.toBeInTheDocument()
  })

  it('질문이 비어 있으면 보내기 버튼은 aria-disabled이고(disabled 속성은 없다) 눌러도 Enter를 쳐도 요청하지 않는다', async () => {
    const user = userEvent.setup()
    const requests = recordRequests(STREAM_PATH)
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)
    const send = within(panel()).getByRole('button', { name: '보내기' })

    expect(send).toHaveAttribute('aria-disabled', 'true')
    expect(send).not.toHaveAttribute('disabled')
    await user.click(send)
    await user.type(within(panel()).getByLabelText('질문'), '{Enter}')
    await act(() => Promise.resolve())

    expect(requests).toHaveLength(0)
  })

  it('meta만 받고 done 없이 끊기면 받은 글이 없어도 "답변이 중간에 끊겼어요"를 보인다', async () => {
    const user = userEvent.setup()
    metaOnlyStream()
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)

    await ask(user, '환불은 언제 되나요?')

    expect(await within(conversation()).findByText('답변이 중간에 끊겼어요')).toBeInTheDocument()
    expect(within(conversation()).queryByText('지금 상담을 쓸 수 없어요')).not.toBeInTheDocument()
  })

  it('창이 열린 채 헤더의 [예매 문의]를 누르면 질문 칸으로 초점을 옮긴다', async () => {
    const user = userEvent.setup()
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)
    within(panel()).getByRole('button', { name: '예매 문의 닫기' }).focus()

    const nav = screen.getByRole('navigation', { name: '둘러보기' })
    await user.click(within(nav).getByRole('button', { name: '예매 문의' }))

    expect(within(panel()).getByLabelText('질문')).toHaveFocus()
    expect(screen.getAllByRole('dialog')).toHaveLength(1)
  })

  it('받는 동안 알림 영역은 비어 있고, 답이 끝나면 한 번 "답변: …"으로 바뀐다', async () => {
    const user = userEvent.setup()
    const { release } = holdStream()
    await signInAsDemo()
    renderRoutes(routes, '/')
    await openWidget(user)

    await ask(user, '환불은 언제 되나요?')

    expect(await within(conversation()).findByText('좌석을 잡아 둔')).toBeInTheDocument()
    expect(within(panel()).getByRole('status')).toBeEmptyDOMElement()
    release()
    await waitFor(() =>
      expect(within(panel()).getByRole('status')).toHaveTextContent(
        '답변: 좌석을 잡아 둔 뒤에 결제해 주세요',
      ),
    )
  })
})
