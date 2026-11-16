# 숫자로 보는 이선좌 KPI 그림 생성. 숫자 출처: 사용자 제공 문구(원장 id는 미확인)
import os
H = os.path.dirname(os.path.abspath(__file__))
F = "Pretendard, 'Noto Sans KR', sans-serif"
G, I, GR = "#2B8A3E", "#3B3BD4", "#676C7E"
tiles = [
 ("같은 좌석, 두 번 팔지 않음", "중복 판매 0건", 70, G,
  ["확인한 실행(사용자 3,000명 오픈", "측정 등)에서 0건", "매진 시험 아님 · 장비 1대"], None),
 ("좌석 단계에서 실패한 사용자", "약 20분의 1", 70, I,
  ["403·310대 → 24·12명", "(개선 전후 각 2회, 같은 날 묶음 비교)", "대기열 통과 뒤 좌석·선점 단계 기준"], None),
 ("같은 서버로 제때 끝낸 요청", "+30%", 108, I,
  ["200ms 안 성공 1,304 → 1,696건/초", "초당 2,000건 · 성공 응답 p95 571 → 87ms", "넘친 요청은 503"], None),
 ("추첨 자동결제", "약 3배 빠르게", 64, I,
  ["당첨 약 2,000건 약 11분 → 4분 안 100%"], "측정일 다름(11/10 → 11/13)"),
 ("좌석 조회가 버티는 한계", "40배", 108, I,
  ["실패 0 최대 초당 5 → 200", "상한 끔 조건"], "측정일 다름"),
 ("응모 마감 직전 몰림", "503 0건", 96, G,
  ["초당 800건까지 · p95 10.58ms", "배포 서버 1회(11/10 구성)"], None),
]
W, Hh = 1600, 900
tw, th, gap, x0, y0 = 474, 311, 25, 64, 190
s = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{Hh}" viewBox="0 0 {W} {Hh}" font-family="{F}">',
 '<defs><linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#3B3BD4"/><stop offset="1" stop-color="#24248F"/></linearGradient>'
 '<linearGradient id="beam" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".2"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></linearGradient></defs>',
 f'<rect width="{W}" height="{Hh}" fill="url(#bg)"/>',
 '<polygon points="300,0 520,0 760,190 160,190" fill="url(#beam)"/><polygon points="1080,0 1300,0 1480,190 940,190" fill="url(#beam)"/>',
 f'<text x="{W/2}" y="112" text-anchor="middle" font-size="68" font-weight="900" fill="#fff">숫자로 보는 <tspan fill="#F59F00">이선좌</tspan></text>',
 f'<text x="{W/2}" y="158" text-anchor="middle" font-size="26" font-weight="600" fill="#fff" fill-opacity=".78">모든 숫자는 배포 EC2 한 대 + 별도 부하 발생기 EC2에서 쟀습니다</text>']
for i, (hd, big, fs, col, lines, warn) in enumerate(tiles):
    x = x0 + (i % 3) * (tw + gap); y = y0 + (i // 3) * (th + 24)
    s.append(f'<rect x="{x}" y="{y}" width="{tw}" height="{th}" rx="24" fill="#fff"/>')
    s.append(f'<rect x="{x+28}" y="{y+30}" width="8" height="30" rx="4" fill="{col}"/>')
    s.append(f'<text x="{x+48}" y="{y+55}" font-size="28" font-weight="800" fill="#373B4A">{hd}</text>')
    s.append(f'<text x="{x+28}" y="{y+168}" font-size="{fs}" font-weight="900" fill="{col}" letter-spacing="-1">{big}</text>')
    ly = y + 214
    for t in lines:
        s.append(f'<text x="{x+28}" y="{ly}" font-size="21" font-weight="600" fill="{GR}">{t}</text>'); ly += 28
    if warn:
        s.append(f'<polygon points="{x+40},{ly-18} {x+52},{ly+2} {x+28},{ly+2}" fill="#F59F00"/>'
                 f'<rect x="{x+39}" y="{ly-11}" width="2.4" height="7" fill="#fff"/><rect x="{x+39}" y="{ly-2}" width="2.4" height="2.4" fill="#fff"/>')
        s.append(f'<text x="{x+62}" y="{ly}" font-size="21" font-weight="800" fill="#B36B00">{warn}</text>')
s.append('</svg>')
open(os.path.join(H, "kpi.svg"), "w", encoding="utf-8").write("\n".join(s))
