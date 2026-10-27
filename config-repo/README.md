# config-repo

Config 서버가 읽는 설정 파일이다 (SA 9-6). 서비스는 기동할 때 `spring.application.name`과 활성 프로필로 아래 파일을 받아 간다.

| 파일 | 누가 읽나 |
|---|---|
| `application.yml` | 모든 Config 클라이언트 (Eureka 주소, Actuator, 추적) |
| `application-{프로필}.yml` | 그 프로필을 켠 모든 클라이언트. 예: `application-loadtest.yml` |
| `{서비스}.yml` | 그 서비스. 예: `gateway.yml`, `user-service.yml` |
| `{서비스}-{프로필}.yml` | 그 서비스의 그 프로필 |

- 비밀값은 넣지 않는다. `${JWT_SECRET}`처럼 자리표시자만 쓰고, 실제 값은 저장소 루트 `.env`로 각 앱에 넣는다.
- 바꾼 설정은 서비스를 재시작하면 반영된다.
- 확인: `curl http://localhost:8888/gateway/default`
