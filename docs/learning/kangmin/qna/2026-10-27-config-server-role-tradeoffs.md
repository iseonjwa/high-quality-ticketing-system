---
title: Config 서버는 무슨 역할을 하고, 설정을 따로 분리하면 무엇을 얻고 잃나
date: 2026-10-27
updated: 2026-10-27
asker: GGangMY
level: 중급
tags: [spring-cloud-config, msa, configuration, 12-factor]
---

# Config 서버는 무슨 역할을 하고, 설정을 따로 분리하면 무엇을 얻고 잃나

## 질문
config server 역할 및 분리 의미와 트레이드오프

## 짧은 답
- **역할**: 서비스들이 기동할 때 HTTP로 "내 이름·프로필에 맞는 설정"을 받아 가는 설정 전용 서버다. 설정 파일은 서비스 jar가 아니라 한 저장소(`config-repo/`)에 모여 있다.
- **분리 의미**: 설정을 코드·이미지에서 떼어 낸다. 같은 이미지를 local·loadtest·prod에서 프로필만 바꿔 쓰고, 여러 서비스가 공유하는 값(Kafka 주소, 샘플링 비율, 토큰 만료)은 한 번만 적는다.
- **대가**: 컨테이너가 하나 늘고, 서비스 기동이 Config 서버에 묶이며, 설정이 "어느 파일에서 왔는지" 추적하는 규칙(우선순위)을 알아야 한다. 테스트도 Config 서버를 끄는 처리가 필요하다.
- 기동 후에는 값을 이미 받아 둔 상태라 Config 서버가 죽어도 실행 중인 서비스는 멈추지 않는다. 묶이는 시점은 **기동과 재시작**이다.

## 자세히

### 동작 순서 (이 저장소 기준)

```
config-repo/*.yml ──(읽기 전용 마운트)──> config-server:8888
                                              ↑ GET /{서비스}/{프로필}
user-service 기동 → spring.config.import: configserver:... (test 프로필이 아닐 때만)
                 → 받은 설정 + 자기 application.yml 합쳐서 Environment 구성
                 → ${USER_DB_PASSWORD}는 서비스 쪽에서 환경 변수로 치환
```

- 서비스는 자기 이름(`spring.application.name`)과 활성 프로필로 요청한다.
- 우선순위(이 저장소에서 테스트로 확인): `{서비스}-{프로필}.yml` > `application-{프로필}.yml` > `{서비스}.yml` > `application.yml`. 리스트 값은 합쳐지지 않고 통째로 바뀐다.
- 비밀값은 config-repo에 `${...}` 자리표시자로만 있다. Config 서버 응답에도 자리표시자 그대로 나가고(실제 값 0회 노출 확인), 치환은 `.env`로 환경 변수를 받은 각 서비스 컨테이너가 한다.

### 선택지 비교

| 방식 | 설정 위치 | 장점 | 단점 |
|---|---|---|---|
| 서비스별 `application.yml`만 | 각 jar 안 | 가장 단순. 추가 프로세스 없음 | 공통 값이 서비스 수만큼 복제. 값 하나 바꾸려면 여러 서비스 재빌드 |
| 환경 변수 + 프로필 (12-Factor) | compose `.env`·`environment` | 추가 프로세스 없음. 컨테이너 표준 방식 | 값이 많아지면 compose가 길어지고, 구조(중첩·리스트)를 표현하기 어려움 |
| **Config 서버 (native)** (선택) | 모노레포 `config-repo/` 폴더 | 공통·서비스·프로필 3단 구조, 한 곳에서 관리, 리뷰가 PR로 됨 | 컨테이너 1개, 기동 의존, 파일을 바꾸면 서비스 재시작 필요 |
| Config 서버 (git 백엔드) | 별도 git 저장소 | 커밋 이력이 곧 설정 변경 이력. 재배포 없이 브랜치·라벨 전환 | 저장소 접근 토큰 관리, 저장소가 하나 더 생김 |
| 플랫폼 기능 (K8s ConfigMap, Consul, AWS Parameter Store, Vault) | 인프라 | 운영 환경 표준, 비밀값 전용 관리(Vault) | 이 프로젝트의 compose·EC2 1대 환경에는 과함 |

### Config 서버 없이 같은 구조를 얻는 방법 (2026-10-27 실험)
Spring Boot 기본 기능만으로도 같은 3단 구조를 얻을 수 있다. `config-repo/`를 각 컨테이너에 마운트하고 `SPRING_CONFIG_NAME=application,<서비스>`, `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/config/`를 주면 된다. Eureka jar로 파일 4개를 하나씩 지우며 확인한 우선순위는 Config 서버와 같았다(`{서비스}-{프로필}` > `application-{프로필}` > `{서비스}` > `application`). 대가는 이 두 값을 `application.yml`에 쓸 수 없어 IntelliJ 실행 설정마다 환경 변수로 넣어야 한다는 점이다. 선택지 전체 비교(설정 관리 방식 7가지)는 강민 초안으로 정리했고, 통합 뒤 `docs/decisions/0003-config-server-native.md`에 보탤 예정이다.

### 고른 이유 (SA 9-6, #30)
- 측정하면서 자주 바꾸는 숫자 기준(타임아웃, 샘플링, 풀 크기)과 loadtest 프로필 전환을 한 곳에서 처리한다. 실제로 `application-loadtest.yml` 한 파일이 모든 서비스의 샘플링 비율을 0.1로 바꾼다.
- 개발(IntelliJ에서 `localhost:8888`), 측정, 배포가 같은 설정 파일을 읽는다.
- native 백엔드를 고른 이유: 모노레포라 설정 변경이 코드와 같은 PR로 리뷰된다. git 백엔드의 토큰 관리가 필요 없다.

### 감수한 점과 이 저장소에서 실제로 겪은 것

| 대가 | 실제로 겪은 것 / 대응 |
|---|---|
| 컨테이너·메모리 증가 | `-Xmx256m`, `mem_limit: 512m`. 아래 "자원 측정" 참고 |
| 기동 의존 | compose `depends_on: condition: service_healthy`로 config-server → eureka → 서비스 순서. `fail-fast: true`로 설정을 못 받으면 서비스가 뜨지 않게 함 |
| 테스트 복잡도 | 처음에는 test 프로필 문서에서 Config를 끄면 된다고 봤는데, import가 그보다 먼저 처리돼 Config 서버가 꺼져 있으면 테스트 7개가 실패했다. import를 `on-profile: "!test"` 문서로 옮겨 해결(TS-21 정정, TS-24) |
| 빌드 캐시 착시 | config-repo가 Gradle 테스트 입력이 아니라서 설정을 틀리게 바꿔도 테스트가 "최신"으로 건너뛰어짐 → 입력으로 등록(TS-20) |
| 값 출처 추적 | 4단 우선순위를 알아야 함. 처음에 README의 순서를 잘못 적었다가 테스트로 바로잡음 |
| 실행 중 변경 안 됨 | 기본은 재시작. SA는 측정용 값만 `@RefreshScope` + `/actuator/refresh`로 정함(아직 미구현). Spring Cloud Bus는 서비스 3개 규모에 과하다고 판단 |

### 자원 측정 (2026-10-27, 로컬 Docker에서 config-server만 단독 기동)

| 항목 | 값 |
|---|---|
| 메모리 대기 / 요청 51회 후 | 229.3 MiB / 236.5 MiB (상한 512 MiB) |
| CPU 대기 | 0.1~0.2% |
| 기동 | `Started ... in 3.82~5.05 seconds`, healthy까지 6초 |
| 설정 요청 1회 | 0.107초 |
| 이미지 고유 층 | 48.7 MB (전체 495 MB 중 JRE 기본 층은 다른 서비스와 공유) |
| 전체 대비 | compose `mem_limit` 합 11,392 MiB(16개) 중 512 MiB ≈ 4.5%, 실사용 기준 ≈ 2% |

- 단위: 메모리(`docker stats`, `mem_limit`, `-Xmx`)는 이진 단위 MiB(1024²바이트), 이미지 크기(`docker image ls`)는 십진 단위 MB(10⁶바이트)다. 이미지 실제 크기 494,832,661바이트가 `495MB`로 표시되는 것으로 확인했다.
- 확인 못 함: 서비스별 기동 지연 증가분, 부하 측정 중 영향(실행 중에는 호출하지 않으므로 없을 것으로 예상).
- 메모리가 빠듯해지면 상한을 실사용에 맞춰 낮출 여지가 있다. 부하 기준선 때 다른 컨테이너와 함께 다시 잰다.

### 단일 장애점인가
- **기동 시점에는 그렇다.** Config 서버가 없으면 `fail-fast` 때문에 새 서비스가 뜨지 않는다. 의도한 동작이다. 잘못된 기본값으로 조용히 뜨는 것보다 낫다.
- **실행 중에는 아니다.** 서비스는 기동 때 받은 값으로 계속 돈다. 재시작·스케일아웃이 겹칠 때만 영향을 받는다.
- 운영에서는 Config 서버를 여러 대 두거나, 받아 둔 설정을 로컬에 캐시하는 방식으로 보완한다. 이 프로젝트 범위에는 넣지 않았다.

### 되돌릴 조건 (SA 9-6)
기동 실패나 설정 추적 문제가 반복되면 `.env` + 프로필로 되돌린다. 설정 파일 구조(`{서비스}-{프로필}.yml`)는 그대로 각 서비스 `resources`로 옮기면 되므로 되돌리는 비용은 크지 않다.

## 이 프로젝트에서는
- 서버: `config-server/` (`@EnableConfigServer`, native, `search-locations: ${CONFIG_REPO_LOCATION:file:/config-repo/}`)
- 설정: `config-repo/` (application·서비스·프로필별 yml, README에 우선순위)
- 클라이언트: 각 서비스 `application.yml`의 `on-profile: "!test"` 문서에 `spring.config.import: configserver:${CONFIG_SERVER_URL:http://localhost:8888}`, 기본 문서에 `spring.cloud.config.fail-fast: true`
- 검증: `config-server/src/test/.../ConfigRepoTest.java` (우선순위, loadtest 샘플링, 비밀값이 `${`로 시작하는지)
- 결정 경위: 10/24 "발제상 선택이라 과잉"이라는 AI 피드백으로 빼기로 했다가, 10/26 설정 파일을 한 곳으로 모으자는 판단으로 다시 넣었다(SA 9-6, #30 잠정).

## 면접 포인트
- "Config 서버를 왜 썼나?": 서비스 여러 개가 공유하는 값과 환경별 값을 한 곳에서 관리하고, 같은 이미지를 프로필만 바꿔 배포하려고. 부하 테스트 중 숫자 기준을 한 파일에서 바꿀 수 있었다.
- "단점은?": 기동이 Config 서버에 묶이고 컨테이너가 늘어난다. 기동 순서는 healthcheck로, 설정 누락은 fail-fast로 막았다. 실행 중 서비스에는 영향이 없다.
- "비밀값은?": config-repo에는 자리표시자만 두고 실제 값은 `.env`로 컨테이너에 주입했다. 테스트로 비밀값이 자리표시자인지 검사한다. 운영 규모가 커지면 Vault 같은 비밀값 전용 저장소로 옮긴다.

## 참고
- Spring Cloud Config 문서 (Environment Repository, Config Client의 `spring.config.import`)
- 이 저장소 `docs/troubleshooting.md` TS-20, TS-21
- SA MASTER 9-6, 결정 기록 #30
