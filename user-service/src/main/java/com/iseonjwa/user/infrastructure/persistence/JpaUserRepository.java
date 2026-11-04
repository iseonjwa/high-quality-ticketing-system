package com.iseonjwa.user.infrastructure.persistence;

import com.iseonjwa.common.response.BusinessException;
import com.iseonjwa.user.domain.User;
import com.iseonjwa.user.domain.UserErrorCode;
import com.iseonjwa.user.domain.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JpaUserRepository implements UserRepository {
    private final SpringDataUserRepository repository;

    public JpaUserRepository(SpringDataUserRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<User> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public Optional<User> findByIdForUpdate(Long id) {
        return repository.findByIdForUpdate(id);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return repository.findByEmail(email);
    }

    // Note: users에서 저장 중 날 수 있는 무결성 위반은 email UNIQUE뿐이라 모두 이메일 중복으로 바꾼다.
    // 제약이 늘면 제약 이름으로 나눠야 한다.
    @Override
    public User save(User user) {
        try {
            return repository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(UserErrorCode.EMAIL_DUPLICATED);
        }
    }

    @Override
    public void recordLoginFailure(Long userId, int maxFailures) {
        repository.recordLoginFailure(userId, maxFailures);
    }

    @Override
    public void resetLoginFailures(Long userId) {
        repository.resetLoginFailures(userId);
    }

    @Override
    public void updatePassword(Long userId, String passwordHash, boolean changeRequired, Long actorId) {
        repository.updatePassword(userId, passwordHash, changeRequired, actorId);
    }
}
