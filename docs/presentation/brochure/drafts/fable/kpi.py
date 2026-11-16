# 브로셔 메인 그림 "숫자로 보는 이선좌"(KPI 타일 6칸, 1600×900)를 SVG로 만든다.
# 규칙: ../../design-kit.md. 숫자와 조건 문구는 ../../census/LEDGER.md 1절 히어로 숫자 ①~⑥에서만 가져온다.
# 실행: python kpi.py → kpi.svg
# PNG: msedge --headless=new --window-size=1600,900 --force-device-scale-factor=2 --screenshot=<png> <svg 파일 URL>
from pathlib import Path

HERE = Path(__file__).parent
W, H = 1600, 900
P, P2, O, G, GL, INK = "#3B3BD4", "#24248F", "#F59F00", "#2B8A3E", "#63E6BE", "#14161E"
BODY_DARK, BODY = "#373B4A", "#676C7E"
WHITE = "#FFFFFF"

M, GAP = 64, 24            # 안전 영역, 타일 간격
TOP = 164                  # 타일 시작 y
TW = (W - 2 * M - 2 * GAP) / 3
ROW_H = [348, 300]         # 윗줄은 조건이 5줄까지(③) 들어가서 더 높다
ROW_Y = [TOP, TOP + ROW_H[0] + GAP]
PAD = 30
LINE = 27                  # 조건 줄 간격

o = []


def add(s):
    o.append(s)


def rect_of(i):
    return M + (i % 3) * (TW + GAP), ROW_Y[i // 3], TW, ROW_H[i // 3]


def run(parts):
    """(글자, 크기, 색, 투명도, 앞 간격[, 위로 올림]) 묶음을 한 줄 tspan으로 만든다."""
    out = []
    for s, size, fill, op, dx, *rest in parts:
        opa = f' fill-opacity="{op}"' if op is not None else ""
        d = f' dx="{dx}"' if dx else ""
        out.append(f'<tspan font-size="{size}" fill="{fill}"{opa}{d}>{s}</tspan>')
    return "".join(out)


def warn_icon(x, y, color, mark):
    """경고 세모(글리프 대신 직접 그려 이모지로 바뀌지 않게 한다). (x, y)는 글자 기준선 왼쪽."""
    add(f'<path d="M{x + 10} {y - 17} L{x + 20.5} {y + 1.5} H{x - 0.5} Z" fill="{color}" stroke="{color}" stroke-width="2.4" stroke-linejoin="round"/>')
    add(f'<rect x="{x + 8.8}" y="{y - 10.5}" width="2.4" height="6.5" rx="1.2" fill="{mark}"/>')
    add(f'<circle cx="{x + 10}" cy="{y - 1.2}" r="1.45" fill="{mark}"/>')


def tile(i, ledger, headline, big, cond, hero=False, extra=None):
    """KPI 타일. big은 run() 묶음, cond는 줄 목록.
    줄이 '!'로 시작하면 경고 줄(세모 + 굵은 흰 글자), {…}로 감싼 부분은 전·후 숫자라 굵게 쓴다."""
    x, y, w, h = rect_of(i)
    add(f"<!-- 타일 {i + 1}: LEDGER.md 1절 {ledger} -->")
    if hero:
        add(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h}" rx="24" fill="{WHITE}"/>')
        head_fill, head_op, cond_fill, cond_op, strong = BODY_DARK, None, BODY, None, INK
    else:
        add(f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h}" rx="24" fill="{WHITE}" fill-opacity="0.1" stroke="{WHITE}" stroke-opacity="0.22" stroke-width="1.5"/>')
        head_fill, head_op, cond_fill, cond_op, strong = WHITE, 0.92, WHITE, 0.7, WHITE
    tx = x + PAD
    op = f' fill-opacity="{head_op}"' if head_op else ""
    add(f'<text x="{tx:.1f}" y="{y + 46:.1f}" font-size="27" font-weight="800" fill="{head_fill}"{op}>{headline}</text>')
    add(f'<text x="{tx - 4:.1f}" y="{y + 180:.1f}" font-weight="900" letter-spacing="-2">{run(big)}</text>')
    if extra:
        extra(tx, y + 180)
    for n, line in enumerate(cond):
        ly = y + 218 + n * LINE
        if line.startswith("!"):
            warn_icon(tx, ly, O, P2)
            add(f'<text x="{tx + 29:.1f}" y="{ly:.1f}" font-size="20" font-weight="800" fill="{WHITE}">{line[1:]}</text>')
            continue
        op = f' fill-opacity="{cond_op}"' if cond_op else ""
        line = line.replace("{", f'<tspan font-weight="800" fill="{strong}" fill-opacity="1">').replace("}", "</tspan>")
        add(f'<text x="{tx:.1f}" y="{ly:.1f}" font-size="20" font-weight="600" fill="{cond_fill}"{op}>{line}</text>')


add(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="Pretendard, \'Noto Sans KR\', sans-serif">')
add("<!-- 숫자 출처: docs/presentation/brochure/census/LEDGER.md 1절 히어로 숫자 ①~⑥. 타일마다 원장 id를 주석으로 적는다 -->")
add("<defs>")
add(f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{P}"/><stop offset="1" stop-color="{P2}"/></linearGradient>')
add('<linearGradient id="beam" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.2"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>')
# 빛줄기는 타일 밖(머리·틈·가장자리)에서만 보이게 타일 자리를 오려 낸다. 카드 글자 위로 빛이 지나지 않는다
holes = "".join(f"M{x:.1f} {y}h{w:.1f}v{h}h{-w:.1f}z" for x, y, w, h in (rect_of(i) for i in range(6)))
add(f'<clipPath id="outside"><path clip-rule="evenodd" d="M0 0h{W}v{H}h{-W}z{holes}"/></clipPath>')
add("</defs>")
add(f'<rect width="{W}" height="{H}" fill="url(#bg)"/>')
add('<g clip-path="url(#outside)">')
for x0, x1, x2 in [(1010, 760, 1010), (1150, 1090, 1330), (1290, 1380, 1640), (860, 520, 740)]:
    add(f'<polygon points="{x0 - 6},0 {x0 + 6},0 {x2},{H} {x1},{H}" fill="url(#beam)"/>')
add("</g>")

# 제목과 프레임 문구
add(f'<text x="{M - 3}" y="104" font-size="66" font-weight="900" fill="{WHITE}" letter-spacing="-2"><tspan fill="{O}">숫자</tspan>로 보는 이선좌</text>')
add(f'<text x="{M}" y="142" font-size="22" font-weight="600" fill="{WHITE}" fill-opacity="0.7">모든 숫자는 배포 EC2 한 대 + 별도 부하 발생기 EC2에서 쟀습니다</text>')

# ① 정확성 값: 흰 타일 + 가장 큰 숫자로 첫 시선을 받는다
tile(0, "①", "같은 좌석, 두 번 팔지 않음",
     [("중복 판매", 46, INK, None, 0), ("0", 150, G, None, 14), ("건", 66, G, None, 4)],
     ["확인한 실행에서 0건 · 선점 충돌이 잦은 가운데", "사용자 3,000명 · 매진 시험 아님 · 장비 1대"], hero=True)
# ② 같은 날 묶음 비교
tile(1, "②", "좌석·선점 단계 실패",
     [("약", 46, WHITE, 0.92, 0), ("20", 122, O, None, 10), ("분의", 58, O, None, 4), ("1", 122, O, None, 8)],
     ["{403명·310명대 → 24명·12명}", "(개선 전후 각 2회, 같은 날 묶음 비교)", "결제 실패는 별도 집계"])
# ③ 같은 날·같은 배포에서 상한만 바꾼 비교. 거절이 늘어난 대가를 같이 적는다
tile(2, "③", "같은 서버로 제때 끝낸 순번 조회",
     [("+30", 122, O, None, 0), ("%", 70, O, None, 4)],
     ["200ms 안 성공 {1,304 → 1,696건/초}", "초당 2,000건 · 같은 날 1회",
      "대신 거절 {1,678 → 8,715건}", "성공률 {89.04 → 85.17%}", "성공 응답 p95 571 → 87ms"])
# ④ 날이 다른 비교
tile(3, "④", "추첨 자동결제",
     [("약", 46, WHITE, 0.92, 0), ("3", 122, O, None, 10), ("배", 66, O, None, 4), ("빠르게", 50, WHITE, None, 14)],
     ["당첨 약 2,000건 {약 11분 → 4분 안 100%}", "!측정일 다름(11/10 → 11/13)"])


# ⑤ 날이 다른 비교. 배수 옆에 실제 전·후 값을 중간 크기로 둔다
def before_after(tx, base):
    bx = tx + 250
    add(f'<text x="{bx:.1f}" y="{base - 50:.1f}" font-size="24" font-weight="800" fill="{WHITE}" fill-opacity="0.7">초당</text>')
    add(f'<text x="{bx - 1:.1f}" y="{base:.1f}" font-size="40" font-weight="900" fill="{WHITE}">5 → 200</text>')


tile(4, "⑤", "좌석 조회가 버티는 한계",
     [("40", 122, O, None, 0), ("배", 66, O, None, 4)],
     ["실패 0 최대 · 4단계 누적 · 상한 끔 조건", "!측정일 다름(11/12 → 11/14)"], extra=before_after)
# ⑥ 단일 측정 값. ①과 경쟁하지 않게 숫자를 낮춘다(①의 약 75%)
tile(5, "⑥", "응모 몰려도 거절 0건",
     [("거절", 54, WHITE, 0.7, 0), ("0", 110, GL, None, 14), ("건", 58, GL, None, 4)],
     ["서버 과부하 거절(503) 0건 · 초당 800건까지", "p95 10.58ms · 배포 서버 1회 · 11/10 구성,", "현재 구성 재측정 안 함"])
add("</svg>")

(HERE / "kpi.svg").write_text("\n".join(o), encoding="utf-8")
print("ok")
