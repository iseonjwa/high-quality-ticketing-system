package iseonjwa.common.persistence;

import java.util.Optional;

@FunctionalInterface
public interface CurrentActorProvider {
    // 사용자 요청은 사용자 ID, Kafka 소비자·스케줄러는 empty를 반환한다.
    Optional<Long> currentActorId();
}
