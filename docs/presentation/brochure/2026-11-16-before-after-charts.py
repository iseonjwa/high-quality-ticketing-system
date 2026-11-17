# 브로셔 트러블슈팅 A·C 전후 막대 그림을 SVG로 만든다.
# 값은 원문 표에서 옮긴 것이고, 이 스크립트는 계산하지 않는다(변화율 문구도 원문·검수 계산값).
# 실행: python 2026-11-16-before-after-charts.py  (같은 폴더에 SVG 2개를 쓴다)
# PNG는 Edge 헤드리스로 만든다: msedge --headless=new --window-size=1600,900 --force-device-scale-factor=2 --screenshot=<png> <svg 파일 URL>

BG, BORDER, BEFORE, AFTER = "#FBFBFD", "#D7DAE2", "#C9CDD6", "#3B3BD4"
TITLE, BODY, MUTED, ACCENT, AXIS = "#14161E", "#373B4A", "#676C7E", "#2F2FAE", "#8D92A3"


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def chart(path, title, subtitle, panels, footnote, source, shared_max=None):
    """shared_max를 주면 모든 칸의 막대를 같은 기준으로 그린다(같은 단위일 때)."""
    W, H = 1600, 900
    n = len(panels)
    gap = 20
    pw = (W - 80 - gap * (n - 1)) / n
    top, ph = 150, 640
    o = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" font-family="Pretendard, \'Noto Sans KR\', sans-serif">',
         f'<rect width="{W}" height="{H}" fill="{BG}"/>',
         f'<text x="40" y="60" font-size="34" font-weight="700" fill="{TITLE}">{esc(title)}</text>',
         f'<text x="40" y="98" font-size="20" fill="{BODY}">{esc(subtitle)}</text>',
         f'<rect x="1300" y="40" width="22" height="22" rx="4" fill="{BEFORE}"/><text x="1332" y="58" font-size="18" fill="{BODY}">개선 전</text>',
         f'<rect x="1440" y="40" width="22" height="22" rx="4" fill="{AFTER}"/><text x="1472" y="58" font-size="18" fill="{BODY}">개선 후</text>']
    for i, p in enumerate(panels):
        px = 40 + i * (pw + gap)
        o.append(f'<rect x="{px}" y="{top}" width="{pw}" height="{ph}" rx="12" fill="#FFFFFF" stroke="{BORDER}" stroke-width="1.5"/>')
        o.append(f'<text x="{px + 24}" y="{top + 44}" font-size="24" font-weight="700" fill="{TITLE}">{esc(p["name"])}</text>')
        o.append(f'<text x="{px + 24}" y="{top + 74}" font-size="16" fill="{MUTED}">{esc(p["cond"])}</text>')
        o.append(f'<text x="{px + 24}" y="{top + 108}" font-size="22" font-weight="700" fill="{ACCENT}">{esc(p["delta"])}</text>')
        base = top + ph - 80
        barmax = ph - 300
        vmax = (shared_max or max(p["before"], p["after"])) * 1.1 or 1
        bw = 120
        cx = px + pw / 2
        for j, (v, label, col, lab) in enumerate([(p["before"], p["before_label"], BEFORE, p["before_tag"]),
                                                  (p["after"], p["after_label"], AFTER, p["after_tag"])]):
            bx = cx - bw - 20 if j == 0 else cx + 20
            h = barmax * v / vmax
            if h < 3 and v == 0:
                h = 3  # 0 값도 자리가 보이게 얇은 선으로 둔다
            o.append(f'<rect x="{bx}" y="{base - h}" width="{bw}" height="{h}" rx="6" fill="{col}"/>')
            o.append(f'<text x="{bx + bw / 2}" y="{base - h - 14}" font-size="24" font-weight="700" text-anchor="middle" fill="{TITLE}">{esc(label)}</text>')
            o.append(f'<text x="{bx + bw / 2}" y="{base + 28}" font-size="16" text-anchor="middle" fill="{BODY}">{esc(lab)}</text>')
        o.append(f'<line x1="{px + 30}" y1="{base}" x2="{px + pw - 30}" y2="{base}" stroke="{AXIS}" stroke-width="1.5"/>')
        for k, line in enumerate(p.get("notes", [])):
            o.append(f'<text x="{px + 24}" y="{base + 56 + k * 20}" font-size="14" fill="{MUTED}">{esc(line)}</text>')
    o.append(f'<text x="40" y="836" font-size="16" fill="{MUTED}">{esc(footnote)}</text>')
    o.append(f'<text x="40" y="866" font-size="15" fill="{MUTED}">{esc(source)}</text>')
    o.append("</svg>")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(o))


# 트러블슈팅 A: docs/presentation/performance/2026-11-14-gateway-overload-one-page.md "전·후 표", docs/troubleshooting.md TS-59
chart(
    "2026-11-16-gateway-before-after.svg",
    "Gateway 과부하 개선 3단계",
    "대기열 순번 조회 API에 몰린 요청을 맨 앞에서 거절하고 요청 로그를 줄여, 같은 장비에서 굿풋을 올렸다(① 초당 2,000건 1,304 → 1,696/s, ②③ 초당 2,500건 1,608 → 2,122/s)",
    [
        dict(name="① 동시 처리 상한 64", cond="초당 2,000건 · 6차, 같은 배포에서 상한만 바꿈",
             delta="굿풋 +30% · p95 571 → 87ms",
             before=1304, after=1696, before_label="1,304/s", after_label="1,696/s",
             before_tag="상한 500(사실상 없음)", after_tag="상한 64",
             notes=["넘친 요청은 503(상한 거절 8,715건)", "서버 CPU는 98%로 그대로"]),
        dict(name="② 거절 위치 앞당김", cond="초당 2,500건 · 6차 → 7차",
             delta="굿풋 +13% · p95 104 → 78ms",
             before=1608, after=1814, before_label="1,608/s", after_label="1,814/s",
             before_tag="필터 안에서 거절", after_tag="맨 앞(HttpHandler)",
             notes=["거절 한 건 비용 = 받은 요청의 2~8%", "(11/12 별도 측정)"]),
        dict(name="③ 요청 로그 줄이기", cond="요청 로그 켬 → 끔 · 굿풋은 2,500/s, CPU는 1,000/s",
             delta="굿풋 +17% · 서버 CPU 78 → 66%",
             before=1814, after=2122, before_label="1,814/s", after_label="2,122/s",
             before_tag="로그 켬", after_tag="로그 끔",
             notes=["숫자는 로그 끔 측정. 운영값(문제 요청만 남김)은 11/12 측정에서", "1,500/s 서버 CPU가 끔과 같음(1,000/s는 +2.9%p)"]),
    ],
    "막대 = 굿풋(200 응답이면서 200ms 안에 끝난 요청/초), 세 칸 같은 기준. 대기자 10,000명, 회차마다 1회 측정, 같은 값 반복 차이 약 5%",
    "비교 2026-11-11 UTC(운영 반영 확인 11-12) · 배포 EC2 m7i.xlarge 1대, 대기열 순번 조회 API · 근거: docs/presentation/performance/2026-11-14-gateway-overload-one-page.md",
    shared_max=2122,
)

# 트러블슈팅 C: docs/design/lottery/numbers-and-measurement.md 3-3절(11/10 전), 3-5절(11/13 후), 3-6절(11/15 후), ADR 0051
chart(
    "2026-11-16-autopay-before-after.svg",
    "추첨 자동결제 개선 전후",
    "결제 명령 소비자를 3개로, 자동결제 PG 허가를 따로 두고 일반 결제 허가를 10 → 30으로 늘리자 약 3분 50초에 전원 결제가 끝나고 겹친 일반 결제 503이 사라졌다",
    [
        # 전: 3회차 진행 조회 01:44:42 = 추첨 01:40:51 + 231초, 707/2,019건. 후: 2회 모두 마지막 결제가 206~229초 버킷 안
        dict(name="추첨 뒤 약 3분 50초 시점 완료율", cond="당첨 약 2,000건 · 자동결제만",
             delta="35% → 100% · 전원 결제 끝",
             before=35.0, after=100, before_label="35%", after_label="100%",
             before_tag="11/10 (707/2,019건)", after_tag="11/13 2회",
             notes=["전: 3회차 진행 조회(+231초) 1회 값", "10분 안 완료(판정 기준)는 93 → 100%"]),
        # 전 p95 612·616·610초 평균 612.7, 후 210·205초 평균 207.5(모두 버킷 선형 보간 계산값)
        dict(name="결제 완료 p95", cond="당첨 약 2,000건 · 자동결제만",
             delta="3.0배 빠름",
             before=613, after=208, before_label="약 613초", after_label="약 208초",
             before_tag="11/10 3회 평균", after_tag="11/13 2회 평균",
             notes=["버킷 선형 보간 계산값", "처리 속도 초당 약 3.2 → 9.4건(약 2.9배)"]),
        dict(name="겹친 일반 결제 503", cond="자동결제 중 일반 결제 초당 30 · 허가 10 → 30 함께 변경",
             delta="1,424건 → 0건",
             before=1424, after=0, before_label="1,424건", after_label="0건",
             before_tag="11/10 1회", after_tag="11/15 1회",
             notes=["각 일반 결제 15,000건 중(9.5% → 0%)", "허가 분리와 허가 증설의 몫은 따로 재지 않음"]),
    ],
    "응모 30,000명, 추첨석 3,000석, Mock PG 승인 지연 300ms. 개선(ADR 0051): 결제 명령 파티션·소비자 3, 전용 허가 pg-billing 3, 일반 결제 허가 pg-approve 10 → 30(잠정)",
    "배포 EC2 m7i.xlarge 1대 · 근거: docs/design/lottery/numbers-and-measurement.md 3-3·3-5·3-6절, docs/decisions/0051",
)
print("ok")
