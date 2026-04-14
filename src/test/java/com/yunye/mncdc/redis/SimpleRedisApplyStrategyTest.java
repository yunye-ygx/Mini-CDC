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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleRedisApplyStrategyTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private MiniCdcProperties properties;

    @Mock
    private MiniCdcProperties.Redis redisProperties;

    private SimpleRedisApplyStrategy strategy;

    @BeforeEach
    void setUp() {
        lenient().when(properties.getRedis()).thenReturn(redisProperties);
        lenient().when(redisProperties.getKeyPrefix()).thenReturn("user:");
        lenient().when(redisProperties.getTransactionDonePrefix()).thenReturn("mini-cdc:txn:done:");
        strategy = new SimpleRedisApplyStrategy(stringRedisTemplate, new ObjectMapper(), properties);
    }

    @Test
    void appliesTransactionAndStoresDoneKeyWhenNotProcessed() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("APPLIED");

        RedisTransactionApplier.ApplyResult result = strategy.apply(sampleTransaction());

        assertThat(result).isEqualTo(RedisTransactionApplier.ApplyResult.APPLIED);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());
        assertThat(keysCaptor.getValue()).containsExactly(
                "mini-cdc:txn:done:mini-user-sync:mysql-bin.000001:345:240",
                "user:1"
        );
        Object[] args = argsCaptor.getValue();
        assertThat(args).hasSize(3);
        assertThat(args[0]).isEqualTo("SET");
        assertThat((String) args[1]).contains("\"username\":\"alice\"");
        assertThat(args[2]).isEqualTo("1");
    }

    @Test
    void returnsDuplicateWhenDoneKeyAlreadyExists() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("DUPLICATE");

        RedisTransactionApplier.ApplyResult result = strategy.apply(sampleTransaction());

        assertThat(result).isEqualTo(RedisTransactionApplier.ApplyResult.DUPLICATE);
    }

    @Test
    void appliesDeleteRowsByIssuingDelOperation() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("APPLIED");

        CdcTransactionEvent deleteTransaction = new CdcTransactionEvent(
                "mini-user-sync:mysql-bin.000002:456:789",
                "mini-user-sync",
                "mysql-bin.000002",
                456L,
                789L,
                2L,
                List.of(new CdcTransactionRow(
                        "mini",
                        "user",
                        0,
                        "DELETE",
                        Map.of("id", 42L),
                        Map.of("id", 42L),
                        null
                ))
        );

        RedisTransactionApplier.ApplyResult result = strategy.apply(deleteTransaction);

        assertThat(result).isEqualTo(RedisTransactionApplier.ApplyResult.APPLIED);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());

        assertThat(keysCaptor.getValue()).containsExactly(
                "mini-cdc:txn:done:mini-user-sync:mysql-bin.000002:456:789",
                "user:42"
        );
        assertThat(argsCaptor.getValue()).containsExactly("DEL", "1");
    }

    @Test
    void rejectsDeleteWithoutPrimaryKey() {
        CdcTransactionEvent invalidTransaction = new CdcTransactionEvent(
                "mini-user-sync:mysql-bin.000003:0:1",
                "mini-user-sync",
                "mysql-bin.000003",
                0L,
                1L,
                3L,
                List.of(new CdcTransactionRow(
                        "mini",
                        "user",
                        0,
                        "DELETE",
                        Map.of(),
                        Map.of("id", 99L),
                        null
                ))
        );

        assertThatThrownBy(() -> strategy.apply(invalidTransaction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("primaryKey");
    }

    @Test
    void rejectsInsertOrUpdateRowsWithoutAfter() {
        CdcTransactionEvent invalidTransaction = new CdcTransactionEvent(
                "mini-user-sync:mysql-bin.000004:100:200",
                "mini-user-sync",
                "mysql-bin.000004",
                100L,
                200L,
                4L,
                List.of(new CdcTransactionRow(
                        "mini",
                        "user",
                        0,
                        "INSERT",
                        Map.of("id", 99L),
                        Map.of("id", 99L),
                        null
                ))
        );

        assertThatThrownBy(() -> strategy.apply(invalidTransaction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CDC transaction row must contain after for INSERT/UPDATE/SNAPSHOT_UPSERT.");
    }

    @Test
    void rejectsUnsupportedEventType() {
        CdcTransactionEvent invalidTransaction = new CdcTransactionEvent(
                "mini-user-sync:mysql-bin.000005:111:222",
                "mini-user-sync",
                "mysql-bin.000005",
                111L,
                222L,
                5L,
                List.of(new CdcTransactionRow(
                        "mini",
                        "user",
                        0,
                        "UNKNOWN",
                        Map.of("id", 21L),
                        null,
                        null
                ))
        );

        assertThatThrownBy(() -> strategy.apply(invalidTransaction))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected CDC transaction eventType");
    }

    @Test
    void appliesMixedSetAndDeleteOperationsInOrder() {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("APPLIED");

        CdcTransactionEvent mixedTransaction = new CdcTransactionEvent(
                "mini-user-sync:mysql-bin.000006:123:456",
                "mini-user-sync",
                "mysql-bin.000006",
                123L,
                456L,
                6L,
                List.of(
                        new CdcTransactionRow(
                                "mini",
                                "user",
                                0,
                                "INSERT",
                                Map.of("id", 10L),
                                null,
                                Map.of("id", 10L, "username", "bob")
                        ),
                        new CdcTransactionRow(
                                "mini",
                                "user",
                                1,
                                "DELETE",
                                Map.of("id", 20L),
                                Map.of("id", 20L, "username", "charlie"),
                                null
                        )
                )
        );

        RedisTransactionApplier.ApplyResult result = strategy.apply(mixedTransaction);

        assertThat(result).isEqualTo(RedisTransactionApplier.ApplyResult.APPLIED);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(stringRedisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());

        assertThat(keysCaptor.getValue()).containsExactly(
                "mini-cdc:txn:done:mini-user-sync:mysql-bin.000006:123:456",
                "user:10",
                "user:20"
        );
        Object[] args = argsCaptor.getValue();
        assertThat(args[0]).isEqualTo("SET");
        assertThat((String) args[1]).contains("\"username\":\"bob\"");
        assertThat(args[2]).isEqualTo("DEL");
        assertThat(args[3]).isEqualTo("1");
    }

    @Test
    void loadsLuaScriptFromClasspathResource() throws Exception {
        when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("APPLIED");

        strategy.apply(sampleTransaction());

        ArgumentCaptor<RedisScript> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        verify(stringRedisTemplate).execute(scriptCaptor.capture(), anyList(), any(Object[].class));

        ClassPathResource resource = new ClassPathResource("lua/apply-transaction.lua");
        assertThat(resource.exists()).isTrue();
        assertThat(readField(scriptCaptor.getValue(), "scriptSource")).isInstanceOf(ResourceScriptSource.class);
        assertThat(scriptCaptor.getValue().getScriptAsString())
                .isEqualTo(new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private CdcTransactionEvent sampleTransaction() {
        return new CdcTransactionEvent(
                "mini-user-sync:mysql-bin.000001:345:240",
                "mini-user-sync",
                "mysql-bin.000001",
                345L,
                240L,
                1L,
                List.of(new CdcTransactionRow(
                        "mini",
                        "user",
                        0,
                        "INSERT",
                        Map.of("id", 1L),
                        null,
                        Map.of("id", 1L, "username", "alice")
                ))
        );
    }

    private Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
