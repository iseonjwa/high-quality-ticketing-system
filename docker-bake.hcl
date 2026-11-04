# 배포 이미지 8개(앱 7개 + 프론트 nginx)를 한 번에 빌드하는 정의다. .github/workflows/images.yml이 쓴다.
#
# Note: 앱 7개는 같은 루트 Dockerfile을 쓰고 APP_MODULE 인자만 다르다. builder 단계(gradlew bootJar)는
# APP_MODULE과 무관해서, 한 번의 bake 실행 안에서는 BuildKit이 같은 단계로 알아보고 한 번만 돌린다.
# 앱마다 따로 빌드(워크플로 매트릭스)하면 같은 Gradle 빌드를 7번 한다. 그래서 bake 한 번으로 묶었다.
#
# Note: 항상 -f docker-bake.hcl로 이 파일만 읽게 한다. 파일을 고르지 않으면 bake가 compose.yml도 읽어서
# 같은 이름의 compose 서비스(gateway 등)를 이 타깃과 합친다.
#
# 로컬에서 정의만 확인: docker buildx bake -f docker-bake.hcl --print
# 로컬에서 하나만 빌드(맥은 arm64로): TAG=local docker buildx bake -f docker-bake.hcl gateway --set '*.platform=linux/arm64' --load

# 이미지 저장소 주소. 이미지 이름은 ghcr.io/iseonjwa/<서비스>다.
variable "REGISTRY" {
  default = "ghcr.io/iseonjwa"
}

# 이미지 태그. 워크플로가 main 커밋 SHA(40자)를 넣는다. Jenkins는 이 SHA로 배포하고 되돌린다.
variable "TAG" {
  default = "local"
}

# 모든 이미지에 붙는 공통 설정
target "_common" {
  # 배포 EC2가 x86(amd64)이다. 맥(arm64)에서 만든 이미지는 EC2에서 돌지 않는다.
  platforms = ["linux/amd64"]
  labels = {
    # GHCR이 이 라벨로 이미지를 저장소에 연결한다(패키지 화면에 저장소 링크와 README가 붙는다).
    "org.opencontainers.image.source"   = "https://github.com/iseonjwa/high-quality-ticketing-system"
    "org.opencontainers.image.revision" = TAG
  }
}

# 앱 7개. matrix가 서비스 이름마다 타깃 하나씩을 만든다(타깃 이름 = 서비스 이름).
target "app" {
  name     = app
  matrix   = {
    app = ["config-server", "eureka", "gateway", "user-service", "reservation-service", "payment-service", "mock-pg"]
  }
  inherits   = ["_common"]
  context    = "."
  dockerfile = "Dockerfile"
  args       = { APP_MODULE = app }
  tags       = ["${REGISTRY}/${app}:${TAG}"]
}

# 프론트: frontend/Dockerfile이 Node로 빌드하고 nginx 이미지에 담는다(ISE-109, ADR 0040).
target "frontend" {
  inherits = ["_common"]
  context  = "frontend"
  tags     = ["${REGISTRY}/frontend:${TAG}"]
}

group "default" {
  targets = ["config-server", "eureka", "gateway", "user-service", "reservation-service", "payment-service", "mock-pg", "frontend"]
}
