# config-repo

Config 서버(native)가 읽는 설정 파일이다 (SA MASTER 9-6). 서비스는 기동할 때 `spring.config.import=configserver:`로 이 파일들을 받아 간다.

| 파일 | 담는 것 |
|---|---|
| `application.yml` | 모든 서비스 공통: Eureka 주소, Kafka, Actuator, 추적, 공통 숫자 기준 |
| `application-{프로필}.yml` | 프로필별 공통 차이. `local`은 IntelliJ로 띄울 때 localhost 주소 |
| `{서비스}.yml` | 서비스 전용 설정 |
| `{서비스}-{프로필}.yml` | 서비스·프로필 조합의 차이 |

우선순위는 뒤가 이긴다: `application.yml` < `application-{프로필}.yml` < `{서비스}.yml` < `{서비스}-{프로필}.yml`.

## 규칙
- 비밀값은 넣지 않는다. `${USER_DB_PASSWORD}`처럼 자리표시자만 쓰고 실제 값은 `.env`로 컨테이너 환경 변수에 넣는다.
- 프로필은 `local`·`test`·`loadtest`·`prod` 네 가지다. `test`는 Config 서버를 쓰지 않으므로 여기에 파일을 두지 않는다.
- 값을 바꾸면 서비스를 재시작해야 반영된다. 측정 중 자주 바꾸는 값만 `@RefreshScope`로 둔다.
- 확인: `curl localhost:8888/user-service/local`로 서비스가 받을 설정을 볼 수 있다(로컬 compose에서만 포트가 열린다).
