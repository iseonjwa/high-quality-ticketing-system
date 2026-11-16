# 서비스별 이야기 하위 페이지의 갤러리 썸네일을 SVG로 만든다.
# 커버 배너와 같은 인디고 톤에 서비스 그림 하나, 제목, 한 줄 이야기, 원문으로 확인된 대표 숫자 하나를 둔다.
# 갤러리 카드는 가운데를 잘라 보여 주므로 글자와 그림을 가운데 띠(x 120~1080)에 둔다.
# 실행: python make_thumbnails.py → thumb-*.svg
# PNG: msedge --headless=new --window-size=1200,600 --force-device-scale-factor=2 --screenshot=<png> <svg 파일 URL>
import json
import math
from pathlib import Path

HERE = Path(__file__).parent
W, H = 1200, 600
P, P2, INK = "#3B3BD4", "#24248F", "#14161E"

# 서비스마다: 파일 이름, 순서, 제목, 한 줄 이야기, 대표 숫자(앞 → 뒤), 숫자 설명, 강조색, 그림
STORIES = json.loads((HERE / "thumbnails.json").read_text(encoding="utf-8"))


def icon(kind, cx, cy, c):
    """서비스를 뜻하는 단순 그림(흰색 + 강조색)."""
    s = []
    if kind == "gateway":  # 관문: 기둥 두 개와 아치, 가운데로 들어오는 화살표 줄
        s.append(f'<rect x="{cx - 120}" y="{cy - 90}" width="44" height="200" rx="12" fill="#FFFFFF"/>')
        s.append(f'<rect x="{cx + 76}" y="{cy - 90}" width="44" height="200" rx="12" fill="#FFFFFF"/>')
        s.append(f'<path d="M{cx - 120} {cy - 70} Q{cx} {cy - 190} {cx + 120} {cy - 70}" fill="none" stroke="#FFFFFF" stroke-width="30" stroke-linecap="round"/>')
        for i, y in enumerate([cy - 20, cy + 30, cy + 80]):
            s.append(f'<path d="M{cx - 60} {y} h80 m-24 -18 l24 18 l-24 18" fill="none" stroke="{c}" stroke-width="12" stroke-linecap="round" stroke-linejoin="round" opacity="{1 - i * 0.25}"/>')
    elif kind == "queue":  # 줄 선 사람들과 번호표
        for i in range(4):
            x = cx - 120 + i * 70
            op = 1 if i == 0 else 0.55
            s.append(f'<circle cx="{x}" cy="{cy - 40}" r="22" fill="#FFFFFF" opacity="{op}"/>')
            s.append(f'<rect x="{x - 28}" y="{cy - 10}" width="56" height="80" rx="24" fill="#FFFFFF" opacity="{op}"/>')
        s.append(f'<rect x="{cx - 160}" y="{cy - 140}" width="110" height="64" rx="14" fill="{c}"/>')
        s.append(f'<text x="{cx - 105}" y="{cy - 96}" font-size="34" font-weight="900" text-anchor="middle" fill="{INK}">No.1</text>')
    elif kind == "seat":  # 좌석 격자, 가운데 두 자리 체크
        for r in range(4):
            for col in range(5):
                x, y = cx - 130 + col * 54, cy - 100 + r * 54
                hit = (r, col) in {(1, 2), (1, 3)}
                s.append(f'<rect x="{x}" y="{y}" width="44" height="44" rx="10" fill="{c if hit else "#FFFFFF"}" opacity="{1 if hit else 0.5}"/>')
                if hit:
                    s.append(f'<path d="M{x + 12} {y + 23} l8 8 l13 -16" fill="none" stroke="{INK}" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>')
    elif kind == "payment":  # 카드 + 되돌림(보상) 배지
        s.append(f'<rect x="{cx - 150}" y="{cy - 100}" width="230" height="146" rx="18" fill="#FFFFFF"/>')
        s.append(f'<rect x="{cx - 150}" y="{cy - 64}" width="230" height="26" fill="{INK}" opacity="0.85"/>')
        s.append(f'<rect x="{cx - 126}" y="{cy - 14}" width="42" height="30" rx="5" fill="{c}"/>')
        # 오른쪽 아래 원형 배지 안에 반시계 되돌림 화살표(↺)
        bx, by, br, ar = cx + 70, cy + 60, 62, 30
        s.append(f'<circle cx="{bx}" cy="{by}" r="{br}" fill="{c}" stroke="#24248F" stroke-width="8"/>')
        a0, a1 = math.radians(-40), math.radians(220)  # 오른쪽 위에서 시작해 반시계로 돌아 왼쪽 아래에서 끝
        x0, y0 = bx + ar * math.cos(a0), by - ar * math.sin(a0)
        x1, y1 = bx + ar * math.cos(a1), by - ar * math.sin(a1)
        s.append(f'<path d="M{x0:.1f} {y0:.1f} A{ar} {ar} 0 1 0 {x1:.1f} {y1:.1f}" fill="none" stroke="{INK}" stroke-width="10" stroke-linecap="round"/>')
        # 화살촉: 끝점에서 접선 방향(반시계 진행 방향)으로
        tx, ty = -math.sin(a1), -math.cos(a1)  # 반시계 진행 방향의 화면 좌표 접선
        nx, ny = math.cos(a1), -math.sin(a1)   # 바깥 법선
        tip = (x1 + tx * 14, y1 + ty * 14)
        p1 = (x1 + nx * 12 - tx * 2, y1 + ny * 12 - ty * 2)
        p2 = (x1 - nx * 12 - tx * 2, y1 - ny * 12 - ty * 2)
        s.append(f'<path d="M{p1[0]:.1f} {p1[1]:.1f} L{tip[0]:.1f} {tip[1]:.1f} L{p2[0]:.1f} {p2[1]:.1f} Z" fill="{INK}" stroke="{INK}" stroke-width="4" stroke-linejoin="round"/>')
    elif kind == "lottery":  # 티켓과 별
        s.append(f'<path d="M{cx - 140} {cy - 70} h280 v40 a26 26 0 0 0 0 52 v40 h-280 v-40 a26 26 0 0 0 0 -52 z" fill="#FFFFFF"/>')
        s.append(f'<line x1="{cx + 60}" y1="{cy - 60}" x2="{cx + 60}" y2="{cy + 52}" stroke="{INK}" stroke-width="4" stroke-dasharray="8 8" opacity="0.4"/>')
        s.append(f'<text x="{cx - 40}" y="{cy + 12}" font-size="44" font-weight="900" text-anchor="middle" fill="{P}">당첨</text>')
        for (dx, dy, r) in [(110, -120, 26), (-150, -110, 16), (150, 100, 14)]:
            x, y = cx + dx, cy + dy
            s.append(f'<path d="M{x} {y - r} L{x + r * 0.3} {y - r * 0.3} L{x + r} {y} L{x + r * 0.3} {y + r * 0.3} L{x} {y + r} L{x - r * 0.3} {y + r * 0.3} L{x - r} {y} L{x - r * 0.3} {y - r * 0.3} Z" fill="{c}"/>')
    return "\n".join(s)


for st in STORIES:
    c = st["accent"]
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="Pretendard, \'Noto Sans KR\', sans-serif">',
         '<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">'
         f'<stop offset="0" stop-color="{P}"/><stop offset="1" stop-color="{P2}"/></linearGradient></defs>',
         f'<rect width="{W}" height="{H}" fill="url(#bg)"/>',
         f'<circle cx="950" cy="300" r="210" fill="#FFFFFF" fill-opacity="0.07"/>',
         icon(st["icon"], 950, 300, c)]
    x = 130
    o.append(f'<text x="{x}" y="150" font-size="34" font-weight="800" fill="{c}">{st["no"]} · {st["tag"]}</text>')
    o.append(f'<text x="{x - 3}" y="236" font-size="70" font-weight="900" fill="#FFFFFF" letter-spacing="-2">{st["title"]}</text>')
    o.append(f'<text x="{x}" y="296" font-size="34" font-weight="600" fill="#FFFFFF" fill-opacity="0.88">{st["line"]}</text>')
    # 대표 숫자 상자
    extra = st.get("extra", [])  # 함께 바뀐 다른 지표(선택)
    o.append(f'<rect x="{x}" y="346" width="600" height="{140 + 40 * len(extra)}" rx="22" fill="#FFFFFF" fill-opacity="0.12"/>')
    for i, line in enumerate(extra):
        o.append(f'<text x="{x + 30}" y="{512 + 40 * i}" font-size="28" font-weight="800" fill="#FFFFFF">{line}</text>')
    if st["before"]:
        o.append(f'<text x="{x + 30}" y="430" font-size="56" font-weight="900" fill="#FFFFFF">{st["before"]} <tspan fill="{c}">→ {st["after"]}</tspan></text>')
    else:  # 전후가 없는 이야기는 숫자 하나만
        o.append(f'<text x="{x + 30}" y="430" font-size="56" font-weight="900" fill="{c}">{st["after"]}</text>')
    o.append(f'<text x="{x + 30}" y="468" font-size="26" font-weight="600" fill="#FFFFFF" fill-opacity="0.75">{st["metric"]}</text>')
    o.append("</svg>")
    (HERE / f'thumb-{st["file"]}.svg').write_text("\n".join(o), encoding="utf-8")
print("ok", len(STORIES))
