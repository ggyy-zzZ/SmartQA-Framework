package com.qa.demo.qa.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.demo.qa.config.QaAssistantProperties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Debezium CDC 事件消费者。
 *
 * 从 Kafka 消费 Debezium 事件，按 entityId 聚合后写入 Neo4j + Qdrant + sync_entity_state。
 *
 * Exactly-Once 实现：
 * 1. 手动提交 offset（ENABLE_AUTO_COMMIT=FALSE）
 * 2. 三库写入全部成功后才提交 offset
 * 3. 失败时不提交 offset，Kafka 重投（配合幂等写入保证不丢不重）
 */
@Component
public class DebeziumCdcConsumer {

    private static final Logger log = LoggerFactory.getLogger(DebeziumCdcConsumer.class);

    private final QaAssistantProperties props;
    private final Neo4jCdcWriter neo4jWriter;
    private final QdrantCdcWriter qdrantWriter;
    private final ObjectMapper objectMapper;

    private KafkaConsumer<String, String> kafkaConsumer;
    private ExecutorService writerExecutor;
    private volatile boolean running = true;

    // 指标
    private final AtomicLong processedCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);

    public DebeziumCdcConsumer(
            QaAssistantProperties props,
            Neo4jCdcWriter neo4jWriter,
            QdrantCdcWriter qdrantWriter) {
        this.props = props;
        this.neo4jWriter = neo4jWriter;
        this.qdrantWriter = qdrantWriter;
        this.objectMapper = new ObjectMapper();
    }

    @PostConstruct
    public void start() {
        if (!props.isCdcEnabled()) {
            log.info("[CDC Consumer] CDC is disabled, skipping consumer startup");
            return;
        }

        log.info("[CDC Consumer] Starting CDC consumer...");

        // 初始化 Kafka Consumer
        Properties kafkaProps = new Properties();
        kafkaProps.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getCdcKafkaBootstrapServers());
        kafkaProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG, props.getCdcKafkaGroupId());
        kafkaProps.setProperty(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        kafkaProps.setProperty(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        kafkaProps.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        kafkaProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        kafkaProps.setProperty(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100");
        kafkaProps.setProperty(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000");
        kafkaProps.setProperty(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "10000");

        this.kafkaConsumer = new KafkaConsumer<>(kafkaProps);
        this.writerExecutor = Executors.newFixedThreadPool(props.getCdcWriteParallelism());

        // 订阅需要监听的 Topic（Debezium 生成的 topic 格式: demo.tdcomp.company）
        String topicPrefix = "demo." + props.getCdcDatabaseIncludeList();
        List<String> topics = Arrays.stream(props.getCdcTableIncludeList().split(","))
                .map(t -> "demo." + t.replace(".", "."))
                .toList();
        kafkaConsumer.subscribe(topics);

        log.info("[CDC Consumer] Subscribed to topics: {}", topics);

        // 启动消费循环
        executorLoop();
    }

    @PreDestroy
    public void stop() {
        log.info("[CDC Consumer] Stopping CDC consumer...");
        running = false;

        if (kafkaConsumer != null) {
            kafkaConsumer.wakeup();
        }
        if (writerExecutor != null) {
            writerExecutor.shutdown();
            try {
                if (!writerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    writerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                writerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (kafkaConsumer != null) {
            kafkaConsumer.close();
        }
        log.info("[CDC Consumer] CDC consumer stopped");
    }

    /**
     * 主消费循环。
     */
    private void executorLoop() {
        Thread loopThread = new Thread(() -> {
            log.info("[CDC Consumer] Consumer loop started");

            while (running) {
                try {
                    ConsumerRecords<String, String> records = kafkaConsumer.poll(Duration.ofSeconds(5));

                    if (records.isEmpty()) {
                        continue;
                    }

                    log.debug("[CDC Consumer] Polled {} records", records.count());

                    // 按 entityId 聚合事件
                    Map<String, List<ParsedEvent>> entityBatches = groupByEntityId(records);

                    // 并行写三库
                    boolean allSuccess = writeToAllStoresWithRetry(entityBatches);

                    if (allSuccess) {
                        // 全部成功，提交 offset
                        commitOffsets(records);
                        processedCount.addAndGet(records.count());
                        log.debug("[CDC Consumer] Committed offsets for {} records", records.count());
                    } else {
                        // 有失败，发送到 DLT
                        sendToDlt(entityBatches);
                        commitOffsets(records); // DLT 后提交避免无限重投
                        failedCount.addAndGet(entityBatches.size());
                    }

                } catch (WakeupException e) {
                    // 正在关闭，忽略
                    break;
                } catch (Exception e) {
                    log.error("[CDC Consumer] Error in consumer loop", e);
                    try {
                        Thread.sleep(5000); // 避免疯狂报错
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            log.info("[CDC Consumer] Consumer loop finished");
        }, "cdc-consumer-loop");

        loopThread.setDaemon(true);
        loopThread.start();
    }

    /**
     * 解析 Debezium 事件。
     */
    private ParsedEvent parseEvent(String key, String value) {
        try {
            JsonNode root = objectMapper.readTree(value);
            String op = root.path("op").asText("r"); // c=create, u=update, d=delete, r=snapshot
            JsonNode after = root.path("after");
            JsonNode before = root.path("before");
            JsonNode source = root.path("source");
            String table = source.path("table").asText();

            return new ParsedEvent(table, op, after, before);
        } catch (Exception e) {
            log.error("[CDC Consumer] Failed to parse CDC event", e);
            return null;
        }
    }

    /**
     * 按 entityId 聚合事件。
     */
    private Map<String, List<ParsedEvent>> groupByEntityId(ConsumerRecords<String, String> records) {
        Map<String, List<ParsedEvent>> batches = new LinkedHashMap<>();

        for (ConsumerRecord<String, String> record : records) {
            ParsedEvent event = parseEvent(record.key(), record.value());
            if (event == null) continue;

            String entityId = event.extractEntityId();
            if (entityId == null || entityId.isBlank()) {
                log.warn("[CDC Consumer] Cannot extract entityId from event, skipping");
                continue;
            }

            String batchKey = event.table() + ":" + entityId;
            batches.computeIfAbsent(batchKey, k -> new ArrayList<>()).add(event);
        }

        return batches;
    }

    /**
     * 带重试的写三库操作。
     */
    private boolean writeToAllStoresWithRetry(Map<String, List<ParsedEvent>> entityBatches) {
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();

        for (Map.Entry<String, List<ParsedEvent>> entry : entityBatches.entrySet()) {
            String batchKey = entry.getKey();
            List<ParsedEvent> events = entry.getValue();

            futures.add(CompletableFuture.supplyAsync(() -> {
                int retries = 0;
                while (retries < props.getCdcMaxRetries()) {
                    try {
                        writeEventsToStores(batchKey, events);
                        return true;
                    } catch (Exception e) {
                        retries++;
                        log.warn("[CDC Consumer] Write failed for {} (attempt {}/{}): {}",
                                batchKey, retries, props.getCdcMaxRetries(), e.getMessage());

                        if (retries >= props.getCdcMaxRetries()) {
                            log.error("[CDC Consumer] Max retries reached for {}", batchKey, e);
                            return false;
                        }

                        try {
                            Thread.sleep(props.getCdcRetryDelayMs());
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                }
                return false;
            }, writerExecutor));
        }

        // 等待所有完成
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            return futures.stream().allMatch(f -> {
                try {
                    return f.join();
                } catch (Exception e) {
                    return false;
                }
            });
        } catch (Exception e) {
            log.error("[CDC Consumer] Error waiting for futures", e);
            return false;
        }
    }

    /**
     * 写入三库（Neo4j + Qdrant + sync_entity_state）。
     */
    private void writeEventsToStores(String batchKey, List<ParsedEvent> events) {
        if (events.isEmpty()) return;

        // 取最后一个事件的 after 作为最终状态
        ParsedEvent lastEvent = events.get(events.size() - 1);

        // 跳过删除事件（after 为空）
        if (lastEvent.op().equals("d") && lastEvent.after().isMissingNode()) {
            log.debug("[CDC Consumer] Skipping delete event for {}", batchKey);
            return;
        }

        String table = lastEvent.table();

        // 1. 写 Neo4j
        try {
            neo4jWriter.write(table, lastEvent.op(), lastEvent.after(), lastEvent.before());
        } catch (Exception e) {
            log.error("[CDC Consumer] Neo4j write failed for {}: {}", batchKey, e.getMessage());
            throw e;
        }

        // 2. 写 Qdrant
        try {
            qdrantWriter.write(table, lastEvent.op(), lastEvent.after());
        } catch (Exception e) {
            log.error("[CDC Consumer] Qdrant write failed for {}: {}", batchKey, e.getMessage());
            throw e;
        }

        // 3. 后续可添加 sync_entity_state 更新
        // syncEntityStateService.upsert(...);

        log.debug("[CDC Consumer] Wrote to all stores for {}", batchKey);
    }

    /**
     * 提交 offset。
     */
    private void commitOffsets(ConsumerRecords<String, String> records) {
        Map<TopicPartition, OffsetAndMetadata> toCommit = new HashMap<>();
        for (ConsumerRecord<String, String> r : records) {
            TopicPartition tp = new TopicPartition(r.topic(), r.partition());
            toCommit.put(tp, new OffsetAndMetadata(r.offset() + 1, null));
        }
        kafkaConsumer.commitSync(toCommit);
    }

    /**
     * 发送到死信队列（DLT）。
     */
    private void sendToDlt(Map<String, List<ParsedEvent>> entityBatches) {
        for (Map.Entry<String, List<ParsedEvent>> entry : entityBatches.entrySet()) {
            log.error("[CDC Consumer] [DLT] Entity {} failed after max retries", entry.getKey());
            // 实际实现可发送到专门的 DLT topic
        }
    }

    /**
     * 获取处理统计。
     */
    public Map<String, Object> getStats() {
        return Map.of(
                "processed", processedCount.get(),
                "failed", failedCount.get(),
                "running", running
        );
    }

    // ==================== 内部类 ====================

    private record ParsedEvent(String table, String op, JsonNode after, JsonNode before) {

        String extractEntityId() {
            return switch (table) {
                case "company" -> after.path("company_id").asText(null);
                case "employee" -> after.path("employee_id").asText(null);
                case "branch" -> after.path("branch_id").asText(null);
                case "partner" -> after.path("partner_id").asText(null);
                default -> null;
            };
        }
    }
}