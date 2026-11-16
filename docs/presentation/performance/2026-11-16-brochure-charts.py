#!/usr/bin/env python3
# 최종 발표회 브로셔(11/16)에 넣는 성능 그림을 SVG로 만든다. 외부 라이브러리 없이 돈다.
# 숫자는 원문에서 옮겨 적었고, 그림마다 출처 줄에 문서·환경·날짜·반복 횟수를 적었다.
# 사용: python3 2026-11-16-brochure-charts.py → 같은 폴더에 SVG, PNG는 script/render-diagram.sh <svg>
import os

HERE = os.path.dirname(os.path.abspath(__file__))
BG, INK, BODY, SUB, GRID = '#FBFBFD', '#14161E', '#373B4A', '#676C7E', '#E1E4EA'
PRIMARY, PRIMARY_BG, ORANGE, GRAY, GREEN = '#3B3BD4', '#EEF0FF', '#D9480F', '#B4B8C5', '#1F8A5B'
FONT = "font-family=\"'Pretendard','Apple SD Gothic Neo',sans-serif\""


def text(x, y, s, size=20, color=BODY, anchor='start', weight=400):
    return f'<text x="{x}" y="{y}" {FONT} font-size="{size}" fill="{color}" text-anchor="{anchor}" font-weight="{weight}">{s}</text>'


def frame(title, subtitle, source, body, h=900):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1600 {h}" width="1600" height="{h}">\n'
            f'<rect width="1600" height="{h}" fill="{BG}"/>'
            + text(48, 64, title, 38, INK, weight=700)
            + text(48, 104, subtitle, 21, SUB)
            + body
            + text(48, h - 28, source, 15, SUB)
            + '\n</svg>\n')


def panel(x, y, w, h, label):
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="16" fill="#FFFFFF" stroke="#E1E4EA" stroke-width="1.5"/>'
            + text(x + 28, y + 44, label, 22, INK, weight=700))


def bars(x, y, w, h, items, vmax, unit='', fmt='{:,}', note_better=None, inside=False, bwmax=150):
    """세로 막대 묶음. items = [(라벨, 값, 색, 값 글자 대신 쓸 문구 또는 None)]"""
    out = []
    n = len(items)
    gap = 36
    bw = min(bwmax, (w - gap * (n + 1)) / n)
    total = n * bw + (n - 1) * gap
    x0 = x + (w - total) / 2
    base = y + h
    out.append(f'<line x1="{x + 16}" y1="{base}" x2="{x + w - 16}" y2="{base}" stroke="{GRID}" stroke-width="2"/>')
    for i, (lab, val, color, vtxt) in enumerate(items):
        bh = max(4, h * val / vmax)
        bx = x0 + i * (bw + gap)
        out.append(f'<rect x="{bx:.1f}" y="{base - bh:.1f}" width="{bw:.1f}" height="{bh:.1f}" rx="8" fill="{color}"/>')
        if inside:
            out.append(text(bx + bw / 2, base - bh + 44, vtxt or (fmt.format(val) + unit), 28, INK if color == GRAY else '#FFFFFF', 'middle', 700))
        else:
            out.append(text(bx + bw / 2, base - bh - 14, vtxt or (fmt.format(val) + unit), 26, INK, 'middle', 700))
        for k, part in enumerate(lab.split('\n')):
            out.append(text(bx + bw / 2, base + 34 + k * 24, part, 18, BODY, 'middle'))
    return ''.join(out)


def badge(x, y, s, color=GREEN, bg='#E8F6EF', size=22):
    w = 22 + len(s) * size * 0.62
    return (f'<rect x="{x}" y="{y}" width="{w:.0f}" height="{size + 20}" rx="{(size + 20) / 2}" fill="{bg}"/>'
            + text(x + w / 2, y + size + 4, s, size, color, 'middle', 700))


def save(name, svg):
    path = os.path.join(HERE, name)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(svg)
    print(path)


# 1. Gateway 동시 처리 상한 64 (같은 배포, 11/11, 각 1회) + 거절 위치·요청 로그 (11/11~12, 각 1회)
def gateway():
    body = panel(48, 140, 740, 640, '초당 2,000건 · 상한 없음 대 상한 64')
    body += text(76, 214, '같은 배포에서 대조군(상한 500)과 비교', 17, SUB)
    body += text(150, 262, '굿풋(요청/초)', 18, SUB, 'middle')
    body += bars(60, 300, 330, 330, [('상한 500\n(사실상 없음)', 1304, GRAY, None), ('상한 64', 1696, PRIMARY, None)], 2400)
    body += text(560, 262, 'p95 응답(ms)', 18, SUB, 'middle')
    body += bars(400, 300, 330, 330, [('상한 500', 571, GRAY, '571ms'), ('상한 64', 87, PRIMARY, '87ms')], 700)
    body += badge(110, 728, '굿풋 +30%') + badge(470, 728, 'p95 −85%')
    body += panel(812, 140, 740, 640, '초당 2,500건 · 거절을 더 싸게')
    body += text(840, 214, '거절 위치를 앞으로(H1) → 요청 로그 끔(H2)', 17, SUB)
    body += text(1182, 262, '굿풋(요청/초)', 18, SUB, 'middle')
    body += bars(830, 300, 704, 330, [('기준', 1608, GRAY, None), ('H1 거절 위치\nHttpHandler 앞', 1814, '#8C8CE8', None), ('H2 요청 로그\n끔(실험)', 2122, PRIMARY, None)], 2600)
    body += badge(1050, 728, '1,608 → 2,122 /s')
    src = '출처: docs/presentation/performance/2026-11-14-gateway-overload-one-page.md · 배포 EC2 m7i.xlarge(4 vCPU) 1대 + 같은 VPC k6 발생기 · 11/11~12 · 각 1회(같은 값 반복 차 약 5%) · 굿풋 = 200ms 안에 끝난 200 응답/초'
    save('2026-11-16-brochure-gateway.svg', frame('Gateway: 줄을 세울 자리를 맨 앞으로', '동시 처리 상한 64를 리틀의 법칙으로 계산해 넣고, 넘치는 요청은 가장 싼 자리에서 503 + 다시 시도할 시간으로 돌려보냈다', src, body))


# 2. 좌석 선점 개선 묶음 v1 → v2 (배포 현실 모드, 11/16, 각 2회)
def seathold():
    body = panel(48, 140, 980, 640, '선점 p95 응답(ms) · 같은 조건 2회씩')
    body += bars(70, 250, 940, 420, [('v1 1회', 242.7, GRAY, '242.7'), ('v1 2회', 196.0, GRAY, '196.0'), ('v2 1회', 44.5, PRIMARY, '44.5'), ('v2 2회', 31.4, PRIMARY, '31.4')], 280)
    body += badge(420, 728, '−77 ~ 87%')
    body += panel(1052, 140, 500, 300, '확정 예매')
    body += text(1302, 290, '+10.7%', 64, PRIMARY, 'middle', 800)
    body += text(1302, 336, '2회 범위 +7.0 ~ 14.5%', 19, SUB, 'middle')
    body += text(1302, 400, '같은 3,000명, 같은 좌석', 17, SUB, 'middle')
    body += panel(1052, 480, 500, 300, 'DB 커넥션 대기 · 예매 CPU')
    body += text(1302, 610, '12·14 → 0', 52, PRIMARY, 'middle', 800)
    body += text(1302, 660, 'Hikari 대기 최대(각 회차)', 19, SUB, 'middle')
    body += text(1302, 720, 'reservation CPU −33 ~ 49%', 21, BODY, 'middle', 700)
    src = '출처: docs/presentation/performance/2026-11-15-bottleneck-improvement-study.md 7-3절 · 배포 EC2 1대, 사용자 3,000명 현실 모드(O1) · 11/16 · 버전별 2회 · 묶음 = 사전 거절, 직렬화 재사용, 예열, Kafka 헬스체크, 요청 로그 축소'
    save('2026-11-16-brochure-seathold.svg', frame('좌석 선점: 병목을 하나씩 옮기며 잡았다', '커넥션 → DB → 직렬화 → CPU → 입구로 옮겨 가는 병목을 단계마다 측정하고, 묶음을 같은 조건 2회로 비교했다', src, body))


# 3. 추첨 자동결제 (배포, 11/10 3회 → 11/13 2회, 날이 달라 참고값)
def lottery():
    body = panel(48, 140, 740, 640, '추첨 뒤 10분 안 자동결제 완료율')
    body += bars(60, 250, 720, 420, [('개선 전\n(3회, 11/10)', 93.0, GRAY, '92.4~93.4%'), ('개선 후\n(2회, 11/13)', 100, PRIMARY, '100%')], 110, inside=True, bwmax=230)
    body += f'<line x1="80" y1="{250 + 420 - 420 * 95 / 110:.0f}" x2="760" y2="{250 + 420 - 420 * 95 / 110:.0f}" stroke="{ORANGE}" stroke-width="2.5" stroke-dasharray="8 6"/>'
    body += text(84, 250 + 420 - 420 * 95 / 110 - 12, '합격선 95%', 18, ORANGE, 'start', 700)
    body += panel(812, 140, 740, 300, '처리 속도와 마지막 결제 시점')
    body += text(980, 260, '3.2 → 9.4', 54, PRIMARY, 'middle', 800)
    body += text(980, 300, '자동결제 건/초', 18, SUB, 'middle')
    body += text(1360, 260, '610 → 205초', 50, PRIMARY, 'middle', 800)
    body += text(1360, 300, '결제 완료 p95(추첨 뒤)', 18, SUB, 'middle')
    body += text(1182, 386, '원인: 결제 명령 소비자 1개의 직렬 대기(PG 지연 스윕으로 확정)', 18, BODY, 'middle')
    body += panel(812, 480, 740, 300, '겹친 일반 결제의 503')
    body += text(1182, 620, '9.5% → 0', 64, PRIMARY, 'middle', 800)
    body += text(1182, 670, '소비자 3 + 자동결제 전용 PG 허가 3으로 분리', 19, SUB, 'middle')
    src = '출처: docs/design/lottery/numbers-and-measurement.md 3-3·3-5·3-6절, ADR 0051 · 배포 EC2 1대, 응모 30,000명·추첨석 3,000·당첨 약 2,000건, Mock PG 300ms · 개선 전 11/10 3회, 후 11/13 2회(날이 달라 참고값)'
    save('2026-11-16-brochure-lottery.svg', frame('추첨 자동결제: 10분 안 95%를 100%로', '빠른 손이 아니어도 당첨되면 등록한 카드로 결제된다. 그 결제가 늦지 않게 원인을 측정으로 좁혀 고쳤다', src, body))


# 4. Synthetic Data 대 균일 부하 (배포, 11/15, 각 1회)
def synthetic():
    def pair(x, label, a, b, at, bt, vmax):
        s = panel(x, 140, 360, 640, label)
        s += bars(x + 10, 260, 340, 400, [('균일', a, GRAY, at), ('Synthetic', b, PRIMARY, bt)], vmax)
        return s
    body = pair(48, '확정 예매', 1973, 2547, '1,973', '2,547', 3000)
    body += pair(424, '선점 409 비율', 48.1, 11.7, '48.1%', '11.7%', 60)
    body += pair(800, '선점 p95(ms)', 192.4, 36.9, '192.4', '36.9', 240)
    body += pair(1176, '대기열 진입 최대(/s)', 353, 2792, '353', '2,792', 3300)
    src = '출처: docs/presentation/performance/2026-11-15-o1-synthetic-vs-uniform-source.md, docs/design/infra/synthetic-data.md 6절 · 배포 EC2 1대, 사용자 3,000명 · 11/15 · 각 1회 · 두 실행 모두 중복 0, 확정 = 승인 = SOLD'
    save('2026-11-16-brochure-synthetic.svg', frame('Synthetic Data: 같은 3,000명도 몰리는 모양이 다르다', 'LLM이 만든 페르소나 8개로 도착 시각·좌석 취향을 흉내 내자, 균일 부하가 숨긴 대기열 입구의 순간 몰림이 드러났다', src, body))


# 5. 결제 허가 수와 자동 배정 인덱스 (배포)
def payment_autoassign():
    body = panel(48, 140, 740, 640, 'PG 동시 허가 10 → 30 · 승인 한도(건/초)')
    body += bars(60, 250, 720, 420, [('허가 10', 31.2, GRAY, '31.2'), ('허가 30', 91.5, PRIMARY, '91.5'), ('계산값\n30 ÷ 0.3초', 94, '#C9CBF5', '≈ 94')], 110)
    body += panel(812, 140, 740, 640, '자동 배정 확정 수 · 인덱스 3개')
    body += bars(830, 250, 704, 420, [('인덱스 전', 433, GRAY, '433'), ('인덱스 후', 2248, PRIMARY, '2,248')], 2600)
    body += text(1182, 744, 'DB CPU 280% → 18.2%', 22, BODY, 'middle', 700)
    src = '출처: docs/design/infra/open-peak-scenarios.md PAY-1(11/12, 단계별 1회, Mock PG 300ms) · docs/presentation/performance/2026-11-15-bottleneck-improvement-study.md 7-4절, TS-72(11/16, 각 1회) · 배포 EC2 1대'
    save('2026-11-16-brochure-payment-autoassign.svg', frame('계산으로 예측하고, 측정으로 확인했다', '결제 처리량은 허가 수 ÷ PG 지연이 정한다는 계산이 맞았고, 자동 배정은 실행 계획을 읽어 인덱스로 고쳤다', src, body))


if __name__ == '__main__':
    gateway(); seathold(); lottery(); synthetic(); payment_autoassign()


# 6. AI 판단 기록(11/16 dev 486501b7 기준). 개별 기록은 머리말 verdict, 일지는 줄 수
def ai_judgments():
    people = [  # 이름, 수용, 부분, 거부, 보류, 일지 줄
        ('강민', 41, 15, 59, 3, 260), ('준현', 32, 12, 21, 1, 132), ('효승', 2, 0, 5, 0, 100),
        ('권우', 20, 9, 5, 1, 65), ('영은', 7, 4, 4, 0, 8)]
    colors = [PRIMARY, '#8C8CE8', ORANGE, GRAY]
    body = panel(48, 140, 1000, 660, '사람별 판단 기록 · 개별 기록(판정별) + 일지')
    x0, y0, scale = 170, 230, 2.0
    for i, (name, a, p, r, h, d) in enumerate(people):
        y = y0 + i * 96
        body += text(150, y + 34, name, 22, INK, 'end', 700)
        x = x0
        for val, col in zip((a, p, r, h), colors):
            w = val * scale
            if w:
                body += f'<rect x="{x:.1f}" y="{y + 8}" width="{w:.1f}" height="38" fill="{col}"/>'
            x += w
        dw = d * scale * 0.5
        body += f'<rect x="{x + 6:.1f}" y="{y + 8}" width="{dw:.1f}" height="38" fill="#E4E6F0"/>'
        total = a + p + r + h
        body += text(x + 16 + dw, y + 36, f'개별 {total} · 일지 {d}', 18, BODY, 'start', 700)
    lx = 190
    for lab, col in zip(('수용', '부분 수용', '거부', '보류', '일지(폭 ½)'), colors + ['#E4E6F0']):
        body += f'<rect x="{lx}" y="736" width="22" height="22" rx="4" fill="{col}"/>' + text(lx + 30, 754, lab, 18, BODY)
        lx += 150
    body += panel(1072, 140, 480, 660, '11/16 기준 기록')
    rows = [('806', 'AI 판단 기록(개별 241 + 일지 565)'), ('94', '개별 기록 중 거부(39%)'), ('351', '질문 학습 노트'),
            ('63', '결정 기록(ADR)'), ('74', '트러블슈팅'), ('384', '머지된 PR')]
    for i, (v, lab) in enumerate(rows):
        y = 250 + i * 90
        body += text(1100, y + 20, v, 46, PRIMARY if i < 2 else INK, 'start', 800)
        body += text(1250, y + 12, lab, 19, BODY)
    src = '출처: docs/ai-judgments/(머리말 verdict, 일지 "- [" 줄), docs/learning/*/qna, docs/decisions, docs/troubleshooting.md, gh pr list · 기준 2026-11-16 dev 486501b7 · 판정은 records-check.sh가 허용하는 네 값'
    save('2026-11-16-brochure-ai-judgments.svg', frame('AI가 쓰고, 사람이 판단하고, 기록이 남는다', '모든 AI 제안에 대한 수용·거부를 판단한 순간 저장소에 남겼다. 기록을 챙기는 일은 사람의 기억이 아니라 훅과 CI가 했다', src, body))


if __name__ == '__main__':
    ai_judgments()
