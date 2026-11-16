#!/usr/bin/env python3
# 브로셔 갤러리 카드 썸네일(검정 바탕, 흰 글씨 키워드)을 SVG로 만든다. PNG는 script/render-diagram.sh로 내보낸다.
# 사용: python3 make-thumbnails.py → 같은 폴더에 card-NN.svg
import os
HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 1600, 900
CARDS = [
    '게이트웨이 처리량|30% 증가',
    'seq 배정을 통한 순서 보장과|높은 처리율',
    '좌석 선점 실패|20분의 1로 감소',
    '결제 장애 중에도|PG·DB 일치',
    '추첨 자동결제|처리 속도 3배',
    '미달·미해결 목표와|원인',
    '게이트웨이|동시 처리 상한 64',
    '입장 토큰 1회 사용으로|반복 점유 방지',
    '코레오그래피 SAGA와|UNKNOWN 재조회',
    '자동결제 소비자 증설과|PG 허가 분리',
    'DB 조건부 갱신과|잠금 대기 50ms',
    '서비스 개수|선정 과정',
    '서비스 화면|미리보기',
    'AI 예매 상담(RAG)과|운영자 화면',
    'AI 제안|거부율 32%',
    'AI 오류|정정 사례',
    'LLM 가상 사용자|3,000명',
    'CI/CD 자동 배포와|롤백',
    '팀원별 IP·SSH 키 접근 통제와|비밀값 보호',
]
def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
for i, text in enumerate(CARDS, 1):
    lines = text.split('|')
    size, gap = 96, 124
    y0 = H / 2 - (len(lines) - 1) * gap / 2 + size * 0.35
    body = ''.join(
        f'<text x="{W/2}" y="{y0 + k*gap:.0f}" font-family="Pretendard, \'Apple SD Gothic Neo\', sans-serif" font-size="{size}" font-weight="700" fill="#FFFFFF" text-anchor="middle" letter-spacing="-2">{esc(l)}</text>'
        for k, l in enumerate(lines))
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}"><rect width="{W}" height="{H}" fill="#000000"/>{body}</svg>\n'
    open(os.path.join(HERE, f'card-{i:02d}.svg'), 'w').write(svg)
print(len(CARDS))
