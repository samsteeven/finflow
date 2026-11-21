package com.finflow.security.jpa;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionManager;

/**
 * Remplace le gestionnaire de transactions JPA de Spring Boot par
 * {@link TenantAwareJpaTransactionManager}. Déclarée avant l'autoconfiguration Hibernate,
 * dont le gestionnaire par défaut ne s'installe qu'en l'absence d'un autre.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration")
@ConditionalOnClass({EntityManagerFactory.class, JpaTransactionManager.class})
public class TenantJpaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TransactionManager.class)
    TenantAwareJpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new TenantAwareJpaTransactionManager(entityManagerFactory);
    }
}
