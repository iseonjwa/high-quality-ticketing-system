#!/usr/bin/env python3
# Gateway 안전망 상한 측정(2026-11-13, O1 15,000명) 발표 그림 두 장을 SVG로 만든다. 외부 라이브러리 없이 돈다.
# 숫자는 아래 DATA·SERIES에 측정 원자료에서 옮겨 적었다(출처: 같은 폴더 2026-11-13-safety-net-limit-source.md).
# 사용: python 2026-11-13-safety-net-limit-charts.py [1초 수집 CSV 폴더]  → 같은 폴더에 SVG 두 개
#       PNG는 script/render-diagram.sh <svg>로 만든다(docs/presentation/README.md 규칙)
import csv, os, sys

HERE = os.path.dirname(os.path.abspath(__file__))
BG, INK, BODY, SUB, GRID = '#FBFBFD', '#14161E', '#373B4A', '#676C7E', '#E1E4EA'
PRIMARY, ORANGE, GRAY = '#3B3BD4', '#D9480F', '#8D92A3'
FONT = "font-family=\"'Pretendard','Malgun Gothic','Apple SD Gothic Neo',sans-serif\""

# 회차별 값(측정 기록 3·6절). None은 지표가 없는 칸
DATA = [
    # 이름, 처리 중 최대(1초), 컨테이너 메모리 최대 MiB, 힙 최대 MiB, 상한 거절(k6), 확정 예매, 좌석 조회 p95 ms, 서버 CPU 90%+ 초
    ('64', 64, 483.4, 194, 250448, 2574, 497, 330),
    ('256', 256, 552.0, 237, 93037, 3812, 1224, 366),
    ('512 ①', 490, 615.2, 278, 0, 4758, 1888, 402),
    ('512 ②', 486, 602.7, 283, 0, 4684, 1887, 373),
    ('끔', 482, 609.4, 284, 0, 4503, 1964, 388),
]


def text(x, y, s, size=20, color=BODY, anchor='start', weight=400):
    return f'<text x="{x}" y="{y}" {FONT} font-size="{size}" fill="{color}" text-anchor="{anchor}" font-weight="{weight}">{s}</text>'


def frame(title, subtitle, source):
    head = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1600 900" width="1600" height="900">'
            f'<rect width="1600" height="900" fill="{BG}"/>' + text(40, 52, title, 34, INK, weight=700)
            + text(40, 88, subtitle, 22, BODY))
    return head, text(40, 866, source, 15, SUB) + '</svg>'


def bars(x0, y0, w, h, label, values, unit, fmt, ref=None, ref_label=''):
    """작은 막대 그래프 하나. values: [(이름, 값)]"""
    out = [text(x0, y0 - 18, label, 22, INK, weight=700)]
    vals = [v for _, v in values if v is not None]
    top = max(vals + ([ref] if ref else [])) * 1.15 or 1
    out.append(f'<line x1="{x0}" y1="{y0 + h}" x2="{x0 + w}" y2="{y0 + h}" stroke="{GRID}" stroke-width="1.5"/>')
    bw = w / len(values) * 0.6
    for i, (name, v) in enumerate(values):
        cx = x0 + w / len(values) * (i + 0.5)
        out.append(text(cx, y0 + h + 28, name, 18, SUB, 'middle'))
        if v is None:
            out.append(text(cx, y0 + h - 10, '측정 전', 15, SUB, 'middle'))
            continue
        bh = h * v / top
        color = GRAY if name == '끔' else PRIMARY
        out.append(f'<rect x="{cx - bw / 2:.1f}" y="{y0 + h - bh:.1f}" width="{bw:.1f}" height="{bh:.1f}" rx="4" fill="{color}"/>')
        out.append(text(cx, y0 + h - bh - 8, fmt(v) + unit, 17, BODY, 'middle', 600))
    if ref:
        ry = y0 + h - h * ref / top
        out.append(f'<line x1="{x0}" y1="{ry:.1f}" x2="{x0 + w}" y2="{ry:.1f}" stroke="{ORANGE}" stroke-width="2" stroke-dasharray="7 4"/>')
        out.append(text(x0 + w, ry - 8, ref_label, 15, '#A8400E', 'end'))
    return ''.join(out)


def chart_summary():
    head, tail = frame('상한 값별 Gateway 처리 중 수와 메모리',
                       '상한을 끄거나 512로 두어도 처리 중 최대는 약 490, 컨테이너 메모리는 1GiB의 60% 안이었다',
                       '2026-11-13 배포 서버(m7i.xlarge), O1 오픈 피크 15,000명·입장 50/s·PG 300ms, 값마다 1회(512는 2회), main 43015207. 1초 수집(gateway cgroup·actuator). 출처: 2026-11-13-safety-net-limit-source.md')
    names = [d[0] for d in DATA]
    g = []
    g.append(bars(60, 150, 700, 240, '처리 중 요청 최대(1초 표본)', list(zip(names, [d[1] for d in DATA])), '', lambda v: f'{v:.0f}'))
    g.append(bars(840, 150, 700, 240, 'Gateway 컨테이너 메모리 최대', list(zip(names, [d[2] for d in DATA])), 'MiB',
                  lambda v: f'{v:.0f}', ref=1024, ref_label='컨테이너 상한 1GiB'))
    g.append(bars(60, 520, 700, 240, '상한 거절(k6가 받은 GATEWAY_OVERLOADED)', list(zip(names, [d[4] for d in DATA])), '',
                  lambda v: f'{v / 1000:.0f}천' if v >= 1000 else f'{v:.0f}'))
    g.append(bars(840, 520, 700, 240, '확정 예매 수(15,000명 중)', list(zip(names, [d[5] for d in DATA])), '', lambda v: f'{v:,.0f}'))
    return head + ''.join(g) + tail


def chart_timeline(csv_dir):
    """회차별 처리 중 수(1초). 오픈 시각을 0초로 맞춘다"""
    runs = [('64', 'L64-1', PRIMARY, '3 0'), ('256', 'L256-1', '#2F2FAE', '8 4'), ('512 ①', 'L512-1', ORANGE, '3 0'), ('끔', 'OFF-1', GRAY, '3 0')]
    opens = {'L64-1': 1794562320, 'L256-1': 1794563160, 'L512-1': 1794567900, 'OFF-1': 1794563940}  # 각 회차 오픈 epoch(회차 생성 출력)
    head, tail = frame('오픈 뒤 Gateway 처리 중 요청 수(1초)',
                       '64·256은 오픈 직후부터 상한에 붙어 있고, 512·끔은 약 450~490에서 저절로 멈춘다',
                       '2026-11-13 배포 서버, O1 15,000명. 처리 중 = gateway_in_flight_requests(끔은 http_server_requests_active 합). 출처: 2026-11-13-safety-net-limit-source.md')
    x0, y0, w, h = 110, 140, 1400, 620
    tmin, tmax, vmax = -60, 360, 550
    X = lambda t: x0 + (t - tmin) / (tmax - tmin) * w
    Y = lambda v: y0 + h - v / vmax * h
    out = []
    for v in range(0, vmax + 1, 100):
        out.append(f'<line x1="{x0}" y1="{Y(v):.1f}" x2="{x0 + w}" y2="{Y(v):.1f}" stroke="{GRID}" stroke-width="1"/>')
        out.append(text(x0 - 12, Y(v) + 6, str(v), 16, SUB, 'end'))
    for t in range(0, tmax + 1, 60):
        out.append(text(X(t), y0 + h + 28, f'+{t}초' if t else '오픈', 16, SUB, 'middle'))
    for i, (label, run, color, dash) in enumerate(runs):
        path = os.path.join(csv_dir, run, f'kangmin-sn-{run}-gwmem.csv')
        if not os.path.exists(path):
            continue
        pts = []
        for r in csv.DictReader(open(path)):
            t = int(r['epoch_ms']) / 1000 - opens[run]
            v = r['inflight'] or r['active']
            if tmin <= t <= tmax and v:
                pts.append(f'{X(t):.1f},{Y(float(v)):.1f}')
        out.append(f'<polyline points="{" ".join(pts)}" fill="none" stroke="{color}" stroke-width="2" stroke-dasharray="{dash}"/>')
        out.append(f'<line x1="{1180}" y1="{130 + i * 28}" x2="{1220}" y2="{130 + i * 28}" stroke="{color}" stroke-width="3" stroke-dasharray="{dash}"/>')
        out.append(text(1230, 136 + i * 28, f'상한 {label}', 17, BODY))
    return head + ''.join(out) + tail


if __name__ == '__main__':
    open(os.path.join(HERE, '2026-11-13-safety-net-limit-summary.svg'), 'w', encoding='utf-8').write(chart_summary())
    if len(sys.argv) > 1:
        open(os.path.join(HERE, '2026-11-13-safety-net-limit-inflight.svg'), 'w', encoding='utf-8').write(chart_timeline(sys.argv[1]))
