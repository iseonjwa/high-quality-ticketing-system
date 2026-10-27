package com.iseonjwa.user.infrastructure.persistence;

import com.iseonjwa.user.domain.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

interface SpringDataUserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE users
            SET failed_login_count = failed_login_count + 1,
                locked_at = CASE WHEN failed_login_count + 1 >= :maxFailures
                                 THEN COALESCE(locked_at, now()) ELSE locked_at END
            WHERE id = :id
            """)
    void recordLoginFailure(@Param("id") Long id, @Param("maxFailures") int maxFailures);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE users SET failed_login_count = 0, locked_at = NULL WHERE id = :id
            """)
    void resetLoginFailures(@Param("id") Long id);

    @Transactional
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE users
            SET password_hash = :hash,
                password_change_required = :changeRequired,
                failed_login_count = 0,
                locked_at = NULL,
                updated_at = now(),
                updated_by = :actorId
            WHERE id = :id
            """)
    void updatePassword(@Param("id") Long id, @Param("hash") String hash,
            @Param("changeRequired") boolean changeRequired, @Param("actorId") Long actorId);
}
