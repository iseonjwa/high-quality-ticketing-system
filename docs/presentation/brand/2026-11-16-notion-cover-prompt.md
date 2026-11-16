# Notion 프로젝트 소개 커버 만든 방법

teamsparta 프로젝트 소개 Notion 페이지의 갤러리 썸네일과 상단 커버다(ISE-297). 같은 그림을 두 비율로 내보냈다.

| 파일 | 크기 | 쓰는 곳 |
|---|---|---|
| `2026-11-16-notion-cover.png` | 1600×1000 | 갤러리 카드 썸네일 |
| `2026-11-16-notion-cover-wide.png` | 1500×600 | 페이지 상단 커버 |
| `2026-11-16-notion-cover-art.png` | 1536×1024 | 글자 없는 그림 원본(codex 생성) |

## 정한 것

- 한 줄 문구는 "몰려도 정확한 티켓팅"이다. 폭주 트래픽과 중복 판매 없음 두 축을 한 줄로 줄였다(SA 1장 소개 문장 기준).
- 장면은 관객이 꽉 찬 파란 좌석 사이에서 조명을 받은 분홍 좌석 하나에 하린이 앉아 있는 그림이다. 같은 좌석을 노리는 사람이 많아도 내 자리는 확실하다는 뜻이다. 함께 뽑은 다른 두 시안(좌석 격자 위 점프, 좌석 파도)보다 뜻이 바로 읽혀서 골랐다.
- 캐릭터는 소개 영상 콘티 v2의 하린(분홍 후드, 단발)이다. 발권 부적 펫 대신 하린을 쓰기로 했다.
- 그림은 codex가 그리고 글자는 HTML로 얹었다. 이미지 모델은 한글을 자주 틀리게 그리기 때문이다. 문구를 바꿀 때는 HTML만 고치면 된다.

## 잘림 확인

갤러리 카드는 그림을 카드 비율로, 상단 커버는 넓은 띠로 자른다. 그래서 글자와 하린 얼굴을 세로 가운데에 모았다. 와이드판은 4:1로 잘라도 다 보이고, 6:1(넓은 모니터)에서는 하린 머리 끝만 조금 잘린다. 더 잘리면 Notion의 커버 위치 변경으로 맞춘다.

## 다시 만드는 방법

1. 그림: codex CLI(0.167.0)의 내장 이미지 생성에 하린 인물 시트 3장(턴어라운드, 표정, 점프)을 참조로 붙여 아래 프롬프트로 만든다. 인물 시트는 소개 영상 콘티 v2 문서에 있다.
2. 글자: `2026-11-16-notion-cover.html`과 `-wide.html`을 헤드리스 Chrome으로 찍는다. 글꼴은 설치된 Pretendard를 먼저 쓰고 없으면 CDN에서 받는다.

```sh
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
"$CHROME" --headless=new --hide-scrollbars --allow-file-access-from-files --force-device-scale-factor=1 \
  --window-size=1600,1000 --virtual-time-budget=3000 \
  --screenshot=2026-11-16-notion-cover.png "file://$PWD/2026-11-16-notion-cover.html"
"$CHROME" --headless=new --hide-scrollbars --allow-file-access-from-files --force-device-scale-factor=1 \
  --window-size=1500,600 --virtual-time-budget=3000 \
  --screenshot=2026-11-16-notion-cover-wide.png "file://$PWD/2026-11-16-notion-cover-wide.html"
```

## 그림 프롬프트 (codex가 쓴 최종본)

```text
Use case: illustration-story. Create a finished Notion project-page gallery cover, PNG exactly 1536x1024 landscape. Use ALL THREE supplied reference images as identity and style references for HARIN: identical face, shoulder-length dark-brown bob with bangs, hot-pink #FF2D78 oversized hoodie, light-blue jeans, white sneakers. Only one Harin. Message conveyed WITHOUT TEXT: a huge crowd competes for concert seats, but one seat is calmly and certainly hers. Small-thumbnail legibility: big simple shapes, one clear focal point Harin and her pink seat at x=55%..85%. LEFT 45% must stay dark, calm, low-detail background continuation reserved for a future title. Important content inside y=15%..85%. Clean modern 2D vector-like motion-graphics illustration matching reference, smooth #14101A outlines, flat cel shading at most one shadow tone per color, crisp antialiased edges. Perfectly flat color fields like SVG. Dominant cobalt #2B3BE5 and deeper navy; hot pink ONLY for Harin and her single seat, small sunflower #FFD23F accents, warm paper #FFF8EE. Crowd is faceless simple navy silhouettes. NO text, letters, numbers, logos, watermarks, UI, real people, brands, idol groups or real venues. Phone screen if visible is plain flat light rectangle. NO grain, noise, paper texture, halftone, crosshatching, sketchy strokes, jagged edges, watercolor, speckles, gradients or banding. Concert hall seen from front and slightly above, many rows of identical cobalt seats all occupied by simple faceless navy fan silhouettes, suggesting enormous demand. On right half exactly ONE hot-pink seat, Harin sits in it smiling with relief, holding her phone up to her chest. A soft sunflower-yellow spotlight falls only on her seat, represented by a simple flat pale-yellow light shape, no gradient. Left rows are darker, quieter, simplified, low contrast. Make Harin clearly seated in the concert row, her pink seat visible around her, crowd surrounds her but she is calm and certain.
```
