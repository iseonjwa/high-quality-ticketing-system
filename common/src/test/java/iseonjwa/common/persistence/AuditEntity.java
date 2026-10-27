package iseonjwa.common.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_sample")
public class AuditEntity extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    protected AuditEntity() {}

    AuditEntity(String name) {
        this.name = name;
    }

    Long getId() {
        return id;
    }

    void rename(String name) {
        this.name = name;
    }

    void delete(Instant at, Long actorId) {
        markDeleted(at, actorId);
    }
}
