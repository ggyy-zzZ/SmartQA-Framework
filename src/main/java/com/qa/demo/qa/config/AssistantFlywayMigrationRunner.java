package com.qa.demo.qa.config;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 可选：启动时对 {@link QaAssistantProperties#getMysqlUrl()} 执行 Flyway，
 * 脚本位于 {@code classpath:db/migration/assistant}。默认关闭，避免无 MySQL 环境启动失败。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AssistantFlywayMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AssistantFlywayMigrationRunner.class);

    private final QaAssistantProperties properties;

    public AssistantFlywayMigrationRunner(QaAssistantProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isMysqlEnabled() || !properties.isFlywayEnabled()) {
            return;
        }
        HikariDataSource ds = new HikariDataSource();
        try {
            ds.setJdbcUrl(properties.getMysqlUrl());
            ds.setUsername(properties.getMysqlUsername());
            ds.setPassword(properties.getMysqlPassword());
            ds.setMaximumPoolSize(1);
            MigrateResult result = Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration/assistant")
                    .baselineOnMigrate(true)
                    .load()
                    .migrate();
            log.info(
                    "Assistant Flyway migrate OK: {} migrations applied, schema version={}",
                    result.migrationsExecuted,
                    result.targetSchemaVersion
            );
        } catch (Exception e) {
            log.error("Assistant Flyway migrate failed (check MySQL assistant DB and qa.assistant.flyway-enabled): {}", e.toString());
        } finally {
            ds.close();
        }
    }
}
