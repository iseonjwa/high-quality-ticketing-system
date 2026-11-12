---
title: EC2에 직접 올리는 것 말고, HTTPS까지 알아서 붙여 주는 AWS 서비스(Lightsail 등)는 무엇이 있나
date: 2026-11-05
updated: 2026-11-05
asker: Junhyun An
level: 기초
tags: [deployment, https, aws, lightsail, cloudfront, app-runner, paas]
---

# EC2에 직접 올리는 것 말고, HTTPS까지 알아서 붙여 주는 AWS 서비스(Lightsail 등)는 무엇이 있나

## 질문
- EC2로 정석 배포하는 것 말고, HTTPS도 편하게 달아 주는 서비스가 AWS에 있지? Lightsail인가?

## 짧은 답
- **있다. 다만 Lightsail이라고 다 되는 것은 아니다.** "HTTPS가 자동으로 붙는" 것은 Lightsail 안에서도 **컨테이너 서비스**와 **배포(CDN)**다. Lightsail **인스턴스**는 EC2와 같은 가상 서버라 인증서를 직접 붙여야 한다.
- AWS에서 HTTPS가 자동으로 붙는 대표 서비스
  - **CloudFront**: `*.cloudfront.net`
  - **Lightsail 컨테이너 서비스**: `*.cs.amazonlightsail.com`
  - **Amplify Hosting**: `*.amplifyapp.com`, 프론트 전용
  - **App Runner**: 2026-04-30부터 새 고객을 받지 않는다.
- 이 프로젝트에는 서버를 옮기는 것보다 **지금 EC2 앞에 CloudFront를 두는 쪽**이 맞다.
  - 도메인 없이 `https://<무작위>.cloudfront.net`이 생긴다.
  - 서버와 compose는 그대로 둔다.
- 컨테이너 서비스·App Runner 같은 "편한" 서비스는 **정해진 모양**을 요구한다. 서비스 하나에 컨테이너 하나, 데이터는 밖에 두는 모양이다.
  - 우리는 Kafka·Postgres·Redis·관측 스택까지 컨테이너 20개를 한 compose로 띄운다.
  - 그래서 그대로 올라가지 않는다.

## 자세히

### 1. 바탕 개념: "HTTPS가 자동"의 정체
- HTTPS에는 **인증서**가 필요하다. 인증서는 "이 이름(도메인이나 IP)의 주인이 맞다"를 인증 기관이 확인해 준 문서다.
- 우리 서버에는 이름이 없고 IP(`52.63.10.174`)만 있다. 그래서 HTTPS를 붙이는 방법은 두 가지뿐이다.
  1. **IP 자체로 인증서를 받는다.** Let's Encrypt IP 인증서다(유효 약 6일, 자동 갱신 필요). 앞 노트 [외부 접속과 무료 HTTPS](2026-11-05-why-front-unreachable-by-ip.md) 3절에 있다.
  2. **이미 인증서가 있는 남의 이름을 빌린다.** AWS 관리형 서비스가 HTTPS를 "자동으로" 주는 방식이다.
     - AWS는 `cloudfront.net`, `amplifyapp.com` 같은 도메인과 그 와일드카드 인증서를 이미 갖고 있다.
     - 서비스를 만들면 그 아래 하위 주소(예: `d1abcd.cloudfront.net`)를 하나 내준다. 인증서도 AWS 것이 그대로 적용된다.
- 내 도메인(예: `iseonjwa.com`)을 쓰려면 어느 서비스든 **도메인을 사고 소유를 증명**해야 한다(ACM이나 Lightsail 인증서의 DNS 검증). 인증서 자체는 무료다(ACM 공인 인증서). 돈과 시간이 드는 부분은 도메인이다.

### 2. AWS 선택지 비교
| 서비스 | 무엇인가 | HTTPS | 이 프로젝트에 맞나 |
|---|---|---|---|
| **Lightsail 인스턴스** | 서버·디스크·전송량을 월 정액으로 묶은 간단한 가상 서버 | 자동 아님. 도메인을 연결해 직접 인증서를 붙이거나, 유료 Lightsail 로드 밸런서(도메인 필요)를 둔다 | 애매하다. compose는 그대로 돌지만 CPU가 **버스트형**(기준 성능 + 크레딧)이다. flex 인스턴스를 부하 측정 때문에 바꾼 것과 같은 문제가 생긴다 |
| **Lightsail 컨테이너 서비스** | 이미지를 주면 알아서 띄우는 간단한 컨테이너 실행 서비스 | **자동**: `https://<이름>.<GUID>.<리전>.cs.amazonlightsail.com`. HTTPS만 받는다 | 안 맞는다. 한 배포에 컨테이너 최대 10개이고 공개 컨테이너는 1개다. 노드를 늘리면 같은 묶음 전체가 복제된다. 꺼 두어도 요금이 나가고 삭제해야 멈춘다 |
| **App Runner** | 컨테이너 이미지나 소스로 웹 서비스를 띄우는 완전 관리형 | 자동(`*.awsapprunner.com`) | 쓸 수 없다. **2026-04-30부터 새 고객을 받지 않는다.** AWS는 대안으로 ECS Express Mode를 안내한다 |
| **Elastic Beanstalk** | EC2·로드 밸런서·오토스케일링을 대신 만들어 주는 묶음 | 로드 밸런서에 ACM 인증서를 붙인다. **도메인이 필요하다** | 무겁다. 결국 EC2와 로드 밸런서 요금이 같이 나가고 도메인도 필요하다 |
| **Amplify Hosting** | 정적 프론트(빌드 결과물) 호스팅 | 자동(`*.amplifyapp.com`) | 반쪽이다. 화면만 https가 된다. https 화면에서 `http://` API를 부르면 브라우저가 **혼합 콘텐츠**로 막는다. API도 https여야 한다 |
| **CloudFront** | AWS의 CDN. 전 세계 엣지가 요청을 받아 원본 서버로 넘긴다 | **자동**: `https://<무작위>.cloudfront.net`. 기본 인증서가 붙는다 | **가장 맞다.** 원본을 지금 EC2로 두면 서버·compose·Jenkins 배포를 바꾸지 않는다 |

정리하면 "편하게 HTTPS"를 주는 서비스는 둘로 나뉜다.
- **실행까지 맡기는 쪽**: 컨테이너 서비스, App Runner. 우리 구조를 그 모양에 맞게 쪼개야 한다.
- **앞단만 맡기는 쪽**: CloudFront. 우리 구조를 건드리지 않는다.

### 3. 왜 "편한" 서비스에 우리 구조가 안 올라가나
- 관리형 실행 서비스(PaaS)는 **"서비스 하나 = 웹 컨테이너 하나, 상태는 밖에"**를 전제로 한다.
  - 그래서 늘리기(복제)와 바꾸기(새 버전으로 교체)를 알아서 해 줄 수 있다.
- 우리 배포 서버의 컨테이너 20개 중 절반 가까이는 웹 앱이 아니다.
  - **상태를 가진 것**: Postgres, Kafka, Redis
  - **관측 스택**: Prometheus, Grafana, Loki 등
  - 이것들을 관리형으로 옮기면 RDS, MSK, ElastiCache 같은 서비스가 각각 하나씩 필요하다. 비용과 설정이 부트캠프 기간에 맞지 않는다.
- 부하 측정도 VM이 낫다.
  - 서버 스펙(m7i.xlarge, 고정 성능)을 우리가 정하고 그대로 잴 수 있다.
  - 관리형은 숨은 제한이 결과에 섞인다. 버스트형 CPU, 노드 단위 자동 복제 같은 것이다.
- 그래서 "EC2 + compose"는 운영 정석이라기보다 **이 규모·기간에 맞는 가장 단순한 선택**이다. 운영 정석에 가까운 모양은 ECS·EKS에 RDS·MSK를 붙이는 쪽이다.

### 4. 이 프로젝트에 CloudFront를 붙인다면
HTTPS는 리허설 뒤에 하기로 했다(사용자 결정, 2026-11-05). 그때 Let's Encrypt IP 인증서와 이 방식을 비교해 고른다.

**구성**
| 항목 | 값 |
|---|---|
| 원본 | EC2 **퍼블릭 DNS 이름**(`ec2-52-63-10-174.ap-southeast-2.compute.amazonaws.com` 꼴). 맞춤 원본은 IP를 받지 않고 DNS 이름을 받는다. VPC에서 DNS 호스트 이름이 켜져 있어야 이 이름이 생긴다 |
| 원본 프로토콜 | HTTP만, 포트 80. 지금 nginx 그대로다 |
| 사용자 쪽 프로토콜 | HTTP를 HTTPS로 넘김(redirect) |
| `/api/*` 동작 | 캐시 끔(`CachingDisabled`). 헤더·쿼리·쿠키를 모두 넘기고(`AllViewerExceptHostHeader`), 모든 메서드(GET·POST·PATCH·DELETE 등)를 허용한다 |
| 나머지(화면) | 원본의 `Cache-Control`을 따른다(`UseOriginCacheControlHeaders`). nginx가 이미 `/assets/`는 1년(`immutable`), `index.html`은 `no-cache`로 나눠 둔다 |

**좋은 점**
- 도메인이 필요 없다.
- certbot, 6일마다 갱신, nginx 443 설정이 모두 필요 없다. 콘솔에서 배포(distribution) 하나만 만들면 된다.
- 화면과 API가 같은 주소(`https://<무작위>.cloudfront.net`, `/api`)로 나가서 CORS 문제가 없다.
- 요금은 상시 프리 티어(월 전송 1TB, 요청 1천만 건) 안에 들 것이다. 시연과 팀원 접속 정도의 양이다.

**주의할 점**
- **CloudFront → EC2 구간은 HTTP다.** 사용자 ↔ CloudFront만 암호화된다. 시연·학습용으로는 괜찮지만, 운영이면 원본에도 인증서를 붙인다.
- **시연 모드 Grafana 링크가 깨진다.**
  - 링크는 화면을 연 호스트 기준이다(`defaultGrafanaUrl`). 그래서 `https://<무작위>.cloudfront.net:3000`이 되는데, 이 주소에는 아무것도 없다.
  - 프론트 `Dockerfile`에는 지금 빌드 인자가 없다. 빌드 인자를 더해 `VITE_GRAFANA_URL=http://52.63.10.174:3000`을 넣거나, CloudFront에 Grafana 경로를 하나 더 둔다.
- **부하 측정은 CloudFront를 거치지 않는다.** k6는 지금처럼 같은 VPC에서 gateway 8080으로 바로 쏜다. CloudFront를 거치면 우리 서버가 아니라 CDN을 재게 되고 요청 요금도 붙는다.
- 이 경우 Jenkins 스모크(`PUBLIC_URL=http://52.63.10.174`)는 그대로 둔다. 기존 `http://` 주소도 당분간 열어 둔다.

### 5. 고르는 기준
| 상황 | 추천 |
|---|---|
| 지금 구조 그대로, 도메인 없이, 가장 적은 작업 | CloudFront 앞단 |
| 주소를 `https://52.63.10.174`처럼 IP로 유지하고 싶다 | Let's Encrypt IP 인증서 |
| 보기 좋은 주소(`iseonjwa.com`)가 필요하다 | 도메인 구입(Route 53 등) + ACM 인증서 + CloudFront |
| 새 프로젝트, 웹 앱 하나, 데이터는 관리형 DB | Lightsail 컨테이너 서비스나 ECS Express Mode 같은 관리형 실행 |

## 면접 포인트
- "관리형 서비스가 HTTPS를 자동으로 준다"는 말은 **AWS 소유 도메인의 하위 주소와 인증서를 빌려 쓴다**는 뜻이다. 내 도메인을 쓰는 순간 소유 증명(DNS 검증)은 내 몫이 된다.
- **TLS 종료 위치**를 말할 수 있어야 한다. CloudFront나 로드 밸런서에서 끝내면 서버는 HTTP만 다뤄도 되지만, 그 뒤 구간은 암호화되지 않는다(필요하면 다시 암호화한다).
- IaaS(EC2)와 PaaS(App Runner·컨테이너 서비스)의 교환 관계
  - PaaS는 편하지만 정해진 모양(무상태 웹 서비스)을 요구한다.
  - 상태를 가진 구성 요소와 부하 측정이 있는 시스템은 VM 위에서 직접 다루는 편이 통제하기 쉽다.
- 관리형 서비스도 종료·신규 중단이 있다(App Runner, 2026-04). 기술을 고를 때 그 서비스의 상태를 확인한다.

## 참고
- [Amazon Lightsail: Deploy and manage containers (기본 도메인·HTTPS·배포당 컨테이너 수)](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-container-services.html)
- [Amazon Lightsail: baseline CPU performance and burst capacity](https://docs.aws.amazon.com/lightsail/latest/userguide/baseline-cpu-performance.html)
- [AWS App Runner availability change](https://docs.aws.amazon.com/apprunner/latest/dg/apprunner-availability-change.html)
- [Amazon Lightsail 가격](https://aws.amazon.com/lightsail/pricing/)
- 앞 노트: [외부 접속과 무료 HTTPS](2026-11-05-why-front-unreachable-by-ip.md), [flex에서 일반 인스턴스로](2026-11-05-ec2-change-instance-type-flex-to-standard.md), [컨테이너가 왜 20개인가](2026-11-05-why-20-containers-in-deploy.md)
