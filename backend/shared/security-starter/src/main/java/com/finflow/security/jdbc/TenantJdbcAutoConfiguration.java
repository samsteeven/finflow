package com.finflow.security.jdbc;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionManager;

/**
 * Gestionnaire de transactions JDBC qui pose le tenant, pour les services sans JPA. Dans un
 * service JPA, {@code TenantJpaAutoConfiguration} passe avant et celui-ci s'efface.
 */
@AutoConfiguration(
        afterName = {
                "com.finflow.security.jpa.TenantJpaAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"},
        beforeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration")
@ConditionalOnClass({DataSource.class, DataSourceTransactionManager.class})
@ConditionalOnSingleCandidate(DataSource.class)
public class TenantJdbcAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TransactionManager.class)
    TenantAwareDataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new TenantAwareDataSourceTransactionManager(dataSource);
    }
}
