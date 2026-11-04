---
title: 실제 운영 중인 추첨 서비스는 공정성을 어떻게 보여 주는가
date: 2026-10-30
updated: 2026-10-30
asker: GGangMY
level: 중급
tags: [lottery, fairness, commit-reveal, provably-fair, anti-bot]
---

# 실제 운영 중인 추첨 서비스는 공정성을 어떻게 보여 주는가

## 질문
추첨 순위 알고리즘(D7)에서 시드 약속(commit-reveal)을 검토하다가 나온 질문. 실제 운영 중인 서비스들은 어떤 식으로 공정성을 어필하는가.

## 짧은 답
공정성을 보여 주는 방식은 크게 다섯 가지다. 사람이 지켜보는 공개 추첨(로또), 기관 신뢰(청약), 규칙 공개와 부정 참여 차단(Nike SNKRS, Ticketmaster Verified Fan), 결과 증빙 페이지(경품 추첨 서비스), 수학적 검증(provably fair, 주로 온라인 게임·블록체인)이다.
티켓·한정판 판매 서비스는 "추첨 난수가 조작되지 않았다"보다 "봇과 중복 응모를 걸러 진짜 사람만 추첨한다"를 공정성으로 내세운다. 추첨 자체를 외부에서 검증할 수 있게 공개한 사례는 이번 검색에서 찾지 못했다.

## 자세히

| 방식 | 사례 | 무엇으로 믿게 하나 | 한계 |
|---|---|---|---|
| 공개 추첨 + 제3자 입회 | 로또 6/45, 연금복권720+ | 생방송, 경찰·방청객 입회, 봉인 확인, 예비 추첨기, 참관인이 추첨볼 무게 측정. 조작 의혹이 나오자 일반인 150명 참관 행사를 열었다 | 물리 추첨에만 맞다. 비용이 크다 |
| 기관 신뢰 | 청약홈 | 한국부동산원의 컴퓨터 입주자 선정 프로그램이 당첨자를 고른다고 공고문에 적는다 | 알고리즘이나 검증 절차를 공개한 자료는 이번 검색에서 확인하지 못했다 |
| 규칙 공개 + 부정 참여 차단 | Nike SNKRS Draw, Ticketmaster Verified Fan | 약관에 "응모 전체에서 무작위 추첨", "1인 1회 응모, 중복은 무효"를 적고, 봇 차단(계정 검증, IP 평판)을 공정성 근거로 든다 | 추첨 자체의 독립 감사는 공개되지 않았다. 숫자(봇 차단 건수, 재판매 비율)도 회사 주장이다 |
| 결과 증빙 | 경품 이벤트 추첨기 서비스 | 난수 알고리즘과 무결성 검증으로 추첨하고, 참여자가 결과를 확인하는 페이지를 준다 | 무엇을 어떻게 검증하는지는 기사만으로 알 수 없다 |
| 수학적 검증(provably fair) | 온라인 게임, 블록체인 게임 | 서버 시드의 해시를 먼저 공개하고 나중에 시드를 공개한다. 누구나 해시를 다시 계산하고 결과를 재현한다 | 시드를 바꾸지 않았다는 것만 증명한다. 결과를 만드는 알고리즘이 공정한지는 따로 보여야 한다 |

### 공정성은 두 층이다
1. **누가 추첨에 들어가는가**: 1인 1응모, 중복 계정·봇 차단. 티켓 서비스가 주로 강조하는 층이다.
2. **추첨이 조작되지 않았는가**: 난수 고정과 재현. 복권과 provably fair가 강조하는 층이다.

## 이 프로젝트에서는
- 1층: D2·D5로 1인 1회차 1응모와 재응모 금지를 DB 제약으로 막았다. 다중 계정이나 봇 차단(본인 인증 등)은 지금 범위 밖이다.
- 2층: D7 추천(`SHA-256(seed ‖ entryId)` 정렬 + 시드 해시 사전 공개)은 provably fair와 같은 구조다. 티켓 서비스에서는 흔하지 않아 발표에서 차별점이 될 수 있다. 한계도 같아서, 순위 계산식과 배정 규칙(건너뛰기, 예비자 수)을 함께 공개해야 검증이 의미가 있다.
- 설계 문서: `docs/design/lottery/options.md` D7.

## 면접 포인트
- **추첨 기능의 공정성을 어떻게 보장했나요?** 두 층으로 나눴다. 참여 단계는 1인 1응모를 DB 유니크 제약으로 막았고, 추첨 단계는 시드 해시를 응모 전에 공개하고 추첨 뒤 시드를 공개해 누구나 순위를 재계산할 수 있게 했다. 이 방식은 시드를 바꾸지 않았다는 것만 증명하므로 계산식과 배정 규칙도 함께 공개했다.

## 참고
- 로또 공개 추첨: [더팩트, 150명 참관 로또 추첨](https://news.tf.co.kr/read/ogmeta/2023363.htm), [연금복권 추첨 현장 공개](https://v.daum.net/v/20250626195101134?f=p)
- Nike: [Nike Draw rules and Terms and Conditions](https://www.nike.com/au/help/a/nike-launch-drawing), [SoleSavy, 봇 차단](https://solesavy.com/nike-snkrs-app-bot-protection/?amp=1)
- Ticketmaster: [NBC News, Verified Fan](https://www.nbcnews.com/pop-culture/ticketmaster-says-verified-fan-registration-doesnt-measure-fandom-fans-rcna81187)
- provably fair: [DEV Community, commit-reveal의 한계](https://dev.to/rishiw/commit-reveal-isnt-enough-designing-provably-fair-systems-that-actually-survive-verification-40nl), [provably fair verifier](https://github.com/rakestake/provably-fair-verifier)
- 경품 추첨기: [데일리안](https://m.dailian.co.kr/news/view/1596123)
- 청약 공고문의 선정 프로그램 문구: [입주자모집공고 예시](https://files-scs.pstatic.net/2025/02/25/VWeB6ZivNt/e편한세상 동대구역 센텀스퀘어 입주자모집공고문.pdf)
