package com.yunye.mncdc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.CdcTransactionEvent;
import com.yunye.mncdc.model.CdcTransactionRow;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class RedisSyncConsumerTest {

    private static final String TOPIC = "user-change-topic";
    private static final int PARTITION = 0;
    private static final long OFFSET = 42L;

    @Mock
    private RedisTransactionApplier redisTransactionApplier;

    @Mock
    private Acknowledgment acknowledgment;

    private MiniCdcProperties properties;

    private RedisSyncConsumer consumer;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        properties = new MiniCdcProperties();
        consumer = new RedisSyncConsumer(objectMapper, redisTransactionApplier, properties);
    }

    @Test
    void acknowledgesWhenRedisApplyReturnsApplied() throws Exception {
        when(redisTransactionApplier.apply(any())).thenReturn(RedisTransactionApplier.ApplyResult.APPLIED);

        consumer.consume(buildRecord(objectMapper.writeValueAsString(sampleTransaction())), 1, acknowledgment);

        verify(acknowledgment).acknowledge();
    }

    @Test
    void acknowledgesWhenRedisApplyReturnsDuplicate() throws Exception {
        when(redisTransactionApplier.apply(any())).thenReturn(RedisTransactionApplier.ApplyResult.DUPLICATE);

        consumer.consume(buildRecord(objectMapper.writeValueAsString(sampleTransaction())), 2, acknowledgment);

        verify(acknowledgment).acknowledge();
    }

    @Test
    void doesNotAcknowledgeWhenRedisApplyFails(CapturedOutput output) throws Exception {
        when(redisTransactionApplier.apply(any())).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> consumer.consume(buildRecord(objectMapper.writeValueAsString(sampleTransaction())), null, acknowledgment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis down");
        verifyNoInteractions(acknowledgment);

        String log = output.getAll();
        assertThat(log).contains("transactionId=mini-user-sync:mysql-bin.000001:345:240");
        assertThat(log).contains("topic=user-change-topic");
        assertThat(log).contains("partition=0");
        assertThat(log).contains("offset=42");
        assertThat(log).contains("attempt=1/4");
    }

    @Test
    void doesNotAcknowledgeWhenPayloadCannotBeParsed() {
        ConsumerRecord<String, String> badRecord = buildRecord("not-json");

        assertThatThrownBy(() -> consumer.consume(badRecord, 1, acknowledgment))
                .isInstanceOf(CdcMessageParseException.class);
        verifyNoInteractions(acknowledgment);
    }

    @Test
    void throwsWhenPayloadJsonIsNull() {
        ConsumerRecord<String, String> record = buildRecord("null");

        assertThatThrownBy(() -> consumer.consume(record, 1, acknowledgment))
                .isInstanceOf(CdcMessageParseException.class);
        verifyNoInteractions(acknowledgment);
    }

    @Test
    void throwsWhenValueIsNull() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(TOPIC, PARTITION, OFFSET, null, null);

        assertThatThrownBy(() -> consumer.consume(record, 1, acknowledgment))
                .isInstanceOf(CdcMessageParseException.class);
        verifyNoInteractions(acknowledgment);
    }

    @Test
    void throwsWhenRedisApplyReturnsNull() throws Exception {
        when(redisTransactionApplier.apply(any())).thenReturn(null);

        assertThatThrownBy(() -> consumer.consume(buildRecord(objectMapper.writeValueAsString(sampleTransaction())), 1, acknowledgment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected Redis apply result: null");
        verifyNoInteractions(acknowledgment);
    }

    private ConsumerRecord<String, String> buildRecord(String payload) {
        return new ConsumerRecord<>(TOPIC, PARTITION, OFFSET, null, payload);
    }

    private CdcTransactionEvent sampleTransaction() {
        return new CdcTransactionEvent(
                "mini-user-sync:mysql-bin.000001:345:240",
                "mini-user-sync",
                "mini",
                "user",
                "mysql-bin.000001",
                345L,
                240L,
                1L,
                List.of(new CdcTransactionRow(
                        0,
                        "INSERT",
                        Map.of("id", 1L),
                        null,
                        Map.of("id", 1L, "username", "alice")
                ))
        );
    }
}
