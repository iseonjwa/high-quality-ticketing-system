package iseonjwa.user.infrastructure.persistence;

import iseonjwa.user.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

interface SpringDataUserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
}
