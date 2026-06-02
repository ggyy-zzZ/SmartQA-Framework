package com.qa.demo.qa.config.store;

import com.qa.demo.qa.config.QaAssistantProperties;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * assistant 库 JDBC 连接（与 {@link com.qa.demo.qa.learning.SyncEntityStateService} 一致）。
 */
public abstract class AssistantStoreSupport {

    protected final QaAssistantProperties properties;

    protected AssistantStoreSupport(QaAssistantProperties properties) {
        this.properties = properties;
    }

    protected Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
                properties.getMysqlUrl(),
                properties.getMysqlUsername(),
                properties.getMysqlPassword()
        );
    }

    protected String scopeOrDefault(String scope) {
        if (scope == null || scope.isBlank()) {
            return properties.getConfigScope();
        }
        return scope.trim();
    }
}
