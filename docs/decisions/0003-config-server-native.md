# 0003. 같은 저장소의 native Config Server 사용

- 상태: 채택
- 날짜: 2026-10-26
- 결정자: Junhyun An
- 관련 AI 판단: [Config Server와 native 설정 폴더 선택](../ai-judgments/junhyun/2026-10-26-config-server-native.md)

## 맥락

SA는 Config Server를 제외했지만 사용자가 전체 Spring Boot 워크스페이스 구성과 Config Server 추가를 명시했다. 설정 저장 방식은 같은 저장소 폴더와 별도 Git 저장소를 비교했다.

## 결정

config-server 모듈과 config-repo 폴더를 같은 저장소에 둔다. Config Server native backend가 읽으며 Compose에서는 폴더를 읽기 전용으로 마운트한다. Config Server → Eureka → 나머지 앱 순서로 시작하고 Config Client는 필수 설정을 받지 못하면 제한된 재시도 뒤 실패한다.

시작에 필요한 앱 이름·프로필·Config 주소만 앱에 둔다. 나머지는 공통·서비스·프로필별 파일로 공급한다. 실제 비밀값은 클라이언트 환경변수로 주입한다. Config Server 프로세스에는 JWT 키와 DB 비밀번호를 전달하지 않는다.

## 검토한 대안

- Config Server 제외: 사용자가 추가를 지시했다.
- 별도 Git 설정 저장소: 사용자가 같은 저장소의 폴더를 선택했다. 저장소 생성·접근 정보 없이 개발할 수 있다.

## 결과

설정을 중앙에서 공급하는 동작까지 로컬에서 확인한다. native backend는 Git backend가 아니며 설정 변경 이력은 현재 저장소에서 관리한다. 런타임 refresh·Bus는 제공하지 않으므로 클라이언트 설정 변경 후 해당 앱을 재시작한다. 팀 전체 합의로 표기하지 않는다.
