# 배포 서버 단계 측정의 단계 정의다. build-images.sh와 deploy-run.sh가 읽는다(source).
# 단계마다 덮어쓰기 파일 overlays/<단계>.yml이 하나씩 있다. 코드가 바뀌는 단계만 이미지가 다르다.

# 측정 순서(STEPS.md 단계 표와 같다)
STEP_ORDER="base g1 r1 p4a p4b p4c f pre"

# 단계 → 이미지 태그
declare -A STEP_IMAGE=([base]=base [g1]=base [r1]=base [p4a]=p4a [p4b]=p4b [p4c]=p4c [f]=f [pre]=pre)

# 이미지 태그 → 측정한 커밋. 이미지 라벨(k6steps.commit)에 남기고, 측정 전에 서버의 이미지와 맞는지 확인한다
declare -A IMAGE_COMMIT=([base]=6704969 [p4a]=50a365d [p4b]=d44c729 [p4c]=d3820f5 [f]=5e9e42a [pre]=660abb06)
IMAGE_TAGS="base p4a p4b p4c f pre"

IMAGE_REPO=iseonjwa-k6steps/reservation-service
