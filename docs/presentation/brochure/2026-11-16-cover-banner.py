# 브로셔 커버 배너(갤러리 썸네일)를 SVG로 만든다.
# 차별점(추첨 응모 → 당첨 시 자동결제)은 헤드라인에, 기본기(선착순 예매도 구역만 고르면 자동배정)는 둘째 줄과 첫 휴대폰에 둔다.
# 휴대폰 화면은 실제 캡처가 아니라 프론트 디자인 시스템 색으로 그린 간략 목업이다. 핵심 문구만 보이게 짧게 둔다.
# 자동배정 화면은 프론트 적용 작업(별도 세션)이 끝나면 실제 화면과 문구를 맞춘다.
# 갤러리 카드 그림 영역은 약 2.18:1이다. 이 비율로 만들어 잘리지 않게 하고, 요소 묶음은 가운데에 둔다.
# 페이지 커버(매우 넓음)는 위아래가 잘리므로 헤드라인과 휴대폰 본체를 세로 가운데에 모은다.
# 실행: python 2026-11-16-cover-banner.py → 2026-11-16-cover-banner.svg
# PNG: msedge --headless=new --window-size=1500,688 --force-device-scale-factor=2 --screenshot=<png> <svg 파일 URL>
from pathlib import Path

HERE = Path(__file__).parent
W, H = 1500, 688  # Notion 갤러리 카드 그림 영역 303×139px(약 2.18:1)에 맞춘 비율
P, P2, PD, O, G, INK = "#3B3BD4", "#24248F", "#2F2FAE", "#F59F00", "#2B8A3E", "#14161E"
GRAY, GRAY2, BODY = "#E1E4EA", "#F4F5F8", "#676C7E"
SEAT_COLORS = ["#C2255C", "#D9480F", "#2B8A3E"]  # VIP, R, S

o = []


def add(s):
    o.append(s)


def text(x, y, s, size, weight=700, fill=INK, anchor="start", opacity=None):
    op = f' fill-opacity="{opacity}"' if opacity is not None else ""
    add(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" text-anchor="{anchor}" fill="{fill}"{op}>{s}</text>')


def check(cx, cy, r, color, stroke="#FFFFFF"):
    add(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{color}"/>')
    k = r / 20
    add(f'<path d="M{cx - 9 * k} {cy + 0.5 * k} L{cx - 2.5 * k} {cy + 7 * k} L{cx + 9.5 * k} {cy - 6 * k}" fill="none" '
        f'stroke="{stroke}" stroke-width="{4.2 * k:.1f}" stroke-linecap="round" stroke-linejoin="round"/>')


PW = 214
SEAT_H, LOT_H = 392, 278  # 각 화면의 마지막 정보 바로 아래에서 끊는다


def phone(x, y, h):
    """휴대폰 틀. 필요한 정보만 담고 아래에서 끊는다(h = 틀 높이)."""
    add(f'<rect x="{x + 6}" y="{y + 14}" width="{PW}" height="{h}" rx="34" fill="#000000" fill-opacity="0.25"/>')
    add(f'<rect x="{x}" y="{y}" width="{PW}" height="{h}" rx="34" fill="{INK}"/>')
    add(f'<rect x="{x + 10}" y="{y + 10}" width="{PW - 20}" height="{h - 20}" rx="24" fill="#FFFFFF"/>')
    # 상단 앱 머리: 로고 + 이름
    sx = x + 24
    add(f'<rect x="{sx}" y="{y + 30}" width="16" height="16" rx="4" fill="{P}"/>')
    add(f'<rect x="{sx + 4}" y="{y + 40}" width="8" height="3" rx="1.5" fill="#FFFFFF"/>')
    text(sx + 22, y + 44, "이선좌", 14, 800)


def seat_phone(x, y):
    """선착순 예매: 구역·매수 → 자동배정 완료."""
    phone(x, y, SEAT_H)
    sx, sw = x + 24, PW - 48
    text(sx, y + 82, "좌석 선택", 17, 800)
    # 매수 선택(2매 선택됨)
    add(f'<rect x="{sx}" y="{y + 94}" width="{sw}" height="28" rx="8" fill="{GRAY2}"/>')
    seg = sw / 4
    for i, label in enumerate(["1매", "2매", "3매", "4매"]):
        if i == 1:
            add(f'<rect x="{sx + seg * i + 2}" y="{y + 96}" width="{seg - 4}" height="24" rx="6" fill="#FFFFFF"/>')
        text(sx + seg * i + seg / 2, y + 113, label, 11, 800 if i == 1 else 600, INK if i == 1 else BODY, "middle")
    # 무대
    add(f'<path d="M{sx + 30} {y + 134} h{sw - 60} a12 12 0 0 1 -12 12 h{-(sw - 84)} a12 12 0 0 1 -12 -12 z" fill="{INK}"/>')
    text(sx + sw / 2, y + 144, "무 대", 8, 700, "#FFFFFF", "middle")
    # 좌석 격자: 대부분 회색(팔림), 일부 색(남음), 배정된 두 자리는 주 색 + 체크
    cols, rows, cs, gap = 9, 5, 15, 4
    gx = sx + (sw - (cols * cs + (cols - 1) * gap)) / 2
    taken = {(0, 1), (0, 2), (0, 5), (1, 0), (1, 3), (1, 7), (2, 2), (2, 6), (3, 1), (3, 4), (3, 8), (4, 0), (4, 5)}
    assigned = {(2, 4), (2, 5)}
    for r in range(rows):
        for c in range(cols):
            cx_, cy_ = gx + c * (cs + gap), y + 158 + r * (cs + gap)
            if (r, c) in assigned:
                add(f'<rect x="{cx_ - 1}" y="{cy_ - 1}" width="{cs + 2}" height="{cs + 2}" rx="4" fill="{P}"/>')
                add(f'<path d="M{cx_ + 4} {cy_ + 8} l3 3 l5 -6" fill="none" stroke="#FFFFFF" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>')
            elif (r, c) in taken:
                add(f'<rect x="{cx_}" y="{cy_}" width="{cs}" height="{cs}" rx="3" fill="{GRAY}"/>')
            else:
                add(f'<rect x="{cx_}" y="{cy_}" width="{cs}" height="{cs}" rx="3" fill="{SEAT_COLORS[min(r // 2, 2)]}"/>')
    # 배정된 자리 요약(핵심 문구 "자동배정 완료"는 바깥 배지가 맡는다)
    cy0 = y + 268
    add(f'<rect x="{sx}" y="{cy0}" width="{sw}" height="96" rx="14" fill="#EEF0FF"/>')
    check(sx + sw / 2, cy0 + 32, 18, P)
    text(sx + sw / 2, cy0 + 76, "F구역 3열 12·13번", 15, 800, INK, "middle")


def lottery_phone(x, y):
    """추첨 응모: 당첨 + 자동결제 완료만 짧게."""
    phone(x, y, LOT_H)
    sx, sw = x + 24, PW - 48
    text(sx, y + 82, "추첨 결과", 17, 800)
    # 축하 원 + 체크
    add(f'<circle cx="{sx + sw / 2}" cy="{y + 158}" r="46" fill="#EBFBEE"/>')
    check(sx + sw / 2, y + 158, 28, G)
    text(sx + sw / 2, y + 236, "당첨됐어요!", 24, 900, INK, "middle")


def chip(cx, y, s):
    w = len(s.replace(" ", "")) * 26 + 46
    add(f'<rect x="{cx - w / 2}" y="{y}" width="{w}" height="44" rx="22" fill="#FFFFFF"/>')
    text(cx, y + 31, s, 26, 800, P, "middle")


def badge(x, y, s, fill, fg, icon_fill):
    w = len(s.replace(" ", "")) * 30 + 100
    add(f'<rect x="{x + 4}" y="{y + 8}" width="{w}" height="66" rx="33" fill="#000000" fill-opacity="0.22"/>')
    add(f'<rect x="{x}" y="{y}" width="{w}" height="66" rx="33" fill="{fill}"/>')
    check(x + 36, y + 33, 19, icon_fill, fill)
    text(x + 66, y + 44, s, 31, 800, fg)
    return w


add(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="Pretendard, \'Noto Sans KR\', sans-serif">')
add("<defs>")
add(f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{P}"/><stop offset="1" stop-color="{P2}"/></linearGradient>')
add('<linearGradient id="beam" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.2"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>')
add("</defs>")
add(f'<rect width="{W}" height="{H}" fill="url(#bg)"/>')
for x0, x1, x2 in [(930, 700, 960), (1010, 960, 1210), (1150, 1180, 1440), (520, 330, 560), (600, 560, 790)]:
    add(f'<polygon points="{x0 - 6},0 {x0 + 6},0 {x2},{H} {x1},{H}" fill="url(#beam)"/>')

add('<g transform="translate(-118.25 37) scale(1.15)">')
P1X, P1Y, P2X, P2Y = 800, 120, 1050, 80
seat_phone(P1X, P1Y)
lottery_phone(P2X, P2Y)
chip(P1X + PW / 2, P1Y - 58, "선착순 · 자동배정")
chip(P2X + PW / 2, P2Y - 58, "추첨 응모")
# 배지는 화면 아래쪽을 덮어도 된다(핵심 문구를 카드 크기에서도 읽히게)
badge(780, P1Y + SEAT_H - 44, "자동배정 완료", "#FFFFFF", P, P)
w2 = len("당첨 · 자동결제".replace(" ", "")) * 30 + 100
badge(1270 - w2, P2Y + LOT_H - 36, "당첨 · 자동결제", G, "#FFFFFF", "#FFFFFF")

tx = 240
text(tx, 178, "5조 이선좌 · 콘서트 티켓 예매", 40, 700, "#FFFFFF", opacity=0.82)
add(f'<text x="{tx - 3}" y="270" font-size="72" font-weight="900" fill="#FFFFFF" letter-spacing="-2">빠른 손 아니어도,</text>')
add(f'<text x="{tx - 3}" y="356" font-size="72" font-weight="900" fill="#FFFFFF" letter-spacing="-2"><tspan fill="{O}">추첨</tspan>으로 내 자리</text>')
text(tx, 430, "선착순은 구역만 고르면 자동배정", 36, 600, "#FFFFFF", opacity=0.92)
text(tx, 480, "중복 판매 0건 · 당첨되면 자동결제", 28, 600, "#FFFFFF", opacity=0.7)
add("</g>")
add("</svg>")

(HERE / "2026-11-16-cover-banner.svg").write_text("\n".join(o), encoding="utf-8")
print("ok")
