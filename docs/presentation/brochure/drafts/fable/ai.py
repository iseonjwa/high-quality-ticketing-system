# 브로셔 큰 그림 "AI 활용 인포그래픽"(1600×900)을 SVG로 만든다.
# 규칙: ../../design-kit.md. 숫자와 사례는 ../../census/LEDGER.md 7절(7-1 통계, 7-2 대표 사례, 7-4)과 3-7에서만 가져온다.
# 구성: 왼쪽 판단 기록 통계(도넛, 0에서 시작하는 비율) / 오른쪽 "AI 제안 → 우리 판단 → 측정으로 닫음" 흐름에 맞춘 대표 사례 3개 / 아래 "거부가 늘 옳지는 않았다".
# 실행: python ai.py → ai.svg
# PNG: msedge --headless=new --window-size=1600,900 --force-device-scale-factor=2 --screenshot=<png> <svg 파일 URL>
import math
from pathlib import Path

HERE = Path(__file__).parent
W, H = 1600, 900
P, P2, O, G, GL, INK = "#3B3BD4", "#24248F", "#F59F00", "#2B8A3E", "#63E6BE", "#14161E"
BODY_DARK, BODY, GRAY = "#373B4A", "#676C7E", "#C9CDD6"
WHITE = "#FFFFFF"
M, TOP, BOT = 64, 164, 836

o = []


def add(s):
    o.append(s)


def text(x, y, s, size, weight=600, fill=WHITE, anchor="start", opacity=None):
    op = f' fill-opacity="{opacity}"' if opacity is not None else ""
    add(f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" font-weight="{weight}" text-anchor="{anchor}" fill="{fill}"{op}>{s}</text>')


def width(s, size):
    """글자 폭 어림(한글 0.93, 그 밖 0.56, 빈칸 0.25). 칩 크기를 잡는 데만 쓴다."""
    return sum(0.25 if ch == " " else 0.93 if ord(ch) > 0x2000 else 0.56 for ch in s) * size


add(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="Pretendard, \'Noto Sans KR\', sans-serif">')
add("<!-- 숫자 출처: docs/presentation/brochure/census/LEDGER.md 7절(AI 활용 소재)과 3-7. 요소마다 원장 위치를 주석으로 적는다 -->")
add("<defs>")
add(f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{P}"/><stop offset="1" stop-color="{P2}"/></linearGradient>')
add('<linearGradient id="beam" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.2"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>')
add("</defs>")
add(f'<rect width="{W}" height="{H}" fill="url(#bg)"/>')
# 빛줄기는 머리 부분에서만 보이게 한다(타일 글자 위로 지나지 않게)
add(f'<clipPath id="head"><rect width="{W}" height="{TOP - 8}"/></clipPath><g clip-path="url(#head)">')
for x0, x1, x2 in [(1150, 1090, 1330), (1290, 1380, 1640), (980, 700, 940)]:
    add(f'<polygon points="{x0 - 6},0 {x0 + 6},0 {x2},{H} {x1},{H}" fill="url(#beam)"/>')
add("</g>")

add(f'<text x="{M - 3}" y="104" font-size="66" font-weight="900" fill="{WHITE}" letter-spacing="-2">AI 제안, <tspan fill="{O}">판단</tspan>은 사람이</text>')
text(M, 142, "제안을 받을 때마다 수용·거부를 기록하고, 갈리는 것은 측정으로 닫았습니다", 22, 600, WHITE, opacity=0.7)

# ───────── 왼쪽: 판단 기록 통계 ─────────
# LEDGER.md 7-1 첫 행: origin/dev 공통 표(shared: 예) 208건 = 수용 99 · 부분 수용 37 · 거부 67 · 보류 5
LW = 404
add(f'<rect x="{M}" y="{TOP}" width="{LW}" height="{BOT - TOP}" rx="24" fill="{WHITE}"/>')
lx = M + 30
text(lx, TOP + 46, "AI 판단 기록 · 팀 공통 표", 27, 800, BODY_DARK)
TOTAL = 208
PARTS = [("수용", 99, P), ("부분 수용", 37, "#9C9CF0"), ("거부", 67, O), ("보류", 5, GRAY)]
assert sum(n for _, n, _ in PARTS) == TOTAL
# 퍼센트 계산식: 건수 ÷ 208 × 100, 소수 첫째 자리 반올림
# 99 ÷ 208 = 47.6% · 37 ÷ 208 = 17.8% · 67 ÷ 208 = 32.2% · 5 ÷ 208 = 2.4% (합 100.0%)
dcx, dcy, R, r = M + LW / 2, TOP + 210, 128, 80
ang = -90.0   # 12시에서 시작해 시계 방향. 각도 = 건수 ÷ 208 × 360(0에서 시작하는 비율)
for name, n, color in PARTS:
    a0, a1 = ang, ang + n / TOTAL * 360
    large = 1 if a1 - a0 > 180 else 0
    pt = lambda a, rad: (dcx + rad * math.cos(math.radians(a)), dcy + rad * math.sin(math.radians(a)))
    (x0, y0), (x1, y1), (x2, y2), (x3, y3) = pt(a0, R), pt(a1, R), pt(a1, r), pt(a0, r)
    add(f'<path d="M{x0:.2f} {y0:.2f} A{R} {R} 0 {large} 1 {x1:.2f} {y1:.2f} L{x2:.2f} {y2:.2f} A{r} {r} 0 {large} 0 {x3:.2f} {y3:.2f} Z" '
        f'fill="{color}" stroke="{WHITE}" stroke-width="3"/>')
    ang = a1
add(f'<text x="{dcx}" y="{dcy + 12}" text-anchor="middle" font-weight="900" fill="{INK}" letter-spacing="-1">'
    f'<tspan font-size="58">208</tspan><tspan font-size="28" dx="3">건</tspan></text>')
text(dcx, dcy + 42, "판단 기록", 18, 600, BODY, "middle")
ly = TOP + 396
for i, (name, n, color) in enumerate(PARTS):
    y = ly + i * 40
    strong = name == "거부"
    add(f'<rect x="{lx}" y="{y - 17}" width="20" height="20" rx="5" fill="{color}"/>')
    text(lx + 32, y, name, 22, 800 if strong else 600, INK if strong else BODY_DARK)
    text(M + LW - 30, y, f"{n}건 · {n / TOTAL * 100:.1f}%", 22, 800 if strong else 600, INK if strong else BODY_DARK, "end")
add(f'<rect x="{lx}" y="{ly + 138}" width="{LW - 60}" height="1.5" fill="#E1E4EA"/>')
# 기준 병기: LEDGER.md 7-1 둘째 행(개별 기록 파일 241건 중 거부 94건, 39.0%), 3-7 알려진 문제(기록 없이 지나간 판단은 세지 못함)
for i, line in enumerate(["기준: origin/dev 공통 표(팀 공유 표시)",
                          "개별 기록 파일로 세면 241건 중",
                          "거부 94건(39.0%)",
                          "기록 없이 지나간 판단은 세지 못함"]):
    text(lx, ly + 170 + i * 26, line, 18, 600, BODY)

# ───────── 오른쪽: 흐름 머리 + 대표 사례 3개 ─────────
RX = M + LW + 24
RW = W - M - RX
CG = 24
CW = (RW - 2 * CG) / 3
COLX = [RX + i * (CW + CG) for i in range(3)]
HEAD_H = 60
for i, (num, label) in enumerate([("1", "AI 제안"), ("2", "우리 판단"), ("3", "측정으로 닫음")]):
    x = COLX[i]
    add(f'<rect x="{x:.1f}" y="{TOP}" width="{CW:.1f}" height="{HEAD_H}" rx="30" fill="{WHITE}"/>')
    add(f'<circle cx="{x + 32:.1f}" cy="{TOP + 30}" r="17" fill="{P}"/>')
    text(x + 32, TOP + 38.5, num, 23, 900, WHITE, "middle")
    text(x + 60, TOP + 40, label, 27, 800, P)
    if i < 2:   # 머리 사이 화살표
        ax = x + CW + CG / 2
        add(f'<path d="M{ax - 7:.1f} {TOP + 19} L{ax + 6:.1f} {TOP + 30} L{ax - 7:.1f} {TOP + 41}" fill="none" stroke="{WHITE}" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>')


def verdict(x, y, s, kind):
    """판단 알약. 거부는 주황, 수용은 밝은 초록."""
    fill, fg = (O, INK) if kind == "거부" else (GL, P2)
    w = width(s, 18) + 28
    add(f'<rect x="{x:.1f}" y="{y - 22}" width="{w:.0f}" height="30" rx="15" fill="{fill}"/>')
    text(x + w / 2, y - 0.5, s, 18, 800, fg, "middle")


# 대표 사례: LEDGER.md 7-2 Top 8의 1번·2번·7번(모두 결과 숫자가 있는 것)
CASES = [
    # 1번(11/11 kangmin): 굿풋 L128b 1,721 대 L64 1,696 → 차이 25건, 25 ÷ 1,696 = 1.5%. 같은 128을 두 번 잰 차이 5%. p95는 64가 87ms, 128이 162ms
    dict(tag="11/11 · Gateway 동시 처리 상한",
         c1=("상한 64 → 128로 올리기", "기준을 지키는 가장 큰 값"),
         c2=("거부", "거부", "상한 64 유지", "차이가 반복 변동보다 작음"),
         c3=("처리 차이 1.5% &lt; 변동 5%", "응답 p95 87ms 대 162ms", "초당 2,000건 · 배포 서버")),
    # 2번(11/13 kwonwoo): C2(500건) 성공 83 → 56건(−32.5%), lock_timeout 50ms는 78·87건. ADR 0061은 로컬 측정(3-3 예상 질문 2)
    dict(tag="11/13 · 좌석 선점 잠금",
         c1=("SKIP LOCKED 도입", "잠긴 좌석은 기다리지 않기"),
         c2=("거부 → 새 추천 수용", "거부", "잠금 대기 500 → 50ms", "구현해서 재 본 뒤 판단"),
         c3=("선점 성공 83 → 56건", "50ms로는 78건·87건", "동시 요청 시험 C2(500건) · 로컬")),
    # 7번(11/9 kangmin): 92.8초 → 3.6초(25.8배, 3회 중앙값), 변이 31가지 중 30가지 동일
    dict(tag="11/9 · 기록 검사 도구",
         c1=("검사를 한 번에 묶어 실행", "파일마다 실행하던 방식 대신"),
         c2=("수용", "수용", "변이 비교 뒤 반영", "바로 올리지 않고 확인"),
         c3=("92.8초 → 3.6초(25.8배)", "변이 31가지 중 30가지 동일", "로컬 · 3회 중앙값")),
]
ROW_Y0, ROW_H, ROW_G = TOP + HEAD_H + 16, 128, 14
for k, c in enumerate(CASES):
    y = ROW_Y0 + k * (ROW_H + ROW_G)
    add(f'<rect x="{RX}" y="{y}" width="{RW}" height="{ROW_H}" rx="20" fill="{WHITE}" fill-opacity="0.1" stroke="{WHITE}" stroke-opacity="0.22" stroke-width="1.5"/>')
    for j in (1, 2):   # 열 구분선
        add(f'<rect x="{COLX[j] - CG / 2:.1f}" y="{y + 18}" width="1.5" height="{ROW_H - 36}" fill="{WHITE}" fill-opacity="0.22"/>')
    x1, x2, x3 = COLX[0] + 22, COLX[1] + 8, COLX[2] + 8
    text(x1, y + 36, c["tag"], 18, 800, GL)
    text(x1, y + 72, c["c1"][0], 23, 800, WHITE)
    text(x1, y + 104, c["c1"][1], 20, 600, WHITE, opacity=0.7)
    verdict(x2, y + 40, c["c2"][0], c["c2"][1])
    text(x2, y + 72, c["c2"][2], 23, 800, WHITE)
    text(x2, y + 104, c["c2"][3], 20, 600, WHITE, opacity=0.7)
    text(x3, y + 40, c["c3"][0], 23, 800, WHITE)
    text(x3, y + 72, c["c3"][1], 20, 600, WHITE, opacity=0.92)
    text(x3, y + 104, c["c3"][2], 20, 600, WHITE, opacity=0.7)

# ───────── 아래: 거부가 늘 옳지는 않았다 ─────────
# LEDGER.md 7-4 첫 행(원문이 직접 적은 "거부했으나 측정은 AI 편" 1건)과 3-1 타임라인 11/11:
# 0a·0b(100ms)·B20(20ms) 모두 미열림, 2,500/s·서버 CPU 99%까지 열림 0회, 느린 호출 최대 24%
by = ROW_Y0 + 3 * (ROW_H + ROW_G) + 2
bh = BOT - by
add(f'<rect x="{RX}" y="{by}" width="{RW}" height="{bh}" rx="20" fill="{WHITE}"/>')
add(f'<rect x="{RX}" y="{by}" width="10" height="{bh}" fill="{O}"/>')
add(f'<rect x="{RX}" y="{by}" width="30" height="{bh}" rx="20" fill="{O}"/>')
add(f'<rect x="{RX + 10}" y="{by}" width="24" height="{bh}" fill="{WHITE}"/>')
bx = RX + 38
by += 16   # 글 세 줄을 카드 세로 가운데로
text(bx, by + 44, "거부가 늘 옳지는 않았다", 30, 900, INK)
add(f'<text x="{bx}" y="{by + 78}" font-size="20" font-weight="600" fill="{BODY_DARK}">AI 추천(맨 앞에서 유입 제한)을 미루고 차단기를 예민하게 하는 실험부터 → '
    f'<tspan font-weight="800" fill="{INK}">차단기 열림 0회</tspan>. 측정은 AI 편이었습니다</text>')
text(bx, by + 106, "조건: 11/11 · 초당 2,500건 · 서버 CPU 99% · 기준을 20ms까지 낮춰도 느린 호출 최대 24% · 원문이 적은 사례 1건", 18, 600, BODY)
add("</svg>")

(HERE / "ai.svg").write_text("\n".join(o), encoding="utf-8")
print("ok")
