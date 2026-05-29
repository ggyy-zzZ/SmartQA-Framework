package com.qa.demo.qa.cdc;

import com.qa.demo.qa.config.QaAssistantProperties;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Debezium Engine 启动器。
 *
 * 嵌入式 Debezium 解析 MySQL Binlog，将变更事件发送到 Kafka。
 */
@Component
public class DebeziumEngineRunner {

    private static final Logger log = LoggerFactory.getLogger(DebeziumEngineRunner.class);

    private final QaAssistantProperties props;
    private final CdcKafkaProducer cdcKafkaProducer;

    private DebeziumEngine<ChangeEvent<String, String>> engine;
    private ExecutorService executorService;

    public DebeziumEngineRunner(QaAssistantProperties props, CdcKafkaProducer cdcKafkaProducer) {
        this.props = props;
        this.cdcKafkaProducer = cdcKafkaProducer;
    }

    @PostConstruct
    public void start() {
        if (!props.isCdcEnabled()) {
            log.info("[CDC] CDC is disabled, skipping Debezium Engine startup");
            return;
        }

        log.info("[CDC] Starting Debezium Engine...");

        // 构建 Debezium 配置
        Properties debeziumProps = buildDebeziumProperties();

        // 创建 Debezium Engine，使用 JSON 序列化
        engine = DebeziumEngine.create(Json.class)
                .using(debeziumProps)
                .notifying((events, committer) -> {
                    for (var event : events) {
                        try {
                            String topic = event.destination();
                            String key = event.key();
                            String value = event.value();

                            if (value == null) {
                                log.debug("[CDC] Received tombstone event for topic: {}", topic);
                                committer.markProcessed(event);
                                continue;
                            }

                            cdcKafkaProducer.send(topic, key, value);
                            committer.markProcessed(event);

                            log.debug("[CDC] Forwarded event to Kafka topic: {}, key: {}", topic, key);
                        } catch (Exception e) {
                            log.error("[CDC] Error handling CDC event", e);
                        }
                    }
                })
                .using((success, message, error) -> {
                    if (error != null) {
                        log.error("[CDC] Debezium Engine terminated with error: {}", message, error);
                    } else {
                        log.info("[CDC] Debezium Engine stopped: {}", message);
                    }
                })
                .build();

        // 启动 Engine（异步）
        executorService = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "debezium-engine");
            t.setDaemon(true);
            return t;
        });
        executorService.execute(engine);

        log.info("[CDC] Debezium Engine started successfully");
    }

    @PreDestroy
    public void stop() {
        log.info("[CDC] Stopping Debezium Engine...");

        if (engine != null) {
            try {
                engine.close();
                if (executorService != null) {
                    executorService.shutdown();
                    if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                        executorService.shutdownNow();
                    }
                }
                log.info("[CDC] Debezium Engine stopped gracefully");
            } catch (Exception e) {
                log.error("[CDC] Error stopping Debezium Engine", e);
            }
        }
    }

    /**
     * 构建 Debezium 配置为 Properties。
     */
    private Properties buildDebeziumProperties() {
        String dbUrl = props.getBusinessMysqlUrl();
        String host = extractHost(dbUrl);
        int port = extractPort(dbUrl);
        String database = extractDatabase(dbUrl);
        String tableIncludeList = props.getCdcTableIncludeList().replace(".", "\\.");

        Properties debeziumProps = new Properties();

        // 基础配置
        debeziumProps.setProperty("name", "demo-cdc-connector");
        debeziumProps.setProperty("connector.class", "io.debezium.connector.mysql.MySqlConnector");

        // MySQL 连接配置
        debeziumProps.setProperty("database.hostname", host);
        debeziumProps.setProperty("database.port", String.valueOf(port));
        debeziumProps.setProperty("database.user", props.getBusinessMysqlUsername());
        debeziumProps.setProperty("database.password", props.getBusinessMysqlPassword());
        debeziumProps.setProperty("database.server.id", "85744");

        // 监听范围
        debeziumProps.setProperty("database.include.list", props.getCdcDatabaseIncludeList());
        debeziumProps.setProperty("table.include.list", props.getCdcTableIncludeList());

        // 行为配置
        debeziumProps.setProperty("include.schema.change", "false");
        debeziumProps.setProperty("snapshot.mode", "when_needed");
        debeziumProps.setProperty("snapshot.locking.mode", "minimal");

        // 事件格式
        debeziumProps.setProperty("topic.prefix", "demo");
        debeziumProps.setProperty("format", "json");
        debeziumProps.setProperty("time.precision.mode", "connect");
        // 扁平 JSON（无 schema 信封），便于 Consumer 直接读 op/after
        debeziumProps.setProperty("key.converter", "org.apache.kafka.connect.json.JsonConverter");
        debeziumProps.setProperty("value.converter", "org.apache.kafka.connect.json.JsonConverter");
        debeziumProps.setProperty("key.converter.schemas.enable", "false");
        debeziumProps.setProperty("value.converter.schemas.enable", "false");
        // JDBC 时区配置（解决 MySQL connector 时区识别问题）
        debeziumProps.setProperty("database.connectionTimeZone", "UTC");
        // Offset 存储配置（使用 Kafka 而非文件系统，避免权限问题）
        debeziumProps.setProperty("offset.storage", "org.apache.kafka.connect.storage.KafkaOffsetBackingStore");
        debeziumProps.setProperty("offset.storage.topic", "demo-offsets");
        debeziumProps.setProperty("offset.storage.replication.factor", "1");
        debeziumProps.setProperty("offset.storage.partitions", "1");
        debeziumProps.setProperty("bootstrap.servers", props.getCdcKafkaBootstrapServers());
        // Schema history（与 Kafka offset 配套，Debezium 2.4 必填）
        debeziumProps.setProperty("schema.history.internal", "io.debezium.storage.kafka.history.KafkaSchemaHistory");
        debeziumProps.setProperty("schema.history.internal.kafka.topic", "demo-schema-history");
        debeziumProps.setProperty("schema.history.internal.kafka.bootstrap.servers", props.getCdcKafkaBootstrapServers());

        log.info("[CDC] Debezium config: db={}, tables={}, server.id=85744", database, tableIncludeList);

        return debeziumProps;
    }

    private String extractHost(String jdbcUrl) {
        int start = jdbcUrl.indexOf("://") + 3;
        int end = jdbcUrl.indexOf(":", start);
        if (end == -1) end = jdbcUrl.indexOf("/", start);
        return jdbcUrl.substring(start, end);
    }

    private int extractPort(String jdbcUrl) {
        int start = jdbcUrl.indexOf("://") + 3;
        int colon = jdbcUrl.indexOf(":", start);
        int slash = jdbcUrl.indexOf("/", colon);
        return Integer.parseInt(jdbcUrl.substring(colon + 1, slash));
    }

    private String extractDatabase(String jdbcUrl) {
        int lastSlash = jdbcUrl.lastIndexOf("/");
        int question = jdbcUrl.indexOf("?", lastSlash);
        return question > 0 ? jdbcUrl.substring(lastSlash + 1, question) : jdbcUrl.substring(lastSlash + 1);
    }
}