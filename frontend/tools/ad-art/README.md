# 샘플 광고 그림 (ISE-294)

홈의 샘플 광고 두 개(밤결 스파클링 띠, 라이드온 셔틀 카드)에 쓰는 그림이다. 2026-11-16 codex CLI(0.167, 기본 이미지 생성 도구)로 만들었다. 브랜드는 가상이고, 사람 얼굴·실제 로고·한글 문구는 넣지 않았다. 광고 문구는 화면 글자로 얹는다(`ConcertListPage.tsx`의 `SampleAdBand`, `SampleAdCard`).

| 파일 | 원본 | 줄이는 방법 | 크기 |
|---|---|---|---|
| `src/assets/ads/ad-bamgyeol.webp` | `bamgyeol-a.png` 1536x1024 | 캔 주변을 잘라 896×460: `cwebp -q 76 -crop 640 300 896 460 bamgyeol-a.png -o ad-bamgyeol.webp` | 17KB |
| `src/assets/ads/ad-rideon.webp` | `rideon-b.png` 1024x1536 | 640×960으로 줄임: `cwebp -q 72 -resize 640 960 rideon-b.png -o ad-rideon.webp` | 39KB |

원본 PNG(장당 1.5~2.2MB)와 고르지 않은 시안(b·a)은 저장소에 두지 않는다. 다시 만들 때는 아래 프롬프트를 codex에 주고 같은 방법으로 줄인다. 이미지 생성은 같은 프롬프트라도 매번 다른 그림을 내므로, 띠는 캔이 가운데 높이(잘라 쓰는 띠 안)에 오는지, 카드는 아래 40%가 어두운지 보고 고른다.

## 밤결 스파클링 띠 (`bamgyeol-a.png`, 1536x1024)

```text
Use case: ads-marketing. Generate one photorealistic high-end commercial advertising photograph, PNG, exactly 1536x1024 landscape. Fictional sparkling drink BAMGYEOL for a student concert-ticketing demo. Very dark navy #0f1d26 studio backdrop with sparse soft teal and violet concert-stage bokeh far behind, restricted to the right. A slim 355ml mint-teal #7ee0d4 aluminum can with a deep navy band stands upright on a dark wet surface in the right 40% of frame, small in the overall composition. Precisely keep the entire can including top and bottom within x=1010..1290 and y=365..650, so all product survives a crop of the middle third of image height (y=341..682). Small restrained splash, fine sparkling droplets, and a few ice cubes remain within that same middle-third strip near the can. Cold condensation, realistic brushed aluminum and specular highlights, crisp premium studio product photography with believable physics. The left 55% of the entire frame is calm dark nearly uniform low-detail negative space for website headline overlay. Top and bottom areas are dark atmospheric space. Only text anywhere is "BAMGYEOL", small clean sans-serif capitals printed on the can, spelled B A M G Y E O L. No other lettering, slogans, Korean text, numbers, watermark, UI, real brands, logos, trademarks, recognizable products, or people. No illustration or cartoon or CGI render. Output just the photograph. CRITICAL framing override: the upright can must be very small, only 240 pixels tall, centered vertically at y=512 (top y=392, bottom y=632). Do not enlarge it to a typical hero product size. Ice and splash around it also entirely between y=392 and y=650. Product is a small distant still life with generous dark space above and below.
```

## 라이드온 셔틀 카드 (`rideon-b.png`, 1024x1536)

```text
Use case: ads-marketing. Generate one realistic premium night-street commercial advertising photograph, PNG, exactly 1024x1536 portrait. A fictional modern yellow #ffd43b mid-size concert shuttle coach, generic original styling, photographed in side view with its passenger door open and warm interior light spilling onto the nearby wet pavement. Headlights on. Entire bus including wheels in the upper 60% of the image, approximately x=70..960 and y=350..850, in front of a concert arena exit at night just after a concert. Warm arena lights, light mist and drizzle, subtle wet-asphalt reflections. A few very distant blurred anonymous crowd silhouettes approaching behind the shuttle, no visible faces, no recognizable individuals, no visible driver. Keep bottom 40% y=922..1536 as calm dark empty asphalt/shadow with #1d2030 tone and minimal detail; keep all bright reflections and interior light spill above that zone. Natural photographed materials, premium night photography, realistic tires, windows, proportions and rain. Only lettering anywhere is the fictional word "RIDE ON", printed small and clean on the side of the shuttle. No other text, arena signage, slogans, route numbers, Korean, watermarks, UI, license plate text (blank or out of view), real brands, logos, trademarks, recognizable vehicle products. No illustration, cartoon or CGI render. Output just the photograph. Critical: front nose must be completely plain yellow with absolutely no emblem, icon, symbol, badge or ornament. Bottom 40 percent is almost unlit navy road: cut off bright reflections above y=900. Frame the bus and arena lights slightly higher to ensure this.
```
