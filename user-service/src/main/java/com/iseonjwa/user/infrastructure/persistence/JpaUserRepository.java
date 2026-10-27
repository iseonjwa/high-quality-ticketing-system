package com.iseonjwa.user.infrastructure.persistence;

import com.iseonjwa.user.domain.User;
import com.iseonjwa.user.domain.UserRepository;
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

    @Override
    public User save(User user) {
        return repository.saveAndFlush(user);
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
