package iseonjwa.user.infrastructure.persistence;

import iseonjwa.user.domain.*;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class JpaUserRepository implements UserRepository {
    private final SpringDataUserRepository repository;
    public JpaUserRepository(SpringDataUserRepository repository) { this.repository = repository; }
    public Optional<User> findById(Long id) { return repository.findById(id); }
    public Optional<User> findByEmail(String email) { return repository.findByEmail(email); }
    public User save(User user) { return repository.saveAndFlush(user); }
}
