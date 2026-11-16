# 전후 하이라이트 C "추첨 자동결제" (1600×900). 실행: python highlight-c.py → highlight-c.svg
# 헤드라인·조건: ../../census/LEDGER.md 1절 히어로 ④, 3-5 추첨 썸네일.
# 곡선의 점: 원문 숫자만 찍는다(점 사이 직선은 보간).
#   전(11/10 L2-a 3회): docs/design/lottery/numbers-and-measurement.md 3-3절 "L2-a 3회 상세"의 진행 조회와 버킷 표
#   후(11/13 1회): 같은 문서 3-5절(당첨 1,992, 마지막 결제 206~229초 버킷)과 그 측정 기록 docs/context/remeasure-2026-11-13.md 2절 1회 상세
from highlight_common import *

WC = (580, 64, 956, 772)
c = Canvas("숫자 출처: LEDGER.md 1절 히어로 ④, 3-5 추첨 / 곡선 점: numbers-and-measurement.md 3-3절·3-5절, remeasure-2026-11-13.md 2절", [WC])


def sec(clock, draw):
    """'HH:MM:SS' 두 시각의 차(초). 진행 조회 시각 − 추첨 시각(계산값)."""
    f = lambda s: sum(int(v) * k for v, k in zip(s.split(":"), (3600, 60, 1)))
    return f(clock) - f(draw)


# 전: 추첨 01:40:51Z. 진행 조회(PAID 누적, 1분 간격)와 결제 완료 시간 누적 버킷(le 초)
B_DRAW = "01:40:51"
before_poll = [(sec(t, B_DRAW), n) for t, n in [("01:41:40", 126), ("01:42:40", 319), ("01:43:41", 514), ("01:44:42", 707),
                                                ("01:45:42", 901), ("01:46:43", 1094), ("01:47:44", 1290)]]
before_bucket = [(458.13, 1432), (549.76, 1725), (600.0, 1885), (641.38, 2018), (733.01, 2019)]
# 후: 추첨 10:33:54 KST. 진행 조회(PAID 누적, 약 30초 간격)와 버킷(206.16초 1,874 → 229.06초 1,992 = 전부)
A_DRAW = "10:33:54"
after_poll = [(sec(t, A_DRAW), n) for t, n in [("10:34:06", 55), ("10:34:37", 342), ("10:35:07", 625), ("10:35:38", 910),
                                               ("10:36:08", 1193), ("10:36:38", 1480), ("10:37:08", 1767)]]
after_bucket = [(206.16, 1874), (229.06, 1992)]

# 왼쪽: 헤드라인 (LEDGER 1절 ④)
c.note("LEDGER 1절 ④: 초당 약 3.2 → 9.4건(2.94배 → '약 3배'), 2,000건 약 10.7분 → 206~229초 버킷 안('4분 안'), 10분 안 완료율 93.1·92.4·93.4% → 100%(2회)")
c.chip(M, 64, "추첨 자동결제", 158)
c.text(M, 166, "당첨 약 2,000건 자동결제", 34, 900, ls=-1)
c.run(M - 4, 340, [("약", 60, WHITE, 0.92, 0), ("3", 180, O, None, 12), ("배", 92, O, None, 6), ("빠르게", 54, WHITE, None, 16)])
c.text(M, 396, "약 11분 → 4분 안 100%", 32, 900)
c.text(M, 434, "처리 속도 초당 약 3.2 → 9.4건", 23, 800, WHITE, 0.92)
c.warn(M, 480, "측정일 다름(11/10 → 11/13)", 21)
c.conds(M, 524, ["조건: 응모 30,000명 · 추첨석 3,000",
                 "Mock PG 300ms · 배포 서버 1대",
                 "전: 결제 소비자 1개(11/10, 3회 측정)",
                 "후: 소비자 3개 + 자동결제 전용 허가 3",
                 "　　(11/13, 2회 측정)",
                 "10분 안 완료율 {93.1·92.4·93.4% → 100%}"])
c.conds(M, 724, ["곡선은 전 3회차·후 1회차. 점은 원문 값이고",
                 "점 사이 직선은 보간. 진행 조회의 경과 초는",
                 "기록 시각 − 추첨 시각(계산값)",
                 "후의 마지막 결제는 206~229초 버킷 안"], 17, 24, 0.7)

# 흰 카드: 같은 축 위 전후 누적 완료 곡선
x, y, w, h = WC
c.white_card(*WC)
PX0, PX1, PY0, PY1 = 690, 1490, 724, 190          # 그림 영역(왼·오른·아래·위)
TMAX, NMAX = 780, 2100
X = lambda t: PX0 + (PX1 - PX0) * t / TMAX
Y = lambda n: PY0 - (PY0 - PY1) * n / NMAX
BEFORE = "#8E94A3"                                 # 흰 바탕 위 선이라 개선 전 회색(#C9CDD6)보다 한 단계 짙게

c.text(x + 36, y + 54, "추첨 뒤 자동결제 완료 누적(건)", 26, 800, INK)
# 범례
lx = x + w - 404
for i, (col, s) in enumerate([(BEFORE, "전 11/10 · 소비자 1개"), (P, "후 11/13 · 소비자 3개")]):
    ly = y + 40 + i * 30
    c.add(f'<line x1="{lx}" y1="{ly}" x2="{lx + 40}" y2="{ly}" stroke="{col}" stroke-width="5" stroke-linecap="round"/>')
    c.text(lx + 52, ly + 6, s, 18, 800, INK)
c.add(f'<circle cx="{lx + 264}" cy="{y + 40}" r="6" fill="{BODY}"/>')
c.text(lx + 278, y + 46, "진행 조회", 17, 600, BODY)
c.add(f'<rect x="{lx + 258}" y="{y + 64}" width="12" height="12" fill="{BODY}"/>')
c.text(lx + 278, y + 76, "누적 버킷", 17, 600, BODY)

# 격자와 눈금
for n in range(0, 2001, 500):
    c.add(f'<line x1="{PX0}" y1="{Y(n):.1f}" x2="{PX1}" y2="{Y(n):.1f}" stroke="{LINE}" stroke-width="{2 if n == 0 else 1.2}"/>')
    c.text(PX0 - 12, Y(n) + 6, f"{n:,}", 18, 600, BODY, anchor="end")
for m in range(0, 13, 2):
    c.add(f'<line x1="{X(m * 60):.1f}" y1="{PY0}" x2="{X(m * 60):.1f}" y2="{PY0 + 7}" stroke="{BODY}" stroke-width="1.5"/>')
    c.text(X(m * 60), PY0 + 30, "0" if m == 0 else f"{m}분", 18, 600, BODY, anchor="middle")
c.text((PX0 + PX1) / 2, PY0 + 66, "추첨 뒤 경과 시간", 18, 600, BODY, anchor="middle")
# 10분 합격선 (numbers-and-measurement.md 1-2절: 추첨 후 10분 이내 95% 확정)
c.add(f'<line x1="{X(600):.1f}" y1="{PY1 - 8}" x2="{X(600):.1f}" y2="{PY0}" stroke="{RED}" stroke-width="2" stroke-dasharray="7 6"/>')
c.text(X(600) + 10, PY0 - 38, "10분 기준선", 18, 800, RED)
c.text(X(600) + 10, PY0 - 14, "(95% 완료가 합격)", 16, 600, RED)


def curve(polls, buckets, col):
    pts = sorted([(0, 0)] + polls + buckets)
    c.add(f'<polyline points="{" ".join(f"{X(t):.1f},{Y(n):.1f}" for t, n in pts)}" fill="none" stroke="{col}" stroke-width="5" stroke-linejoin="round" stroke-linecap="round"/>')
    for t, n in polls:
        c.add(f'<circle cx="{X(t):.1f}" cy="{Y(n):.1f}" r="7" fill="{col}" stroke="{WHITE}" stroke-width="2.5"/>')
    for t, n in buckets:
        c.add(f'<rect x="{X(t) - 7:.1f}" y="{Y(n) - 7:.1f}" width="14" height="14" fill="{col}" stroke="{WHITE}" stroke-width="2.5"/>')


c.note("전: numbers-and-measurement.md 3-3절 L2-a 3회 상세(진행 조회 7점, 버킷 458.13·549.76·600·641.38·733.01초)")
curve(before_poll, before_bucket, BEFORE)
c.note("후: 3-5절 + remeasure-2026-11-13.md 2절 1회 상세(진행 조회 7점, 버킷 206.16초 1,874 · 229.06초 1,992)")
curve(after_poll, after_bucket, P)

# 곡선 위 설명
c.text(X(229.06) + 20, Y(1992) - 2, "4분 안 100%", 30, 900, P, ls=-1)
c.text(X(229.06) + 20, Y(1992) + 26, "1,992건 전부 결제 완료", 18, 800, BODY_DARK)
c.text(X(134) + 24, Y(1193) + 22, "초당 약 9.4건", 22, 900, P)
c.text(X(352) + 22, Y(1094) + 40, "초당 약 3.2건", 22, 900, BODY)
c.text(X(600) - 18, Y(1885) - 30, "10분에 93.4%", 22, 900, INK, anchor="end")
c.text(X(600) - 18, Y(1885) - 6, "(1,885 / 2,019건)", 17, 600, BODY, anchor="end")
c.text(X(641.38) + 16, Y(2018) + 34, "약 11분에", 20, 900, BODY_DARK)
c.text(X(641.38) + 16, Y(2018) + 58, "모두 완료", 20, 900, BODY_DARK)
c.save("highlight-c.svg")
