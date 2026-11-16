# 브로셔 큰 그림 "화면 흐름"(1600×900)을 SVG로 만든다.
# 대기열 → 자동배정 → 결제(선착순 세 화면) 그리고 추첨 당첨·자동결제. 휴대폰 목업 4대.
# 규칙: ../../design-kit.md. 목업 그리는 법은 ../../2026-11-16-cover-banner.py와 같은 색·모양을 쓴다.
# 화면 내용은 docs/design/frontend/samples/screens/(queue-waiting, seat-sections-375, payment-ready, lottery-paid)를
# 단순화해 다시 그린 것이다. 자동배정 화면은 프론트에 아직 없어 커버 배너 목업과 같은 내용으로 둔다.
# 화면 속 순번·금액·카드 번호는 측정값이 아니라 시안의 예시 문구다(원장 숫자를 쓰지 않는 그림).
# 실행: python flow.py → flow.svg
# PNG: msedge --headless=new --window-size=1600,900 --force-device-scale-factor=2 --screenshot=<png> <svg 파일 URL>
from pathlib import Path

HERE = Path(__file__).parent
W, H = 1600, 900
P, P2, O, G, INK = "#3B3BD4", "#24248F", "#F59F00", "#2B8A3E", "#14161E"
GRAY, GRAY2, BODY = "#E1E4EA", "#F4F5F8", "#676C7E"
WHITE = "#FFFFFF"
SEAT_COLORS = ["#C2255C", "#D9480F", "#2B8A3E"]  # VIP, R, S

S = 1.3                    # 휴대폰 배율(커버 배너의 단위 좌표를 그대로 쓰고 통째로 키운다)
PW, PH = 214, 392          # 단위 좌표의 휴대폰 크기. 네 대 모두 같은 높이에서 끊는다
GAP = 110                  # 휴대폰 사이(화살표 자리)
PY = 248                   # 휴대폰 윗변
X0 = (W - (4 * PW * S + 3 * GAP)) / 2

o = []


def add(s):
    o.append(s)


def text(x, y, s, size, weight=700, fill=INK, anchor="start", opacity=None):
    op = f' fill-opacity="{opacity}"' if opacity is not None else ""
    add(f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" font-weight="{weight}" text-anchor="{anchor}" fill="{fill}"{op}>{s}</text>')


def check(cx, cy, r, color, stroke=WHITE):
    add(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{color}"/>')
    k = r / 20
    add(f'<path d="M{cx - 9 * k} {cy + 0.5 * k} L{cx - 2.5 * k} {cy + 7 * k} L{cx + 9.5 * k} {cy - 6 * k}" fill="none" '
        f'stroke="{stroke}" stroke-width="{4.2 * k:.1f}" stroke-linecap="round" stroke-linejoin="round"/>')


def phone():
    """휴대폰 틀(단위 좌표 0,0 기준). 커버 배너와 같은 모양."""
    add(f'<rect x="6" y="14" width="{PW}" height="{PH}" rx="34" fill="#000000" fill-opacity="0.25"/>')
    add(f'<rect width="{PW}" height="{PH}" rx="34" fill="{INK}"/>')
    add(f'<rect x="10" y="10" width="{PW - 20}" height="{PH - 20}" rx="24" fill="{WHITE}"/>')
    add(f'<rect x="24" y="30" width="16" height="16" rx="4" fill="{P}"/>')
    add(f'<rect x="28" y="40" width="8" height="3" rx="1.5" fill="{WHITE}"/>')
    text(46, 44, "이선좌", 14, 800)


SX, SW = 24, PW - 48       # 화면 안쪽 왼쪽 끝, 폭
CX = PW / 2


def status_pill(y, label, value=None):
    """화면 위쪽 상태 알약(초록 점 + 문구)."""
    add(f'<rect x="{SX}" y="{y}" width="{SW}" height="24" rx="12" fill="{GRAY2}"/>')
    add(f'<circle cx="{SX + 14}" cy="{y + 12}" r="3.5" fill="{G}"/>')
    text(SX + 24, y + 16, label, 10, 600, BODY)
    if value:
        text(SX + SW - 12, y + 16.5, value, 11.5, 800, INK, "end")


def queue_screen():
    """대기열: 내 순번과 예상 대기만."""
    phone()
    status_pill(62, "순번을 자동으로 확인하고 있어요")
    text(CX, 132, "내 순번", 13, 600, BODY, "middle")
    add(f'<text x="{CX}" y="186" text-anchor="middle" font-weight="900" fill="{INK}" letter-spacing="-1">'
        f'<tspan font-size="46">1,234</tspan><tspan font-size="20" dx="3">번</tspan></text>')
    add(f'<text x="{CX}" y="218" text-anchor="middle" font-size="13" font-weight="600" fill="{BODY}">예상 대기 '
        f'<tspan font-weight="800" fill="{INK}">약 1분</tspan></text>')
    add(f'<rect x="{SX}" y="238" width="{SW}" height="8" rx="4" fill="{GRAY}"/>')
    add(f'<rect x="{SX}" y="238" width="{SW * 0.62:.1f}" height="8" rx="4" fill="{P}"/>')
    add(f'<rect x="{SX}" y="268" width="{SW}" height="96" rx="14" fill="#EEF0FF"/>')
    text(CX, 300, "차례가 되면", 12, 600, BODY, "middle")
    text(CX, 322, "좌석 선택으로 바로 넘어가요", 12, 800, INK, "middle")
    text(CX, 346, "창을 닫아도 자리는 그대로", 10.5, 600, BODY, "middle")


def seat_screen():
    """선착순 예매: 구역·매수 → 자동배정 완료(커버 배너와 같은 내용)."""
    phone()
    text(SX, 82, "좌석 선택", 17, 800)
    add(f'<rect x="{SX}" y="94" width="{SW}" height="28" rx="8" fill="{GRAY2}"/>')
    seg = SW / 4
    for i, label in enumerate(["1매", "2매", "3매", "4매"]):
        if i == 1:
            add(f'<rect x="{SX + seg * i + 2}" y="96" width="{seg - 4}" height="24" rx="6" fill="{WHITE}"/>')
        text(SX + seg * i + seg / 2, 113, label, 11, 800 if i == 1 else 600, INK if i == 1 else BODY, "middle")
    add(f'<path d="M{SX + 30} 134 h{SW - 60} a12 12 0 0 1 -12 12 h{-(SW - 84)} a12 12 0 0 1 -12 -12 z" fill="{INK}"/>')
    text(CX, 144, "무 대", 8, 700, WHITE, "middle")
    cols, rows, cs, gap = 9, 5, 15, 4
    gx = SX + (SW - (cols * cs + (cols - 1) * gap)) / 2
    taken = {(0, 1), (0, 2), (0, 5), (1, 0), (1, 3), (1, 7), (2, 2), (2, 6), (3, 1), (3, 4), (3, 8), (4, 0), (4, 5)}
    assigned = {(2, 4), (2, 5)}
    for r in range(rows):
        for c in range(cols):
            x, y = gx + c * (cs + gap), 158 + r * (cs + gap)
            if (r, c) in assigned:
                add(f'<rect x="{x - 1}" y="{y - 1}" width="{cs + 2}" height="{cs + 2}" rx="4" fill="{P}"/>')
                add(f'<path d="M{x + 4} {y + 8} l3 3 l5 -6" fill="none" stroke="{WHITE}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>')
            elif (r, c) in taken:
                add(f'<rect x="{x}" y="{y}" width="{cs}" height="{cs}" rx="3" fill="{GRAY}"/>')
            else:
                add(f'<rect x="{x}" y="{y}" width="{cs}" height="{cs}" rx="3" fill="{SEAT_COLORS[min(r // 2, 2)]}"/>')
    add(f'<rect x="{SX}" y="268" width="{SW}" height="96" rx="14" fill="#EEF0FF"/>')
    check(CX, 294, 15, P)
    text(CX, 328, "자동배정 완료", 12, 800, P, "middle")
    text(CX, 350, "F구역 3열 12·13번", 15, 800, INK, "middle")


def pay_screen():
    """결제: 잡아 둔 좌석, 남은 시간, 합계, 결제 버튼."""
    phone()
    text(SX, 82, "결제", 17, 800)
    status_pill(96, "결제 가능", "04:50")
    for i, seat in enumerate(["12번", "13번"]):
        y = 132 + i * 40
        add(f'<rect x="{SX}" y="{y}" width="{SW}" height="34" rx="8" fill="{GRAY2}"/>')
        add(f'<rect x="{SX + 8}" y="{y + 9}" width="16" height="16" rx="4" fill="{SEAT_COLORS[1]}"/>')
        text(SX + 16, y + 21, "R", 9.5, 800, WHITE, "middle")
        text(SX + 30, y + 21.5, f"F구역 3열 {seat}", 11, 700, INK)
        text(SX + SW - 8, y + 21.5, "143,000원", 10.5, 600, BODY, "end")
    add(f'<rect x="{SX}" y="222" width="{SW}" height="1" fill="{GRAY}"/>')
    text(SX, 252, "합계", 12, 600, BODY)
    text(SX + SW, 254, "286,000원", 20, 900, INK, "end")
    add(f'<rect x="{SX}" y="272" width="{SW}" height="44" rx="10" fill="{P}"/>')
    text(CX, 299.5, "286,000원 결제하기", 14, 800, WHITE, "middle")
    text(CX, 338, "시간이 지나면", 10.5, 600, BODY, "middle")
    text(CX, 354, "잡아 둔 좌석이 풀려요", 10.5, 600, BODY, "middle")


def lottery_screen():
    """추첨: 당첨 + 자동결제 완료, 등록해 둔 카드."""
    phone()
    text(SX, 82, "추첨 결과", 17, 800)
    add(f'<circle cx="{CX}" cy="142" r="40" fill="#EBFBEE"/>')
    check(CX, 142, 25, G)
    text(CX, 218, "당첨됐어요!", 24, 900, INK, "middle")
    text(CX, 242, "결제까지 끝났어요", 13, 600, BODY, "middle")
    add(f'<rect x="{SX}" y="268" width="{SW}" height="96" rx="14" fill="{GRAY2}"/>')
    text(SX + 14, 292, "자동결제 카드", 11.5, 800, INK)
    add(f'<rect x="{SX + SW - 56}" y="278" width="44" height="20" rx="10" fill="#EBFBEE"/>')
    text(SX + SW - 34, 292, "등록됨", 10, 800, G, "middle")
    add(f'<rect x="{SX + 14}" y="312" width="44" height="30" rx="5" fill="{INK}"/>')
    add(f'<rect x="{SX + 20}" y="320" width="9" height="7" rx="1.5" fill="{O}"/>')
    text(SX + 68, 324, "시연용 카드", 12, 800, INK)
    text(SX + 68, 341, "•••• 4242", 10.5, 600, BODY)


def chip(cx, y, s, num=None, fill=WHITE, fg=P):
    """이름표 칩. num이 있으면 앞에 번호 동그라미를 둔다."""
    tw = sum(22 if ord(ch) > 0x2000 else 7 for ch in s)
    w = tw + 44 + (34 if num else 0)
    x = cx - w / 2
    add(f'<rect x="{x:.1f}" y="{y}" width="{w}" height="44" rx="22" fill="{fill}"/>')
    if num:
        add(f'<circle cx="{x + 26:.1f}" cy="{y + 22}" r="14" fill="{fg}"/>')
        text(x + 26, y + 29, num, 19, 900, fill, "middle")
        text(x + 48, y + 30.5, s, 24, 800, fg)
    else:
        text(cx, y + 30.5, s, 24, 800, fg, "middle")


def arrow(x0, x1, y):
    """휴대폰 사이 화살표."""
    add(f'<path d="M{x0:.1f} {y} H{x1 - 4:.1f}" fill="none" stroke="{WHITE}" stroke-width="7" stroke-linecap="round"/>')
    add(f'<path d="M{x1 - 20:.1f} {y - 17} L{x1:.1f} {y} L{x1 - 20:.1f} {y + 17}" fill="none" stroke="{WHITE}" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>')


add(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="Pretendard, \'Noto Sans KR\', sans-serif">')
add("<!-- 화면 흐름. 측정 숫자를 쓰지 않는다. 화면 속 순번·금액·카드 번호는 디자인 시안(docs/design/frontend/samples/screens/)의 예시 문구다 -->")
add("<!-- 기능 근거: LEDGER.md 10절 차별성(추첨 자동결제), 3-2 대기열, 3-3 좌석 선점·자동배정, 3-4 결제, 3-5 추첨·자동결제 -->")
add("<defs>")
add(f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{P}"/><stop offset="1" stop-color="{P2}"/></linearGradient>')
add('<linearGradient id="beam" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.2"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>')
add("</defs>")
add(f'<rect width="{W}" height="{H}" fill="url(#bg)"/>')
for x0, x1, x2 in [(1010, 760, 1010), (1150, 1090, 1330), (1290, 1380, 1640), (860, 520, 740)]:
    add(f'<polygon points="{x0 - 6},0 {x0 + 6},0 {x2},{H} {x1},{H}" fill="url(#beam)"/>')

add(f'<text x="61" y="104" font-size="66" font-weight="900" fill="{WHITE}" letter-spacing="-2">줄 서기부터 <tspan fill="{O}">결제</tspan>까지, 화면 네 장</text>')
text(64, 142, "선착순은 대기열 → 자동배정 → 결제, 추첨은 당첨되면 자동결제 · 디자인 시안을 단순화한 그림", 22, 600, WHITE, opacity=0.7)

SCREENS = [
    (queue_screen, "대기열", "1", "번호표로 공정하게 줄 서기"),
    (seat_screen, "자동배정", "2", "구역만 고르면 자리는 자동으로"),
    (pay_screen, "결제", "3", "잡아 둔 자리를 시간 안에 결제"),
    (lottery_screen, "추첨 · 자동결제", None, "당첨되면 결제까지 자동으로"),
]
MID = PY + PH * S / 2
for i, (draw, name, num, caption) in enumerate(SCREENS):
    x = X0 + i * (PW * S + GAP)
    cx = x + PW * S / 2
    add(f'<g transform="translate({x:.1f} {PY}) scale({S})">')
    draw()
    add("</g>")
    if num:
        chip(cx, PY - 60, name, num)
    else:
        chip(cx, PY - 60, name, None, G, WHITE)   # 추첨은 다른 길이라 초록 칩으로 구분한다
    text(cx, PY + PH * S + 58, caption, 25, 800, WHITE, "middle")
    if i < 2:
        arrow(x + PW * S + 26, x + PW * S + GAP - 22, MID)
    elif i == 2:
        # 결제 → 추첨은 이어지는 단계가 아니라 다른 길이다. 화살표 대신 "또는"으로 잇는다
        gx = x + PW * S + GAP / 2 + 3
        add(f'<path d="M{gx:.1f} {PY + 40} V{PY + PH * S - 40:.1f}" stroke="{WHITE}" stroke-opacity="0.45" stroke-width="3" stroke-dasharray="3 11" stroke-linecap="round"/>')
        add(f'<rect x="{gx - 40:.1f}" y="{MID - 23:.1f}" width="80" height="46" rx="23" fill="{P2}" stroke="{WHITE}" stroke-opacity="0.6" stroke-width="2"/>')
        text(gx, MID + 8.5, "또는", 24, 800, WHITE, "middle")
add("</svg>")

(HERE / "flow.svg").write_text("\n".join(o), encoding="utf-8")
print("ok")
