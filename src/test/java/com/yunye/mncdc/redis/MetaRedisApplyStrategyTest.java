package com.yunye.mncdc.redis;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetaRedisApplyStrategyTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private MiniCdcProperties properties;

    @Mock
    private MiniCdcProperties.Redis redisProperties;

    private MetaRedisApplyStrategy strategy;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getRedis()).thenReturn(redisProperties);
        lenient().when(redisProperties.getKeyPrefix()).thenReturn("user:");
        lenient().when(redisProperties.getTransactionDonePrefix()).thenReturn("mini-cdc:txn:done:");
        lenient().when(redisProperties.getRowMetaPrefix()).thenReturn("mini-cdc:row:meta:");
        strategy = new MetaRedisApplyStrategy(stringRedisTemplate, new ObjectMapper(), properties);
    }

    @Test
    void buildsBusinessAndMetadataKeysForInsertRows() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("APPLIED");

        RedisTransactionApplier.ApplyResult result = strategy.apply(insertTransaction(
                "mini-user-sync:mysql-bin.000010:88:125",
                "mysql-bin.000010",
                125L,
                0,
                Map.of("id", 1L, "username", "alice")
        ));

        assertThat(result).isEqualTo(RedisTransactionApplier.ApplyResult.APPLIED);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());

        assertThat(keysCaptor.getValue()).containsExactly(
                "mini-cdc:txn:done:mini-user-sync:mysql-bin.000010:88:125",
                "user:1",
                "mini-cdc:row:meta:user:1"
        );
        assertThat(argsCaptor.getValue()[0]).isEqualTo("INSERT");
        assertThat((String) argsCaptor.getValue()[1]).contains("\"username\":\"alice\"");
        assertThat((String) argsCaptor.getValue()[2]).contains("\"deleted\":false");
        assertThat((String) argsCaptor.getValue()[2]).contains("\"binlogFilename\":\"mysql-bin.000010\"");
        assertThat((String) argsCaptor.getValue()[2]).contains("\"nextPosition\":125");
        assertThat((String) argsCaptor.getValue()[2]).contains("\"eventIndex\":0");
        assertThat((String) argsCaptor.getValue()[2]).contains("\"transactionId\":\"mini-user-sync:mysql-bin.000010:88:125\"");
    }

    @Test
    void buildsDeleteTombstoneMetadata() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("APPLIED");

        strategy.apply(deleteTransaction(
                "mini-user-sync:mysql-bin.000011:89:130",
                "mysql-bin.000011",
                130L,
                1,
                Map.of("id", 1L, "username", "alice")
        ));

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsCaptor.getValue()[0]).isEqualTo("DELETE");
        assertThat(argsCaptor.getValue()[1]).isEqualTo("");
        assertThat((String) argsCaptor.getValue()[2]).contains("\"deleted\":true");
        assertThat((String) argsCaptor.getValue()[2]).contains("\"eventIndex\":1");
    }

    @Test
    void returnsDuplicateWhenLuaReportsDoneKeyHit() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("DUPLICATE");

        assertThat(strategy.apply(insertTransaction(
                "mini-user-sync:mysql-bin.000012:90:140",
                "mysql-bin.000012",
                140L,
                0,
                Map.of("id", 1L, "username", "alice")
        ))).isEqualTo(RedisTransactionApplier.ApplyResult.DUPLICATE);
    }

    private CdcTransactionEvent insertTransaction(
            String transactionId,
            String binlogFilename,
            long nextPosition,
            int eventIndex,
            Map<String, Object> after
    ) {
        return new CdcTransactionEvent(
                transactionId,
                "mini-user-sync",
                "mini",
                "user",
                binlogFilename,
                88L,
                nextPosition,
                1L,
                List.of(new CdcTransactionRow(
                        eventIndex,
                        "INSERT",
                        Map.of("id", after.get("id")),
                        null,
                        after
                ))
        );
    }

    private CdcTransactionEvent deleteTransaction(
            String transactionId,
            String binlogFilename,
            long nextPosition,
            int eventIndex,
            Map<String, Object> before
    ) {
        return new CdcTransactionEvent(
                transactionId,
                "mini-user-sync",
                "mini",
                "user",
                binlogFilename,
                88L,
                nextPosition,
                1L,
                List.of(new CdcTransactionRow(
                        eventIndex,
                        "DELETE",
                        Map.of("id", before.get("id")),
                        before,
                        null
                ))
        );
    }
}
