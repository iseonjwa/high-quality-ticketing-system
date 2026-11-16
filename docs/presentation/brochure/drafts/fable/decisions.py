# 브로셔 "기술적 의사결정" 카드 썸네일 5장(1500×688)을 SVG로 만든다.
# 규칙: ../../design-kit.md(카드 썸네일, 안전 영역 가로 120·세로 80). 갤러리 카드(약 318×146)에서 번호와 제목이 읽혀야 한다.
# 내용 기준: ../../2026-11-16-brochure-notion.md "기술적 의사결정" ①~⑤, 숫자는 ../../census/LEDGER.md에 있는 값만.
# 카드마다: 번호, 결정 제목, "A 대신 B" 대비 한 줄, 근거 한 줄, 대가 한 줄(작게), 상징 그림 하나.
# 실행: python decisions.py → decision-1.svg ~ decision-5.svg, 갤러리 미리보기용 decisions-gallery.html
# PNG: msedge --headless=new --window-size=1500,688 --force-device-scale-factor=2 --screenshot=<png> <svg 파일 URL>
# 미리보기: msedge --headless=new --window-size=1738,238 --force-device-scale-factor=1 --screenshot=decisions-gallery.png decisions-gallery.html
from pathlib import Path

HERE = Path(__file__).parent
W, H = 1500, 688
P, P2, O, G, GL, INK = "#3B3BD4", "#24248F", "#F59F00", "#2B8A3E", "#63E6BE", "#14161E"
WHITE, RED_L = "#FFFFFF", "#FF8787"
TX = 120                   # 글자 왼쪽 끝(안전 영역)
SCX, SCY = 1180, 344       # 상징 그림 중심

o = []


def add(s):
    o.append(s)


def text(x, y, s, size, weight=700, fill=WHITE, anchor="start", opacity=None, extra=""):
    op = f' fill-opacity="{opacity}"' if opacity is not None else ""
    add(f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" font-weight="{weight}" text-anchor="{anchor}" fill="{fill}"{op}{extra}>{s}</text>')


def width(s, size):
    """글자 폭 어림(한글 0.93, 그 밖 0.56, 빈칸 0.25). 칩 크기를 잡는 데만 쓴다."""
    return sum(0.25 if ch == " " else 0.93 if ord(ch) > 0x2000 else 0.56 for ch in s) * size


def check(cx, cy, r, color, stroke=WHITE):
    add(f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{color}"/>')
    k = r / 20
    add(f'<path d="M{cx - 9 * k} {cy + 0.5 * k} L{cx - 2.5 * k} {cy + 7 * k} L{cx + 9.5 * k} {cy - 6 * k}" fill="none" '
        f'stroke="{stroke}" stroke-width="{4.2 * k:.1f}" stroke-linecap="round" stroke-linejoin="round"/>')


def frame(num, area, ledger):
    add(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="Pretendard, \'Noto Sans KR\', sans-serif">')
    add(f"<!-- 결정 카드 {num}. 근거: {ledger} -->")
    add("<defs>")
    add(f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{P}"/><stop offset="1" stop-color="{P2}"/></linearGradient>')
    add('<linearGradient id="beam" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.2"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>')
    add("</defs>")
    add(f'<rect width="{W}" height="{H}" fill="url(#bg)"/>')
    # 빛줄기는 상징 그림 쪽에만 둔다(글자 위로 지나지 않게)
    for x0, x1, x2 in [(1080, 900, 1120), (1230, 1260, 1500)]:
        add(f'<polygon points="{x0 - 6},0 {x0 + 6},0 {x2},{H} {x1},{H}" fill="url(#beam)"/>')
    # 번호 + 영역 이름
    add(f'<circle cx="{TX + 38}" cy="130" r="38" fill="{WHITE}"/>')
    text(TX + 38, 150, str(num), 56, 900, P, "middle")
    text(TX + 96, 143, f"기술적 의사결정 · {area}", 34, 700, WHITE, opacity=0.82)


def title(l1, l2):
    """두 줄 제목. {…}로 감싼 낱말은 주황."""
    for y, s in ((258, l1), (346, l2)):
        s = s.replace("{", f'<tspan fill="{O}">').replace("}", "</tspan>")
        text(TX - 3, y, s, 78, 900, WHITE, extra=' letter-spacing="-2"')


def versus(a, b):
    """A 대신 B: 버린 쪽은 흐린 알약 + 줄, 고른 쪽은 흰 알약."""
    y, hh, fs = 388, 62, 32
    wa = width(a, fs) + 52
    add(f'<rect x="{TX}" y="{y}" width="{wa:.0f}" height="{hh}" rx="31" fill="{WHITE}" fill-opacity="0.14" stroke="{WHITE}" stroke-opacity="0.3" stroke-width="1.5"/>')
    text(TX + wa / 2, y + 42, a, fs, 700, WHITE, "middle", 0.75)
    add(f'<path d="M{TX + 22} {y + 32} H{TX + wa - 22:.0f}" stroke="{WHITE}" stroke-opacity="0.75" stroke-width="3" stroke-linecap="round"/>')
    x = TX + wa + 16
    text(x, y + 42, "대신", fs, 800, WHITE)
    x += width("대신", fs) + 16
    wb = width(b, fs) + 56
    add(f'<rect x="{x:.0f}" y="{y}" width="{wb:.0f}" height="{hh}" rx="31" fill="{WHITE}"/>')
    text(x + wb / 2, y + 42, b, fs, 800, P, "middle")


def notes(why, cost):
    """근거 한 줄(조건 포함)과 대가 한 줄. 카드 크기에서는 읽히지 않아도 되는 작은 글씨."""
    text(TX, 504, why, 25, 600, WHITE, opacity=0.92)
    add(f'<text x="{TX}" y="548" font-size="25" font-weight="600" fill="{WHITE}" fill-opacity="0.7">'
        f'<tspan font-weight="800" fill-opacity="0.92">대가</tspan><tspan dx="12">{cost}</tspan></text>')


def save(num):
    add("</svg>")
    (HERE / f"decision-{num}.svg").write_text("\n".join(o), encoding="utf-8")
    o.clear()


# ───────── ① Gateway 과부하 ─────────
frame(1, "Gateway",
      "LEDGER.md 1절 히어로 ③(굿풋 1,304 → 1,696, 거절 1,678 → 8,715, 성공률 89.04 → 85.17%), 3-1 타임라인 11/11(차단기 열림 0회), brochure-notion ①")
title("과부하는", "{맨 앞}에서 막는다")
versus("차단기를 예민하게", "맨 앞 동시 처리 상한")
# 계산식: (1,696 − 1,304) ÷ 1,304 = +30.1%
notes("제때 끝낸 순번 조회 +30% · 초당 2,000건 · 같은 날 1회",
      "거절 1,678 → 8,715건 · 성공률 89.04 → 85.17%")
# 상징: 몰려온 요청이 문 앞에서 걸러지는 모습. 통과는 민트, 거절은 붉은 점
gx = SCX + 10
add(f'<rect x="{gx - 9}" y="{SCY - 190}" width="18" height="140" rx="9" fill="{WHITE}"/>')
add(f'<rect x="{gx - 9}" y="{SCY + 50}" width="18" height="140" rx="9" fill="{WHITE}"/>')
for r in range(7):
    cy = SCY - 156 + r * 52
    passing = r in (3,)
    for c in range(3):
        cx = gx - 70 - c * 52
        if passing or c > 0:
            add(f'<circle cx="{cx}" cy="{cy}" r="17" fill="{WHITE}" fill-opacity="{0.9 if passing else 0.45}"/>')
        else:
            add(f'<circle cx="{cx}" cy="{cy}" r="17" fill="{RED_L}"/>')
            add(f'<path d="M{cx - 6} {cy - 6} l12 12 M{cx + 6} {cy - 6} l-12 12" stroke="{P2}" stroke-width="3.5" stroke-linecap="round"/>')
for c in range(3):
    add(f'<circle cx="{gx + 70 + c * 52}" cy="{SCY}" r="17" fill="{GL}"/>')
add(f'<path d="M{gx - 34} {SCY} H{gx + 30}" stroke="{WHITE}" stroke-width="6" stroke-linecap="round"/>')
add(f'<path d="M{gx + 18} {SCY - 13} L{gx + 32} {SCY} L{gx + 18} {SCY + 13}" fill="none" stroke="{WHITE}" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/>')
save(1)

# ───────── ② 입장 토큰 ─────────
frame(2, "입장 토큰",
      "LEDGER.md 3-9(:391, :402 입장 토큰 선점 성공 순간 소모, ADR 0053, DB 고유 제약), brochure-notion ②(대가 문구). 숫자 없음")
title("입장 한 번에", "{선점 한 번}")
versus("10분 지나면 만료", "선점 성공 순간 소모")
notes("선점 → 만료 → 다시 선점으로 좌석을 묶어 두는 길을 막음",
      "결제에 실패한 사람은 대기열을 다시 섭니다")
# 상징: 쓰고 난 입장권(한쪽이 뜯긴 표 + 사용 완료 체크)
add(f'<g transform="rotate(-7 {SCX} {SCY})">')
tw, th = 370, 190
x0, y0 = SCX - tw / 2, SCY - th / 2
add(f'<path d="M{x0 + 22} {y0} H{x0 + tw - 22} a22 22 0 0 1 22 22 V{y0 + 68} a27 27 0 0 0 0 54 V{y0 + th - 22} a22 22 0 0 1 -22 22 '
    f'H{x0 + 22} a22 22 0 0 1 -22 -22 V{y0 + 122} a27 27 0 0 0 0 -54 V{y0 + 22} a22 22 0 0 1 22 -22 z" fill="{WHITE}"/>')
add(f'<path d="M{x0 + 262} {y0 + 16} V{y0 + th - 16}" stroke="{P}" stroke-opacity="0.35" stroke-width="4" stroke-dasharray="2 12" stroke-linecap="round"/>')
text(x0 + 54, y0 + 84, "입장 토큰", 44, 900, P)
add(f'<rect x="{x0 + 54}" y="{y0 + 112}" width="150" height="14" rx="7" fill="{P}" fill-opacity="0.22"/>')
add(f'<rect x="{x0 + 54}" y="{y0 + 138}" width="96" height="14" rx="7" fill="{P}" fill-opacity="0.22"/>')
add("</g>")
check(SCX + 142, SCY + 62, 62, G)
add(f'<circle cx="{SCX + 142}" cy="{SCY + 62}" r="62" fill="none" stroke="{WHITE}" stroke-width="7"/>')
save(2)

# ───────── ③ SAGA ─────────
frame(3, "예매 ↔ 결제 SAGA",
      "LEDGER.md 3-4 타임라인 11/14(거절 → Outbox → Kafka → 예매 RELEASED 0.14초, 시연 1건, 계산 0.139초)·보조 1, 10절(SAGA 코레오그래피), brochure-notion ③(대가 문구)")
title("조정자 없이", "{이벤트}로 보상")
versus("오케스트레이션", "코레오그래피")
notes("결제 거절 뒤 약 0.14초에 좌석 반환 · 배포 서버 시연 1건",
      "보상이 결제 추적에 이어 보이지 않아 예매 상태 이력으로 보완")
# 상징: 가운데 조정자(점선, 지움) 없이 결제 → 이벤트 → 예매로 바로 이어지는 모습
lx, rx_, ny = SCX - 130, SCX + 130, SCY + 70
add(f'<circle cx="{SCX}" cy="{SCY - 130}" r="50" fill="none" stroke="{WHITE}" stroke-opacity="0.5" stroke-width="4" stroke-dasharray="9 9"/>')
text(SCX, SCY - 120, "조정자", 26, 800, WHITE, "middle", 0.6)
add(f'<path d="M{SCX - 62} {SCY - 92} L{SCX + 62} {SCY - 168}" stroke="{RED_L}" stroke-width="7" stroke-linecap="round"/>')
for cx, name in ((lx, "예매"), (rx_, "결제")):
    add(f'<circle cx="{cx}" cy="{ny}" r="70" fill="{WHITE}"/>')
    text(cx, ny + 16, name, 44, 900, P, "middle")
add(f'<path d="M{rx_ - 82} {ny} H{lx + 90}" stroke="{GL}" stroke-width="8" stroke-linecap="round"/>')
add(f'<path d="M{lx + 104} {ny - 16} L{lx + 86} {ny} L{lx + 104} {ny + 16}" fill="none" stroke="{GL}" stroke-width="8" stroke-linecap="round" stroke-linejoin="round"/>')
add(f'<rect x="{SCX - 62}" y="{ny + 96}" width="124" height="50" rx="25" fill="{GL}"/>')
text(SCX, ny + 131, "이벤트", 28, 800, P2, "middle")
add(f'<path d="M{SCX} {ny + 92} V{ny + 14}" stroke="{GL}" stroke-width="4" stroke-dasharray="2 9" stroke-linecap="round"/>')
save(3)

# ───────── ④ 자동결제 ─────────
frame(4, "추첨 자동결제",
      "LEDGER.md 1절 히어로 ④(초당 약 3.2 → 9.4건, 9.4 ÷ 3.2 = 2.94배, 10분 안 100% 2회, ⚠ 11/10 → 11/13), 3-5(pg-billing 3·pg-approve 30, 감수: 상시 허가 3개 점유)")
title("소비자는 늘리고", "허가는 {따로}")
versus("소비자 1 · 허가 공유", "소비자 3 + 전용 PG 허가 3")
notes("처리 약 3배(초당 3.2 → 9.4건) · 측정일 다름(11/10 → 11/13)",
      "전용 허가 3개를 늘 따로 잡아 둡니다")
# 상징: 따로 나뉜 두 허가 묶음. 위는 자동결제 전용 3칸(추첨 색), 아래는 일반 결제 30칸
YEL = "#FFD43B"
bx, bw = SCX - 190, 380
add(f'<rect x="{bx}" y="{SCY - 178}" width="{bw}" height="150" rx="24" fill="{WHITE}" fill-opacity="0.1" stroke="{YEL}" stroke-width="4"/>')
text(bx + 28, SCY - 132, "자동결제 전용 허가 3", 28, 800, YEL)
for i in range(3):
    add(f'<rect x="{bx + 28 + i * 111}" y="{SCY - 108}" width="102" height="56" rx="14" fill="{YEL}"/>')
add(f'<rect x="{bx}" y="{SCY + 2}" width="{bw}" height="176" rx="24" fill="{WHITE}" fill-opacity="0.1" stroke="{WHITE}" stroke-opacity="0.6" stroke-width="4"/>')
text(bx + 28, SCY + 48, "일반 결제 허가 30", 28, 800, WHITE)
for i in range(30):
    add(f'<rect x="{bx + 28 + (i % 10) * 33}" y="{SCY + 70 + (i // 10) * 32}" width="27" height="24" rx="6" fill="{WHITE}" fill-opacity="0.85"/>')
save(4)

# ───────── ⑤ AI 상담 ─────────
frame(5, "AI 상담",
      "brochure-notion ⑤(선택지·이유·감수). LEDGER.md에 AI 상담 항목이 없어 숫자를 쓰지 않는다(문서 크기·메모리·비용 숫자 제외)")
title("벡터 DB 없이", "{출처}를 인용")
versus("벡터 DB 검색", "키워드 검색 + 출처 인용")
notes("규정 문서가 작아 키워드 검색으로 충분 · API 키 없이도 검색 모드로 동작",
      "외부 API에 기대고 호출 비용이 듭니다")
# 상징: 답변 말풍선 + 출처 표시
AIC = "#FFA94D"
bw, bh = 380, 250
x0, y0 = SCX - bw / 2, SCY - bh / 2 - 26
add(f'<path d="M{x0 + 30} {y0} H{x0 + bw - 30} a30 30 0 0 1 30 30 V{y0 + bh - 30} a30 30 0 0 1 -30 30 H{x0 + 130} l-58 54 l8 -54 H{x0 + 30} '
    f'a30 30 0 0 1 -30 -30 V{y0 + 30} a30 30 0 0 1 30 -30 z" fill="{WHITE}"/>')
for i, w_ in enumerate((300, 264, 190)):
    add(f'<rect x="{x0 + 40}" y="{y0 + 44 + i * 40}" width="{w_}" height="18" rx="9" fill="{P}" fill-opacity="0.22"/>')
add(f'<rect x="{x0 + 40}" y="{y0 + 168}" width="210" height="54" rx="27" fill="{AIC}"/>')
add(f'<circle cx="{x0 + 70}" cy="{y0 + 192}" r="10" fill="none" stroke="{INK}" stroke-width="4"/>')
add(f'<path d="M{x0 + 78} {y0 + 200} l9 9" stroke="{INK}" stroke-width="4" stroke-linecap="round"/>')
text(x0 + 100, y0 + 206, "출처 인용", 28, 800, INK)
save(5)

# 갤러리 카드 실제 크기(318×146) 미리보기
cards = "".join(
    f'<div class="c"><img src="decision-{n}.png" width="318" height="146"><p>{t}</p></div>'
    for n, t in ((1, "① Gateway 과부하"), (2, "② 입장 토큰 소모 시점"), (3, "③ 예매 ↔ 결제 SAGA"), (4, "④ 추첨 자동결제"), (5, "⑤ AI 상담")))
(HERE / "decisions-gallery.html").write_text(
    '<!doctype html><meta charset="utf-8"><style>body{margin:0;padding:24px;background:#FBFBFD;display:flex;gap:16px;'
    "font-family:Pretendard,'Noto Sans KR',sans-serif}.c{width:318px;background:#fff;border:1px solid #E1E4EA;border-radius:10px;overflow:hidden}"
    ".c img{display:block}.c p{margin:0;padding:10px 12px;font-size:14px;font-weight:600;color:#14161E}</style>" + cards, encoding="utf-8")
print("ok")
