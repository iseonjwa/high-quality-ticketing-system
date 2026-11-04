package com.iseonjwa.user.domain;

import java.util.Optional;

public interface UserRepository {
    Optional<User> findById(Long id);

    Optional<User> findByIdForUpdate(Long id);

    Optional<User> findByEmail(String email);

    // 같은 이메일이 이미 있으면 EMAIL_DUPLICATED로 실패한다. 동시 가입도 DB 유니크 제약이 하나만 통과시킨다.
    User save(User user);

    // 실패 횟수를 1 올리고 maxFailures에 도달하면 잠근다.
    void recordLoginFailure(Long userId, int maxFailures);

    // 실패 횟수를 0으로 되돌리고 잠금을 푼다.
    void resetLoginFailures(Long userId);

    // 비밀번호를 바꾸면서 실패 횟수와 잠금도 함께 푼다. actorId는 변경한 사용자다.
    void updatePassword(Long userId, String passwordHash, boolean changeRequired, Long actorId);
}
