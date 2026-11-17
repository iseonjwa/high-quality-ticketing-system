# 전후 하이라이트 B "좌석" (1600×900). 실행: python highlight-b.py → highlight-b.svg
# 숫자: ../../census/LEDGER.md 1절 히어로 ②, 1-b(확정 예매·상한 거절), 1-c(선점 p95·Hikari 대기), 3-3 좌석 썸네일에서만 가져온다.
from highlight_common import *

WC = (640, 64, 896, 456)                      # 흰 카드(전후 막대)
TW = (936 - 2 * 28) / 3                       # 보조 3칸
SUBS = [(64 + i * (TW + 28), 548, TW, 288) for i in range(3)]
EV = (1024, 548, 512, 288)
c = Canvas("숫자 출처: docs/presentation/brochure/census/LEDGER.md 1절 히어로 ②, 1-b, 1-c, 3-3 좌석",
           [WC, *[(round(x), y, round(w), h) for x, y, w, h in SUBS], EV])

# 왼쪽: 헤드라인 (LEDGER 1절 ②)
c.note("LEDGER 1절 ②: error 사용자 403 / 310대 → 24 / 12, 약 19.8배 감소 → '약 20분의 1'")
c.chip(M, 64, "좌석 선점", 128)
c.text(M, 166, "좌석·선점 단계 실패", 34, 900, ls=-1)
c.run(M - 4, 340, [("약", 60, WHITE, 0.92, 0), ("20", 170, O, None, 12), ("분의", 76, O, None, 6), ("1", 170, O, None, 10)])
c.text(M, 394, "실패 사용자 403명·310명대 → 24명·12명", 26, 800)
c.conds(M, 434, ["조건: 현실 모드 · 같은 날(11/16) 묶음 비교 · 각 2회",
                 "사용자 3,000명 · 15,000석 · Mock PG 300ms",
                 "상한 64 · 풀 5 · 결제 실패는 별도 집계",
                 "여러 개선의 묶음 비교 · 개별 몫은 나누지 않음"])

# 흰 카드: 전 2회·후 2회 막대
x, y, w, h = WC
c.white_card(*WC)
BASE = 404
c.note("LEDGER 1절 ② 전 → 후: v1 R1·R2 403 / 310대 → v2 R1·R2 24 / 12. error = 대기열 통과 뒤 좌석 조회·선점 단계에서 처리하지 못해 끝난 사용자 수")
c.text(x + 36, y + 50, "좌석 조회·선점 단계에서 끝난 사용자(명)", 25, 800, INK)
c.text(x + 36, y + 78, "대기열 통과 뒤 이 단계에서 처리하지 못한 사용자 수 · 결제 실패·확정 시간 초과는 별도 집계", 18, 600, BODY)
k = 212 / 403
bx = x + 138
for i, (v, label, lab2, fill, vfill) in enumerate([(403, "403", "전 1회", GREY, INK), (310, "310명대", "전 2회", GREY, INK),
                                                    (24, "24", "후 1회", P, P), (12, "12", "후 2회", P, P)]):
    c.bar(bx + i * 140 + (80 if i > 1 else 0), BASE, 120, v * k, fill, label, lab2, 34, vfill)
c.add(f'<line x1="{x + 100}" y1="{BASE}" x2="{x + w - 100}" y2="{BASE}" stroke="{LINE}" stroke-width="2"/>')
c.add(f'<line x1="{x + 36}" y1="{y + h - 54}" x2="{x + w - 36}" y2="{y + h - 54}" stroke="{LINE}" stroke-width="1.5"/>')
c.note("LEDGER 1절 ② 주의·1-b 상한 거절 감소: 평균 6,666 → 5,033건 남음. '310대'는 근삿값")
c.text(x + 36, y + h - 22, f'남은 것: 입구 상한 거절은 평균 <tspan font-weight="900" fill="{INK}">6,666 → 5,033건</tspan>으로 남아 있음 · 310명대는 근삿값(원문 표기)', 18, 600, BODY_DARK)

# 보조 3칸
subs = [
    # LEDGER 1-c·3-3 썸네일: Hikari 대기 12 / 14 → 0 / 0
    ("DB 커넥션 대기 최대", [("12 · 14", 40, WHITE, 0.7, 0)], [("→ ", 40, WHITE, None, 0), ("0 · 0", 54, GL, None, 0)],
     ["예매 서비스 DB 커넥션 풀 5", "전 2회 → 후 2회"]),
    # LEDGER 1-c: 선점 p95 242.7 / 196 → 44.5 / 31.4ms, 409 포함 전체 선점 응답 p95
    ("선점 응답 p95", [("242.7 · 196ms", 30, WHITE, 0.7, 0)], [("→ ", 30, WHITE, None, 0), ("44.5 · 31.4ms", 30, WHITE, None, 0)],
     ["{409 포함 전체 선점 응답}", "성공한 선점만의 값은 아님"]),
    # LEDGER 1-b 확정 예매 +10.7%: 평균 1,971.5 → 2,181.5, 범위 +7.0~14.5%
    ("확정 예매(2회 평균)", [("1,971.5건", 34, WHITE, 0.7, 0)], [("→ ", 34, WHITE, None, 0), ("2,181.5건", 34, WHITE, None, 0)],
     ["{+10.7%}(범위 +7.0~14.5%)", "개선 전 두 회 차이 5%보다 큼"]),
]
for (sx, sy, sw, sh), (name, l1, l2, foot) in zip(SUBS, subs):
    c.glass(sx, sy, sw, sh)
    c.text(sx + 26, sy + 50, name, 24, 800, WHITE, 0.92)
    c.run(sx + 26, sy + 112, l1, ls=-1)
    c.run(sx + 26, sy + 170, l2, ls=-1)
    c.conds(sx + 26, sy + 222, foot, 18, 28, 0.75)

# 근거 화면 (LEDGER 3-3 "쓸 그림")
c.evidence(*EV, "DB 커넥션 대기가 생기는 모양", "11/15 균일 도착 실행 122(개선 전 구성) · 표본 최대 10",
           "docs/presentation/performance/img/2026-11-15/hikari-pending-122-123.png", (40, 125, 1760, 470), 480, 96,
           "위 막대(11/16 전후 실행)의 화면은 아님 · 5초 간격 표본")
c.save("highlight-b.svg")
