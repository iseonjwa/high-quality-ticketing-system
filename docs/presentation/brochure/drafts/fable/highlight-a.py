# 전후 하이라이트 A "입구(Gateway)" (1600×900). 실행: python highlight-a.py → highlight-a.svg
# 숫자: ../../census/LEDGER.md 1절 히어로 ③, 3-1 Gateway 썸네일·타임라인(11/11 행)에서만 가져온다.
from highlight_common import *

WC = (640, 64, 896, 456)                      # 흰 카드(전후 막대)
TW = (936 - 2 * 28) / 3                       # 서사 3칸
STEPS = [(64 + i * (TW + 28), 548, TW, 288) for i in range(3)]
EV = (1024, 548, 512, 288)
c = Canvas("숫자 출처: docs/presentation/brochure/census/LEDGER.md 1절 히어로 ③, 3-1 Gateway",
           [WC, *[(round(x), y, round(w), h) for x, y, w, h in STEPS], EV])

# 왼쪽: 헤드라인 (LEDGER 1절 ③)
c.note("LEDGER 1절 ③: 굿풋 1,304 → 1,696(+30.1%), 조건 문구")
c.chip(M, 64, "입구 · Gateway", 178)
c.text(M, 166, "같은 서버로 제때 끝낸 순번 조회", 34, 900, ls=-1)
c.run(M - 6, 340, [("+30", 180, O, None, 0), ("%", 100, O, None, 6)])
c.text(M, 394, "200ms 안 성공 1,304 → 1,696건/초", 27, 800)
c.conds(M, 434, ["조건: 순번 조회 초당 2,000건 · 대기자 10,000명",
                 "동시 처리 상한 사실상 없음(500) → 64",
                 "같은 날 · 같은 배포 · 회차당 1회 · 4 vCPU 서버 1대",
                 "보조: 성공 응답 p95 571 → 87ms"])

# 흰 카드: 전후 막대 + 대가
x, y, w, h = WC
c.white_card(*WC)
BASE = 418
c.note("LEDGER 1절 ③ 전 → 후: 굿풋 1,304 → 1,696건/초")
c.text(x + 36, y + 50, "제때 끝낸 요청(건/초)", 25, 800, INK)
c.text(x + 36, y + 78, "200 응답이면서 200ms 안에 끝난 요청", 18, 600, BODY)
k = 204 / 1696
c.bar(x + 90, BASE, 150, 1304 * k, GREY, "1,304", "전 · 상한 500", 36)
c.bar(x + 290, BASE, 150, 1696 * k, P, "1,696", "후 · 상한 64", 36, P)
c.add(f'<line x1="{x + 60}" y1="{BASE}" x2="{x + 470}" y2="{BASE}" stroke="{LINE}" stroke-width="2"/>')
c.add(f'<line x1="{x + 520}" y1="{y + 30}" x2="{x + 520}" y2="{BASE + 34}" stroke="{LINE}" stroke-width="1.5"/>')
c.note("LEDGER 1절 ③ 각주 필수: 상한 거절 1,678 → 8,715건, 성공률 89.04 → 85.17%, 서버 CPU 98% 그대로")
c.text(x + 556, y + 50, "대가: 상한 거절(건)", 25, 800, INK)
c.text(x + 556, y + 78, "넘친 요청은 바로 503으로 돌려보냄", 18, 600, BODY)
k2 = 204 / 8715
c.bar(x + 590, BASE, 110, 1678 * k2, GREY, "1,678", "전", 32)
c.bar(x + 740, BASE, 110, 8715 * k2, RED, "8,715", "후", 32, RED)
c.add(f'<line x1="{x + 566}" y1="{BASE}" x2="{x + 874}" y2="{BASE}" stroke="{LINE}" stroke-width="2"/>')
c.text(x + 36, y + h - 20, f'같은 단계 성공률 <tspan font-weight="900" fill="{INK}">89.04 → 85.17%</tspan> · 거절을 늘려 줄을 짧게 한 장치 · 서버 CPU는 98%로 그대로', 18, 600, BODY_DARK)

# 서사 3칸 (LEDGER 3-1 썸네일 보조 1, 타임라인 11/11 행)
c.note("LEDGER 3-1 보조 1·타임라인 11/11: 강제 종료 3 → 0(768 → 1,024MiB, 시도 3회 대 재실행 1회), p95 435 → 752ms / 6차 L500 → L64")
steps = [("죽는 실패", WHITE, ["메모리 768MiB", "강제 종료(OOM) {3회}"], None),
         ("느린 실패", WHITE, ["메모리만 1,024MiB로 늘림", "강제 종료 {0회}, 대신", "p95 {435 → 752ms}로 느려짐"], "시도 3회 대 재실행 1회 · 같은 날"),
         ("맨 앞 상한", O, ["동시 처리 64개로 제한", "넘친 요청은 맨 앞에서 거절", "제때 끝낸 요청 {+30%}"], "순번 조회 한 API의 값")]
for i, ((sx, sy, sw, sh), (name, col, lines, foot)) in enumerate(zip(STEPS, steps)):
    c.glass(sx, sy, sw, sh)
    c.add(f'<circle cx="{sx + 42:.1f}" cy="{sy + 50}" r="18" fill="{WHITE}"/>')
    c.text(sx + 42, sy + 58, str(i + 1), 22, 900, P, anchor="middle")
    c.text(sx + 72, sy + 61, name, 31, 900, col, ls=-1)
    c.conds(sx + 26, sy + 112, lines, 20, 30, 0.85)
    if foot:
        c.text(sx + 26, sy + sh - 24, foot, 16, 600, WHITE, 0.7)
    if i < 2:   # 칸 사이 화살표
        ax = sx + sw + 14
        c.add(f'<path d="M{ax - 6:.1f} {sy + 132} l10 12 l-10 12" fill="none" stroke="{WHITE}" stroke-opacity="0.8" stroke-width="4" stroke-linecap="round" stroke-linejoin="round"/>')

# 근거 화면 (LEDGER 3-1 "쓸 그림")
c.evidence(*EV, "상한 64에 붙는 처리 중 요청", "11/13 예매 흐름(O1) 측정 화면 · 위 막대와는 다른 실행",
           "docs/presentation/performance/2026-11-13-safety-net-limit-inflight.png", (64, 208, 3104, 1600), 420, 192)
c.save("highlight-a.svg")
