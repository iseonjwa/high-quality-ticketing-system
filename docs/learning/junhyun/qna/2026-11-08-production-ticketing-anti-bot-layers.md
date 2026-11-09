---
title: 실제 티켓 서비스는 매크로와 직접 API 호출을 어떻게 막나
date: 2026-11-08
updated: 2026-11-08
asker: Junhyun An
level: 중급
tags: [queue, bot, rate-limit, waiting-room, fairness, security]
---

# 실제 티켓 서비스는 매크로와 직접 API 호출을 어떻게 막나

## 질문
실제 운영 중인 서비스들은 어떻게 구현하나. 프론트에서 온 요청만 받거나 rate limit을 두는 방법도 있을 것 같다.
([앞 질문: 프론트를 거치는 사람이 직접 API를 쏘는 사람보다 늦지 않나](2026-11-08-queue-fairness-frontend-vs-direct-api.md)의 후속)

## 짧은 답
- 하나로 막지 않고 층을 쌓는다. ① 사람인지·정상 기기인지 판별, ② 요청 제한, ③ 오픈 순간 속도 경쟁을 없애는 순번 규칙(사전 대기실 + 무작위), ④ 대기열을 통과했다는 서명 토큰, ⑤ 계정·사람 단위 제한, ⑥ 사후 탐지와 법적 처벌.
- "프론트에서 온 요청만 받기"는 원리상 보장할 수 없다. 프론트가 붙이는 값은 모두 사용자 손안에서 만들어지므로 스크립트가 똑같이 만든다. 게다가 매크로는 진짜 프론트를 headless 브라우저로 띄워 버튼을 누른다. 실서비스가 확인하는 것은 "프론트를 거쳤나"가 아니라 "필요한 단계를 거쳤나(서명 토큰)"와 "사람일 확률이 높나(봇 점수)"다.
- rate limit은 꼭 있어야 하는 기본층이다. 연타와 부하는 막지만, 정각에 한 번만 정확히 쏘는 스크립트와 계정·IP를 여러 개 쓰는 쪽은 막지 못한다.
- 오픈 순간 ms 경쟁 자체는 사전 대기실 + 무작위 순번으로 없앤다. Queue-it, Ticketmaster, Cloudflare가 이 방식을 제공한다.

## 자세히

### 1. "프론트에서 온 요청만 받기"가 안 되는 이유
- 서버는 HTTP 요청을 누가 보냈는지 알 수 없다. `Origin`, `Referer`, `User-Agent`, 커스텀 헤더는 curl 한 줄로 똑같이 보낸다.
- 프론트 JS에 비밀키를 넣고 요청에 서명해도, JS는 사용자에게 내려가는 코드라서 키도 함께 내려간다. 비밀이 아니다.
- 매크로는 우리 화면을 그대로 쓴다. Playwright·Puppeteer로 진짜 브라우저를 띄우고, 우리 프론트가 만든 요청을 그대로 보낸다. 이 요청은 실제로 프론트를 거친 요청이다. 이 프로젝트가 시연 흐름 테스트(ISE-118)에 쓰려는 도구가 그대로 매크로 도구다.
- 그래서 원칙은 "클라이언트를 믿지 않는다"다. 서버가 판단할 수 있는 것만 판단한다.

### 2. 실서비스가 대신 확인하는 두 가지

**대기열을 통과했다는 증명 (서명 토큰)**
Queue-it의 KnownUser SDK는 대기열을 통과한 사용자에게 해시가 붙은 토큰(대기열 식별자, 유효 시각, 해시)을 주고 원래 사이트로 돌려보낸다. 사이트 서버는 정적 파일을 뺀 모든 요청에서 이 토큰을 검증한다. User-Agent를 속여 대기열을 건너뛰는 것도 막는다. 프론트를 거쳤는지는 모르지만, 대기열을 건너뛰지 않았다는 것은 서버가 확실히 안다.

**사람·정상 기기일 확률 (봇 관리)**
- 웹: JS 챌린지, 브라우저 지문, 마우스·키 입력 같은 행동 분석, 퍼즐형 보안문자. 결과는 "확실히 사람"이 아니라 점수다. Queue-it은 봇 검사를 통과해야 대기열 진입 토큰(Enqueue Token)을 주는 기능을 둔다. NOL 티켓은 예매 중 퍼즐 조각을 맞추는 인증 팝업을 띄운다고 보도됐다.
- 모바일 앱: Android Play Integrity, iOS App Attest가 "진짜 앱이 진짜 기기에서 보낸 요청"을 플랫폼 서명으로 증명한다. 서버가 그 토큰을 검증한다. 웹에는 이만큼 강한 장치가 사실상 없다. 티켓 서비스가 앱 예매를 권하는 이유 중 하나다.
- 모두 우회 비용을 올리는 장치다. 보안문자는 외부 풀이 서비스로 우회하는 사례가 있다.

### 3. rate limit이 막는 것과 못 막는 것
| 막는 것 | 못 막는 것 |
|---|---|
| 새로고침·연타 폭주, 오픈 전 미리 쏘는 연타의 부하 | 정각에 한 번만 정확히 쏘는 스크립트(제한 안에 들어간다) |
| 비밀번호 대입 같은 대량 시도 | 계정 여러 개, 주거용 프록시로 IP 여러 개 |

설계 주의점
- IP 기준만 쓰면 대학·회사·통신사 NAT 뒤의 여러 사람이 한 IP를 나눠 쓰다 함께 막힌다. 로그인 사용자 기준을 주로 쓰고, IP 기준은 느슨하게 함께 둔다.
- 오픈 직전은 정상 사용자도 요청이 많다. 대기열 순번 조회처럼 서버가 주기를 알려 주는 API는 그 주기에 맞춰 한도를 정한다.
- Queue-it도 스스로를 "WAF, rate limiting, 봇 탐지와 함께 쓰는 한 층"이라고 설명한다.

### 4. 속도 경쟁을 없애는 순번 규칙: 사전 대기실 + 무작위
- **Queue-it 예약형 대기실**: 오픈 전 카운트다운 화면(pre-queue)에 사람을 모은다. 0이 되는 순간 그 안의 사람에게 무작위로 순번을 준다. 그 뒤 도착자는 맨 뒤에서 FIFO로 선다. 스크립트가 정각 직후 ms 단위로 들어오는 이점을 없애려는 장치라고 설명한다.
- **Ticketmaster Smart Queue**: 오픈 전에 로그인해 대기실에 들어오면 오픈 때 자동으로 줄 자리를 받는다. 일찍 들어왔다고 앞자리를 받지는 않는다고 안내한다. 다만 자리를 어떻게 정하는지는 공개하지 않았다. 2026년 5월에는 "자리가 무작위가 아니다"라는 취지의 경영진 발언이 보도돼 논란이 있었다(1차 출처는 확인하지 못함).
- **Cloudflare Waiting Room**: 대기 방식을 FIFO(쿠키에 적은 도착 시각 순)와 Random(빈자리가 날 때 무작위로 내보냄) 중에서 고른다. Random은 일부 요금제에서만 쓸 수 있다. 사전 대기(prequeue) 중에는 방식과 상관없이 아무도 들여보내지 않는다.
- 무작위여도 계정이 많으면 당첨 확률이 오른다. 그래서 다음 층이 필요하다.

### 5. 계정·사람 단위로 비용 올리기와 사후 대응
- **본인 인증**: 국내 예매처는 예매자 본인 확인을 강화하는 추세다. 멜론티켓에 신분증 확인 서비스를 공급했다는 보도가 있다.
- **사전 등록과 심사**: Ticketmaster Verified Fan은 미리 등록한 팬을 골라 선예매 코드를 준다.
- **매수 제한, 취소표 지연 공개**: NOL은 취소표를 임의의 시간 뒤에 판다. 취소와 재예매로 표를 넘기는 개인 간 거래를 막으려는 정책이라고 설명했다.
- **이상거래 탐지와 수사**: 예매처는 자체 이상거래 탐지로 매크로 의심 거래를 미리 막고 경찰과 함께 모니터링한다. NOL 운영사가 고소한 매크로 예매 사건을 경찰이 수사한 보도도 있다.
- **법**: 공연법 개정으로 2024년 3월 22일부터 매크로로 산 입장권의 웃돈 재판매가 금지됐다(1년 이하 징역 또는 1천만 원 이하 벌금). 매크로 사용을 수사기관이 입증해야 해서 실효성 지적이 있었다. 2026년 1월 국회를 통과한 후속 개정은 매크로 사용과 상관없이 상습·영업 목적의 부정 판매를 금지하고, 판매 금액의 최대 50배 과징금을 도입했다. 시행일은 2026년 8월로 보도됐지만 기사마다 날짜가 다르다.

## 이 프로젝트에서는
| 층 | 실서비스 예 | 이 프로젝트 (2026-11-08 `origin/dev`) |
|---|---|---|
| 사람·기기 판별 | 봇 점수, 퍼즐 인증, Play Integrity | 없음. 보안문자는 SA Could, 행동 기반 매크로 탐지는 Won't |
| 요청 제한 | WAF, rate limit | SA 12-8에 `ratelimit:{userId}`(1초) 설계, 공통 오류 코드 `RATE_LIMITED`(429)만 있고 제한기는 없음 |
| 순번 규칙 | 사전 대기실 + 무작위 | Redis `INCR` 도착 순. 사전 대기실 + 무작위 순번은 SA Could |
| 대기열 통과 증명 | Queue-it 서명 토큰 | 있음. 입장 토큰을 Gateway가 검증하고 `sub`와 로그인 사용자를 비교한다(ADR 0015) |
| 계정 단위 제한 | 본인 인증, 매수 제한 | 계정당 대기열 1자리, 1인 최대 매수. 실제 본인 인증은 Won't |
| 속도와 무관한 분배 | Verified Fan, 추첨 | 추첨 배분 + 자동결제(F6) |
| 사후 대응 | 이상거래 탐지, 법 | 없음(범위 밖) |

Gateway는 WebFlux 기반(`spring-cloud-starter-gateway-server-webflux`)이고 reactive Redis 의존성이 이미 있다. 그래서 Spring Cloud Gateway의 `RequestRateLimiter` 필터(Redis 토큰 버킷)와 사용자 ID `KeyResolver`로 SA의 사용자별 제한을 붙일 수 있다. 한도를 정할 때는 부하 측정(k6)이 같은 계정으로 요청을 몰면 429에 걸린다는 점을 함께 봐야 한다.

## 면접 포인트
- "프론트에서 온 요청만 받으면 되지 않나?" → 클라이언트가 보내는 값은 모두 위조할 수 있고, 매크로는 진짜 프론트를 headless 브라우저로 쓴다. 서버가 확인할 수 있는 것은 자기가 서명한 토큰(대기열 통과)과 확률적인 봇 점수다.
- "rate limit이면 충분한가?" → 부하와 연타는 막는다. 정각에 한 번 쏘는 스크립트, 다계정·다중 IP는 못 막는다. 순번 규칙과 계정 단위 제한이 함께 있어야 한다.
- "대기열의 공정성을 어떻게 높이나?" → 사전 대기실에 모아 오픈 순간 무작위로 순번을 주면 ms 경쟁이 사라진다. 다계정 문제가 남으므로 계정당 1자리·본인 인증과 함께 쓴다.

## 참고
- Queue-it: [Scheduled waiting room](https://queue-it.com/product/scheduled/), [Bot & abuse management](https://queue-it.com/developers/bots-abuse-management/), [How does Queue-it work](https://www.queue-it.com/how-does-queue-it-work/), [KnownUser Fastly](https://github.com/queueit/KnownUser.Fastly)
- Ticketmaster: [How A Ticketmaster Queue Works](https://blog.ticketmaster.com/what-is-ticketmaster-smart-queue/), [Smart Queue](https://business.ticketmaster.com/smart-queue/), [Digital Music News 2026-05 대기열 자리 논란](https://www.digitalmusicnews.com/2026/05/15/ticketmaster-queue-positions-controversy/)
- Cloudflare: [Waiting Room queueing methods](https://developers.cloudflare.com/waiting-room/reference/queueing-methods/)
- 앱 증명: [Expo App Integrity (Play Integrity·App Attest)](https://docs.expo.dev/versions/latest/sdk/app-integrity/)
- 국내: [문화체육관광부 보도자료 2024-03-18](https://www.mcst.go.kr/site/s_notice/press/pressView.jsp?pSeq=20924), [비즈한국 NOL 퍼즐 인증·취소표 정책](https://bizhankook.com/articles/32001.html), [아시아경제 2026-05 경찰·예매처 공동 대응](https://www.asiae.co.kr/en/article/social-general/2026051209162887080), [아르고스 ID check 멜론티켓 공급](https://ditoday.com/?p=124154), [STCLab NetFUNNEL](https://stclab.com/en/product/netfunnel)
- 저장소: `docs/core/SA MASTER.md` 1-2, 5-1, 12-8, `docs/decisions/0015-queue-admission-guards.md`
