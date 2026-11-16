# 전후 하이라이트 그림 3장(highlight-a·b·c)이 함께 쓰는 부품. 규칙: ../../design-kit.md, 톤은 kpi.py와 같다.
# PNG: msedge --headless=new --window-size=1600,900 --force-device-scale-factor=2 --screenshot=<png> <svg 파일 URL>
import base64
import io
from pathlib import Path

HERE = Path(__file__).parent
REPO = HERE.parents[4]
W, H, M = 1600, 900, 64
P, P2, O, G, GL, RED, GREY, INK = "#3B3BD4", "#24248F", "#F59F00", "#2B8A3E", "#63E6BE", "#E03131", "#C9CDD6", "#14161E"
BODY_DARK, BODY, WHITE, LINE = "#373B4A", "#676C7E", "#FFFFFF", "#E1E4EA"


class Canvas:
    def __init__(self, source_note, cards):
        """cards: 빛줄기를 오려 낼 카드 자리 (x, y, w, h) 목록. 빛은 카드 밖에서만 보인다."""
        self.o = []
        a = self.add
        a(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}" font-family="Pretendard, \'Noto Sans KR\', sans-serif">')
        a(f"<!-- {source_note} -->")
        a("<defs>")
        a(f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{P}"/><stop offset="1" stop-color="{P2}"/></linearGradient>')
        a('<linearGradient id="beam" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFFFFF" stop-opacity="0.2"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>')
        holes = "".join(f"M{x} {y}h{w}v{h}h{-w}z" for x, y, w, h in cards)
        a(f'<clipPath id="outside"><path clip-rule="evenodd" d="M0 0h{W}v{H}h{-W}z{holes}"/></clipPath>')
        a("</defs>")
        a(f'<rect width="{W}" height="{H}" fill="url(#bg)"/>')
        a('<g clip-path="url(#outside)">')
        for x0, x1, x2 in [(1010, 760, 1010), (1150, 1090, 1330), (1290, 1380, 1640), (860, 520, 740)]:
            a(f'<polygon points="{x0 - 6},0 {x0 + 6},0 {x2},{H} {x1},{H}" fill="url(#beam)"/>')
        a("</g>")

    def add(self, s):
        self.o.append(s)

    def note(self, s):
        self.add(f"<!-- {s} -->")

    def text(self, x, y, s, size, weight=600, fill=WHITE, op=None, anchor=None, ls=None):
        extra = (f' fill-opacity="{op}"' if op is not None else "") + (f' text-anchor="{anchor}"' if anchor else "") + (f' letter-spacing="{ls}"' if ls else "")
        self.add(f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" font-weight="{weight}" fill="{fill}"{extra}>{s}</text>')

    def run(self, x, y, parts, weight=900, ls=-2):
        """(글자, 크기, 색, 투명도, 앞 간격) 묶음을 한 줄로 쓴다."""
        out = []
        for s, size, fill, op, dx in parts:
            opa = f' fill-opacity="{op}"' if op is not None else ""
            d = f' dx="{dx}"' if dx else ""
            out.append(f'<tspan font-size="{size}" fill="{fill}"{opa}{d}>{s}</tspan>')
        self.add(f'<text x="{x:.1f}" y="{y:.1f}" font-weight="{weight}" letter-spacing="{ls}">{"".join(out)}</text>')

    def white_card(self, x, y, w, h):
        self.add(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="24" fill="{WHITE}"/>')

    def glass(self, x, y, w, h):
        self.add(f'<rect x="{x:.1f}" y="{y}" width="{w:.1f}" height="{h}" rx="24" fill="{WHITE}" fill-opacity="0.1" stroke="{WHITE}" stroke-opacity="0.22" stroke-width="1.5"/>')

    def chip(self, x, y, s, w):
        """이름표 칩: 흰 알약 + 주 색 글자."""
        self.add(f'<rect x="{x}" y="{y}" width="{w}" height="40" rx="20" fill="{WHITE}"/>')
        self.text(x + w / 2, y + 28, s, 21, 800, P, anchor="middle")

    def warn(self, x, y, s, size=20):
        """경고 세모(글리프 대신 직접 그린다) + 굵은 흰 글자. (x, y)는 기준선 왼쪽."""
        self.add(f'<path d="M{x + 10} {y - 17} L{x + 20.5} {y + 1.5} H{x - 0.5} Z" fill="{O}" stroke="{O}" stroke-width="2.4" stroke-linejoin="round"/>')
        self.add(f'<rect x="{x + 8.8}" y="{y - 10.5}" width="2.4" height="6.5" rx="1.2" fill="{P2}"/>')
        self.add(f'<circle cx="{x + 10}" cy="{y - 1.2}" r="1.45" fill="{P2}"/>')
        self.text(x + 29, y, s, size, 800)

    def conds(self, x, y, lines, size=20, gap=26, op=0.7):
        """조건 줄 묶음. {…}로 감싼 부분은 전·후 숫자라 굵은 흰 글자로 쓴다."""
        for n, s in enumerate(lines):
            s = s.replace("{", f'<tspan font-weight="800" fill="{WHITE}" fill-opacity="1">').replace("}", "</tspan>")
            self.text(x, y + n * gap, s, size, 600, WHITE, op)

    def bar(self, x, base, w, h, fill, value, label, vsize=32, vfill=INK, lfill=BODY_DARK):
        """전후 막대. 0에서 시작하고 막대 위에 값을 쓴다."""
        self.add(f'<rect x="{x:.1f}" y="{base - h:.1f}" width="{w}" height="{h:.1f}" rx="6" fill="{fill}"/>')
        self.text(x + w / 2, base - h - 12, value, vsize, 900, vfill, anchor="middle")
        self.text(x + w / 2, base + 26, label, 18, 800, lfill, anchor="middle")

    def evidence(self, x, y, w, h, cap1, cap2, img_rel, crop, iw, ih, foot=None):
        """근거 화면 작은 삽입. 원본 그림을 잘라 줄여 SVG 안에 넣는다(파일 하나로 열리게)."""
        from PIL import Image
        self.glass(x, y, w, h)
        self.add(f'<rect x="{x + 16}" y="{y + 14}" width="98" height="32" rx="16" fill="{WHITE}"/>')
        self.text(x + 65, y + 36, "근거 화면", 17, 800, P, anchor="middle")
        self.text(x + 126, y + 37, cap1, 18, 800)
        self.text(x + 18, y + 68, cap2, 16, 600, WHITE, 0.75)
        im = Image.open(REPO / img_rel).convert("RGB").crop(crop).resize((iw * 2, ih * 2), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "PNG", optimize=True)
        ix, iy = x + (w - iw) / 2, y + 82
        self.note(f"근거 화면 원본: {img_rel}")
        self.add(f'<clipPath id="ev"><rect x="{ix}" y="{iy}" width="{iw}" height="{ih}" rx="8"/></clipPath>')
        self.add(f'<image x="{ix}" y="{iy}" width="{iw}" height="{ih}" clip-path="url(#ev)" href="data:image/png;base64,{base64.b64encode(buf.getvalue()).decode()}"/>')
        if foot:
            self.text(x + 18, iy + ih + 26, foot, 16, 600, WHITE, 0.75)

    def save(self, name):
        self.add("</svg>")
        (HERE / name).write_text("\n".join(self.o), encoding="utf-8")
        print("ok", name)
