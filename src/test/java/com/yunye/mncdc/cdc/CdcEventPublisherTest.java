package com.yunye.mncdc.cdc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.CdcTransactionEvent;
import com.yunye.mncdc.model.CdcTransactionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CdcEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private MiniCdcProperties properties;

    @Mock
    private MiniCdcProperties.Kafka kafkaProperties;

    private ObjectMapper objectMapper;

    private CdcEventPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        publisher = new CdcEventPublisher(kafkaTemplate, objectMapper, properties);
    }

    @Test
    void serializesEventIndexInPublishedPayload() throws Exception {
        when(properties.getKafka()).thenReturn(kafkaProperties);
        when(kafkaProperties.getTopic()).thenReturn("user-change-topic");
        when(properties.isLogEventJson()).thenReturn(false);
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        publisher.publishTransaction(new CdcTransactionEvent(
                "txn-1",
                "mini-user-sync",
                "mini",
                "user",
                "mysql-bin.000010",
                88L,
                125L,
                1L,
                List.of(new CdcTransactionRow(
                        1,
                        "UPDATE",
                        Map.of("id", 1L),
                        Map.of("id", 1L, "username", "tom"),
                        Map.of("id", 1L, "username", "tommy")
                ))
        ));

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("user-change-topic"), eq("txn-1"), payloadCaptor.capture());
        JsonNode jsonPayload = objectMapper.readTree(payloadCaptor.getValue());
        assertThat(jsonPayload.path("events").get(0).path("eventIndex").asInt()).isEqualTo(1);

        CdcTransactionEvent roundTrip = objectMapper.readValue(payloadCaptor.getValue(), CdcTransactionEvent.class);
        assertThat(roundTrip.events().get(0).eventIndex()).isEqualTo(1);
    }
}
