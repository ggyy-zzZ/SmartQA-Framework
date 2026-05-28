package com.qa.demo.qa.cdc;

import com.qa.demo.qa.config.QaAssistantProperties;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Properties;
import java.util.concurrent.Future;

/**
 * CDC 事件的 Kafka Producer。
 *
 * Debezium Engine 解析 Binlog 后，调用此类将事件发送到 Kafka Topic。
 * 后续 DebeziumCdcConsumer 从 Kafka 消费这些事件。
 */
@Component
public class CdcKafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(CdcKafkaProducer.class);

    private final QaAssistantProperties props;
    private final KafkaProducer<String, String> producer;

    public CdcKafkaProducer(QaAssistantProperties props) {
        this.props = props;
        this.producer = createProducer();
    }

    private KafkaProducer<String, String> createProducer() {
        Properties kafkaProps = new Properties();
        kafkaProps.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getCdcKafkaBootstrapServers());
        kafkaProps.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        kafkaProps.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        // 可靠性配置
        kafkaProps.setProperty(ProducerConfig.ACKS_CONFIG, "all");          // 所有副本确认
        kafkaProps.setProperty(ProducerConfig.RETRIES_CONFIG, "3");        // 重试次数
        kafkaProps.setProperty(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true"); // 幂等生产者

        // 性能配置
        kafkaProps.setProperty(ProducerConfig.BATCH_SIZE_CONFIG, "16384");
        kafkaProps.setProperty(ProducerConfig.LINGER_MS_CONFIG, "5");
        kafkaProps.setProperty(ProducerConfig.BUFFER_MEMORY_CONFIG, "33554432");

        return new KafkaProducer<>(kafkaProps);
    }

    /**
     * 发送 CDC 事件到 Kafka。
     *
     * @param topic Kafka Topic 名称
     * @param key   消息 Key（通常为表的主键）
     * @param value 消息 Value（Debezium JSON 事件）
     */
    public void send(String topic, String key, String value) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
            Future future = producer.send(record);

            log.debug("[CDC Producer] Sent to topic={}, key={}, partition={}",
                    topic, key, future.isDone());

        } catch (Exception e) {
            log.error("[CDC Producer] Failed to send to topic={}, key={}", topic, key, e);
            throw new RuntimeException("CDC Kafka send failed", e);
        }
    }

    /**
     * 同步发送（等待确认）。
     */
    public void sendSync(String topic, String key, String value) {
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
            producer.send(record).get();  // 阻塞等待确认
            log.debug("[CDC Producer] Sent (sync) to topic={}, key={}", topic, key);
        } catch (Exception e) {
            log.error("[CDC Producer] Failed to send (sync) to topic={}, key={}", topic, key, e);
            throw new RuntimeException("CDC Kafka send failed", e);
        }
    }

    public void close() {
        if (producer != null) {
            producer.flush();
            producer.close();
        }
    }
}