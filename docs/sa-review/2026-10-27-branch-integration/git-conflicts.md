# Git 병합 충돌 근거

확인 시각: 2026-10-27T16:17:02+09:00. Git merge-tree 2.52.0으로 고정 커밋 간 3-way 병합을 계산했다. 작업 브랜치·인덱스·작업 파일은 변경하지 않았다. Git object database에 계산된 tree만 생긴다.

dev/main 기준: `4e02b5ed84bf22bbdbeb22861c86f98ffb7127fd`. 아래 충돌 수는 파일 수이며, 같은 파일 안의 충돌 구간 수가 아니다. Git 충돌이 0이어도 클래스 중복·계약 불일치는 별도로 검토해야 한다.

## 스냅샷

| 구분 | 원격 브랜치 | SHA | dev에만/브랜치에만 있는 커밋 | dev 대비 변경 파일 | 변경 main Java/test Java |
| --- | --- | --- | --- | --- | --- |
| junhyun | `origin/codex/ise-5-junhyun-common-foundation` | `45874b7904d98b667aad0737500aa3631bdda3e1` | 0/35 | 184 | 62/20 |
| kwonwoo | `origin/kwonwoo/feat/common` | `60994695c70fcb4e0c8c2ec11128fd7bb868de33` | 0/5 | 52 | 12/6 |
| hyoseung | `origin/hyoseung/chore/init-env` | `acb862788e80ec602837afee8954baa1be528c02` | 0/9 | 81 | 27/9 |
| kangmin | `origin/ggangmy/chore/init-env` | `965f000b6363170eab5761ea76def129a8394264` | 0/21 | 130 | 50/7 |
| youngeun | `origin/youngeun/chore/init-config` | `86e2e66dfca4036ea4eb10387b695e48b5a4191f` | 0/1 | 78 | 44/0 |

## 전체 쌍 비교

명령: `git merge-tree --write-tree --name-only --no-messages <SHA A> <SHA B>`. 종료 코드 0은 텍스트 병합 성공, 1은 충돌이다. 컴파일·동작 성공을 뜻하지 않는다.

| 조합 | 종료 코드 | 충돌 파일 수 | 공통 조상 |
| --- | --- | --- | --- |
| junhyun + kwonwoo | 1 | 13 | `4e02b5ed84bf` |
| junhyun + hyoseung | 1 | 26 | `4e02b5ed84bf` |
| junhyun + kangmin | 1 | 26 | `4e02b5ed84bf` |
| junhyun + youngeun | 1 | 18 | `4e02b5ed84bf` |
| kwonwoo + hyoseung | 1 | 7 | `4e02b5ed84bf` |
| kwonwoo + kangmin | 1 | 4 | `4e02b5ed84bf` |
| kwonwoo + youngeun | 1 | 9 | `4e02b5ed84bf` |
| hyoseung + kangmin | 1 | 20 | `4e02b5ed84bf` |
| hyoseung + youngeun | 1 | 13 | `4e02b5ed84bf` |
| kangmin + youngeun | 1 | 34 | `4e02b5ed84bf` |

## Junhyun 기준의 충돌 파일

아래는 각 브랜치를 Junhyun에 독립적으로 합친 결과다. 여러 브랜치를 순서대로 합친 누적 결과는 별도 통합 브랜치에서 다시 검사해야 한다.

### kwonwoo (13개)

- `build.gradle`
- `common/build.gradle`
- `docs/ai-judgments/README.md`
- `docs/decisions/README.md`
- `docs/troubleshooting.md`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradlew`
- `gradlew.bat`
- `payment-service/build.gradle`
- `reservation-service/build.gradle`
- `settings.gradle`
- `user-service/build.gradle`

### hyoseung (26개)

- `.dockerignore`
- `.env.example`
- `Dockerfile`
- `build.gradle`
- `common-response/build.gradle`
- `common-web/build.gradle`
- `common/build.gradle`
- `config-repo/application-loadtest.yml`
- `config-repo/application.yml`
- `config-repo/gateway.yml`
- `config-repo/user-service.yml`
- `config-server/build.gradle`
- `config-server/src/main/resources/application.yml`
- `docs/README.md`
- `docs/ai-judgments/README.md`
- `eureka/build.gradle`
- `eureka/src/main/resources/application.yml`
- `gateway/build.gradle`
- `gateway/src/main/resources/application.yml`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradlew`
- `gradlew.bat`
- `settings.gradle`
- `user-service/build.gradle`
- `user-service/src/main/resources/application.yml`

### kangmin (26개)

- `.dockerignore`
- `.env.example`
- `.gitignore`
- `Dockerfile`
- `config-repo/application-loadtest.yml`
- `config-repo/application-local.yml`
- `config-repo/application-prod.yml`
- `config-repo/application.yml`
- `config-repo/eureka.yml`
- `config-repo/gateway.yml`
- `config-repo/payment-service.yml`
- `config-repo/reservation-service.yml`
- `config-repo/user-service.yml`
- `config-server/src/main/resources/application.yml`
- `docs/troubleshooting.md`
- `eureka/src/main/resources/application.yml`
- `gateway/src/main/resources/application.yml`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradlew`
- `gradlew.bat`
- `infra/grafana/provisioning/datasources/prometheus.yml`
- `mock-pg/src/main/resources/application.yml`
- `payment-service/src/main/resources/application.yml`
- `reservation-service/src/main/resources/application.yml`
- `user-service/src/main/resources/application.yml`

### youngeun (18개)

- `.gitignore`
- `build.gradle`
- `common-messaging/build.gradle`
- `common-response/build.gradle`
- `common-web/build.gradle`
- `common/build.gradle`
- `config-server/build.gradle`
- `eureka/build.gradle`
- `gateway/build.gradle`
- `gradle/wrapper/gradle-wrapper.jar`
- `gradle/wrapper/gradle-wrapper.properties`
- `gradlew`
- `gradlew.bat`
- `mock-pg/build.gradle`
- `payment-service/build.gradle`
- `reservation-service/build.gradle`
- `settings.gradle`
- `user-service/build.gradle`

## 재현과 한계

- `git fetch origin --prune` 후 위 SHA를 고정했다. 이후 원격 커밋은 이 보고서에 포함되지 않는다.
- `--no-messages` 출력의 첫 줄은 계산된 tree SHA다. 나머지 경로를 충돌 파일로 집계했다.
- dev를 직접 변경하거나 브랜치를 생성하지 않았다.
- 다른 기준 브랜치의 전체 충돌 경로는 `git-evidence.jsonl`에 보존했다.
