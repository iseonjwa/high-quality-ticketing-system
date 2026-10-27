package com.iseonjwa.common.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = JpaAuditingTest.Config.class)
class JpaAuditingTest {
    @PersistenceContext
    EntityManager entityManager;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    MutableActorProvider actors;

    @BeforeEach
    void 시스템_사용자로_초기화한다() {
        actors.userId = null;
    }

    @Test
    void 사용자_생성_수정과_시스템_수정의_감사값을_저장한다() {
        var tx = new TransactionTemplate(transactionManager);
        actors.userId = 11L;
        Long id = tx.execute(status -> {
            var entity = new AuditEntity("first");
            entityManager.persist(entity);
            entityManager.flush();
            assertNotNull(entity.getCreatedAt());
            assertEquals(11L, entity.getCreatedBy());
            assertEquals(11L, entity.getUpdatedBy());
            return entity.getId();
        });
        actors.userId = 22L;
        tx.executeWithoutResult(status -> entityManager.find(AuditEntity.class, id).rename("second"));
        tx.executeWithoutResult(status -> {
            var entity = entityManager.find(AuditEntity.class, id);
            assertEquals(11L, entity.getCreatedBy());
            assertEquals(22L, entity.getUpdatedBy());
            assertNotNull(entity.getUpdatedAt());
        });
        actors.userId = null;
        tx.executeWithoutResult(status -> entityManager.find(AuditEntity.class, id).rename("system"));
        tx.executeWithoutResult(status -> {
            var entity = entityManager.find(AuditEntity.class, id);
            assertNull(entity.getUpdatedBy());
            assertEquals(11L, entity.getCreatedBy());
        });
    }

    @Test
    void 시스템_생성과_명시적_논리삭제를_지원하고_행을_자동으로_숨기지_않는다() {
        var tx = new TransactionTemplate(transactionManager);
        var deletedAt = Instant.parse("2026-10-26T10:00:00Z");
        Long id = tx.execute(status -> {
            var entity = new AuditEntity("system");
            entityManager.persist(entity);
            entityManager.flush();
            assertNull(entity.getCreatedBy());
            assertNull(entity.getUpdatedBy());
            assertNull(entity.getDeletedAt());
            entity.delete(deletedAt, 33L);
            return entity.getId();
        });
        tx.executeWithoutResult(status -> {
            var entity = entityManager.find(AuditEntity.class, id);
            assertNotNull(entity);
            assertEquals(deletedAt, entity.getDeletedAt());
            assertEquals(33L, entity.getDeletedBy());
        });
    }

    static class MutableActorProvider implements CurrentActorProvider {
        Long userId;

        public Optional<Long> currentActorId() {
            return Optional.ofNullable(userId);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CommonJpaConfiguration.class)
    static class Config {
        @Bean
        MutableActorProvider actors() {
            return new MutableActorProvider();
        }

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:common_audit;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(
                DataSource dataSource, org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.iseonjwa.common.persistence");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
                    "hibernate.resource.beans.container", new org.springframework.orm.jpa.hibernate.SpringBeanContainer(beans)));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }
    }
}
