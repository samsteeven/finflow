package com.finflow.security.jdbc;

import com.finflow.security.TenantContext;
import com.finflow.security.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;

/**
 * Équivalent JDBC de {@code TenantAwareJpaTransactionManager} pour les services sans JPA
 * (event store de Payment) : le tenant est posé au début de chaque transaction, pour cette
 * transaction seulement (ADR-005).
 */
public class TenantAwareDataSourceTransactionManager extends DataSourceTransactionManager {

    public TenantAwareDataSourceTransactionManager(DataSource dataSource) {
        super(dataSource);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition)
            throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        String tenant = TenantContext.current().map(TenantId::toString).orElse("");
        try (PreparedStatement statement = con.prepareStatement("select set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenant);
            statement.execute();
        }
    }
}
