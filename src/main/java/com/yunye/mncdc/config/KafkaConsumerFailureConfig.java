package com.yunye.mncdc.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.service.ApplicationShutdownHandler;
import com.yunye.mncdc.service.CdcMessageParseException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Slf4j
@Configuration
public class KafkaConsumerFailureConfig {

    @Bean
    public FixedBackOff kafkaConsumerRetryBackOff(MiniCdcProperties properties) {
        int maxAttempts = resolvedMaxAttempts(properties);
        return new FixedBackOff(
                resolvedRetryBackoffMillis(properties),
                maxAttempts - 1L
        );
    }

    @Bean
    public ConsumerRecordRecoverer kafkaConsumerRecoverer(
            ObjectMapper objectMapper,
            ApplicationShutdownHandler shutdownHandler,
            MiniCdcProperties properties) {
        return (ConsumerRecord<?, ?> record, Exception exception) -> {
            int maxAttempts = resolvedMaxAttempts(properties);
            log.error(
                    "Redis sync consumer exhausted retries transactionId={} topic={} partition={} offset={} attempts={}/{} manualInterventionRequired=true applicationExit=true",
                    extractTransactionId(objectMapper, record != null ? record.value() : null),
                    record != null ? record.topic() : "UNKNOWN",
                    record != null ? record.partition() : -1,
                    record != null ? record.offset() : -1,
                    maxAttempts,
                    maxAttempts,
                    exception
            );
            shutdownHandler.exit(1);
        };
    }

    @Bean
    public CommonErrorHandler kafkaConsumerErrorHandler(
            ConsumerRecordRecoverer recoverer,
            FixedBackOff backOff) {
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(CdcMessageParseException.class);
        errorHandler.setAckAfterHandle(false);
        return errorHandler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> kafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            CommonErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.getContainerProperties().setDeliveryAttemptHeader(true);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    private String extractTransactionId(ObjectMapper objectMapper, Object payload) {
        if (payload == null) {
            return "UNKNOWN";
        }
        try {
            JsonNode root;
            if (payload instanceof byte[] bytes) {
                root = objectMapper.readTree(bytes);
            } else {
                root = objectMapper.readTree(String.valueOf(payload));
            }
            return root.path("transactionId").asText("UNKNOWN");
        } catch (Exception exception) {
            return "UNKNOWN";
        }
    }

    private int resolvedMaxAttempts(MiniCdcProperties properties) {
        if (properties == null || properties.getKafka() == null || properties.getKafka().getConsumer() == null) {
            return 4;
        }
        return Math.max(1, properties.getKafka().getConsumer().getMaxAttempts());
    }

    private long resolvedRetryBackoffMillis(MiniCdcProperties properties) {
        if (properties == null
                || properties.getKafka() == null
                || properties.getKafka().getConsumer() == null
                || properties.getKafka().getConsumer().getRetryBackoff() == null) {
            return 1000L;
        }
        return Math.max(0L, properties.getKafka().getConsumer().getRetryBackoff().toMillis());
    }
}
