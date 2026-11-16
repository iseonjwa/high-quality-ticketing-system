# Notion 프로젝트 소개 커버 만든 방법

teamsparta 프로젝트 소개 Notion 페이지(`[Spring심화_10기] 최종 프로젝트 브로셔`의 `[5조] 이선좌`)의 커버다(ISE-297). 이 데이터베이스의 갤러리 보기는 카드 미리보기로 페이지 커버를 쓰므로, 와이드판 한 장이 갤러리 카드와 페이지 상단 커버를 함께 맡는다. 2026-11-16에 페이지 커버로 올렸다(그 전 커버는 "빠른 손 아니어도, 추첨으로 내 자리" 그림이었다).

| 파일 | 크기 | 쓰는 곳 |
|---|---|---|
| `2026-11-16-notion-cover-wide.png` | 1500×600 | Notion 페이지 커버(갤러리 카드와 상단 커버) |
| `2026-11-16-notion-cover.png` | 1600×1000 | 16:10 판. Notion에는 쓰지 않았고 슬라이드처럼 잘리지 않는 곳에 쓴다 |
| `2026-11-16-notion-cover-art.png` | 1536×1024 | 글자 없는 그림 원본(codex 생성) |

## 정한 것

- 한 줄 문구는 "몰려도 정확한 티켓팅"이다. 폭주 트래픽과 중복 판매 없음 두 축을 한 줄로 줄였다(SA 1장 소개 문장 기준).
- 장면은 관객이 꽉 찬 파란 좌석 사이에서 조명을 받은 분홍 좌석 하나에 하린이 앉아 있는 그림이다. 같은 좌석을 노리는 사람이 많아도 내 자리는 확실하다는 뜻이다. 함께 뽑은 다른 두 시안(좌석 격자 위 점프, 좌석 파도)보다 뜻이 바로 읽혀서 골랐다.
- 캐릭터는 소개 영상 콘티 v2의 하린(분홍 후드, 단발)이다. 발권 부적 펫 대신 하린을 쓰기로 했다.
- 그림은 codex가 그리고 글자는 HTML로 얹었다. 이미지 모델은 한글을 자주 틀리게 그리기 때문이다. 문구를 바꿀 때는 HTML만 고치면 된다.

## 잘림 확인

같은 커버를 두 곳이 다르게 자른다.

- 갤러리 카드: 약 1.85:1로 가로 가운데만 남는다(1500 폭 기준 x 약 195~1305).
- 페이지 상단 커버: 화면 폭에 따라 약 5:1~7:1로 세로 가운데만 남고, 페이지 아이콘이 왼쪽 3분의 1 지점 아래쪽을 가린다.

첫 와이드판은 글자를 왼쪽 끝에 크게 두어 카드에서 "선좌 / 도 정확한 티켓팅"만 보였고, 상단 커버에서는 아이콘이 "티켓팅"을 가렸다. 그래서 글자를 x 250~700, y 200~350 안으로 옮기고 줄였고, 하린 얼굴은 x 940~1250, y 190~330 안에 두었다. 올린 뒤 실제 Notion에서 갤러리 카드와 상단 커버(창 폭 1568)를 찍어 글자와 얼굴이 잘리거나 가려지지 않는 것을 확인했다. 다른 창 폭에서 잘리면 Notion의 커버 위치 변경으로 맞춘다.

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
