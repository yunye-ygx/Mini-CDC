package com.yunye.mncdc.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.CdcTransactionEvent;
import com.yunye.mncdc.model.CdcTransactionRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MetaRedisApplyStrategyIntegrationTest {

    private LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate stringRedisTemplate;

    private MetaRedisApplyStrategy strategy;

    private String rootPrefix;

    private String businessKeyPrefix;

    private String transactionDonePrefix;

    private String rowMetaPrefix;

    @BeforeEach
    void setUp() {
        rootPrefix = "it:" + UUID.randomUUID() + ":";
        businessKeyPrefix = rootPrefix + "user:";
        transactionDonePrefix = rootPrefix + "txn:";
        rowMetaPrefix = rootPrefix + "meta:";

        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
                System.getenv().getOrDefault("REDIS_HOST", "192.168.100.128"),
                Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"))
        );
        String password = System.getenv().getOrDefault("REDIS_PASSWORD", "123456");
        if (password != null && !password.isBlank()) {
            configuration.setPassword(RedisPassword.of(password));
        }
        configuration.setDatabase(Integer.parseInt(System.getenv().getOrDefault("REDIS_DATABASE", "0")));

        connectionFactory = new LettuceConnectionFactory(configuration);
        connectionFactory.afterPropertiesSet();

        stringRedisTemplate = new StringRedisTemplate(connectionFactory);
        stringRedisTemplate.afterPropertiesSet();

        MiniCdcProperties properties = new MiniCdcProperties();
        properties.getRedis().setKeyPrefix(businessKeyPrefix);
        properties.getRedis().setTransactionDonePrefix(transactionDonePrefix);
        properties.getRedis().setRowMetaPrefix(rowMetaPrefix);

        strategy = new MetaRedisApplyStrategy(stringRedisTemplate, new ObjectMapper(), properties);
    }

    @AfterEach
    void tearDown() {
        if (stringRedisTemplate != null) {
            deleteByPattern(rootPrefix + "*");
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void createsMetadataOnFirstInsert() {
        strategy.apply(insertTransaction("txn-new", "mysql-bin.000010", 125L, 0, Map.of("id", 1L, "username", "alice")));

        assertThat(stringRedisTemplate.opsForValue().get(businessKeyPrefix + "1"))
                .contains("\"id\":1")
                .contains("\"username\":\"alice\"");
        assertThat(stringRedisTemplate.opsForValue().get(rowMetaPrefix + "user:1"))
                .contains("\"deleted\":false")
                .contains("\"eventIndex\":0");
    }

    @Test
    void createsTombstoneOnDeleteEvenWhenBusinessKeyIsMissing() {
        strategy.apply(deleteTransaction("txn-delete", "mysql-bin.000010", 126L, 0, Map.of("id", 1L, "username", "alice")));

        assertThat(stringRedisTemplate.hasKey(businessKeyPrefix + "1")).isFalse();
        assertThat(stringRedisTemplate.opsForValue().get(rowMetaPrefix + "user:1"))
                .contains("\"deleted\":true")
                .contains("\"nextPosition\":126");
    }

    @Test
    void skipsStaleInsertReplayAfterNewerDelete() {
        strategy.apply(deleteTransaction("txn-delete", "mysql-bin.000010", 126L, 0, Map.of("id", 1L, "username", "alice")));
        strategy.apply(insertTransaction("txn-stale", "mysql-bin.000010", 125L, 0, Map.of("id", 1L, "username", "alice")));

        assertThat(stringRedisTemplate.hasKey(businessKeyPrefix + "1")).isFalse();
        assertThat(stringRedisTemplate.opsForValue().get(rowMetaPrefix + "user:1"))
                .contains("\"deleted\":true")
                .contains("\"nextPosition\":126");
    }

    @Test
    void skipsStaleDeleteReplayAfterNewerUpdate() {
        strategy.apply(insertTransaction("txn-insert", "mysql-bin.000010", 125L, 0, Map.of("id", 1L, "username", "alice")));
        strategy.apply(updateTransaction("txn-update", "mysql-bin.000010", 126L, 0,
                Map.of("id", 1L, "username", "alice"),
                Map.of("id", 1L, "username", "alicia")));
        strategy.apply(deleteTransaction("txn-stale-delete", "mysql-bin.000010", 124L, 0, Map.of("id", 1L, "username", "alice")));

        assertThat(stringRedisTemplate.opsForValue().get(businessKeyPrefix + "1"))
                .contains("\"id\":1")
                .contains("\"username\":\"alicia\"");
        assertThat(stringRedisTemplate.opsForValue().get(rowMetaPrefix + "user:1"))
                .contains("\"deleted\":false")
                .contains("\"nextPosition\":126");
    }

    @Test
    void usesEventIndexWhenSameTransactionTouchesTheSameKeyTwice() {
        strategy.apply(new CdcTransactionEvent(
                "txn-repeat",
                "mini-user-sync",
                "mysql-bin.000010",
                88L,
                125L,
                1L,
                List.of(
                        new CdcTransactionRow("mini", "user", 0, "INSERT", Map.of("id", 1L), null, Map.of("id", 1L, "username", "alice")),
                        new CdcTransactionRow("mini", "user", 1, "UPDATE", Map.of("id", 1L), Map.of("id", 1L, "username", "alice"), Map.of("id", 1L, "username", "alicia"))
                )
        ));

        assertThat(stringRedisTemplate.opsForValue().get(businessKeyPrefix + "1"))
                .contains("\"id\":1")
                .contains("\"username\":\"alicia\"");
        assertThat(stringRedisTemplate.opsForValue().get(rowMetaPrefix + "user:1"))
                .contains("\"eventIndex\":1");
    }

    @Test
    void returnsDuplicateWhenDoneKeyAlreadyExists() {
        CdcTransactionEvent transaction = insertTransaction("txn-dup", "mysql-bin.000010", 125L, 0, Map.of("id", 1L, "username", "alice"));

        assertThat(strategy.apply(transaction)).isEqualTo(RedisTransactionApplier.ApplyResult.APPLIED);
        assertThat(strategy.apply(transaction)).isEqualTo(RedisTransactionApplier.ApplyResult.DUPLICATE);
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
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
                binlogFilename,
                88L,
                nextPosition,
                1L,
                List.of(new CdcTransactionRow(
                        "mini",
                        "user",
                        eventIndex,
                        "INSERT",
                        Map.of("id", after.get("id")),
                        null,
                        after
                ))
        );
    }

    private CdcTransactionEvent updateTransaction(
            String transactionId,
            String binlogFilename,
            long nextPosition,
            int eventIndex,
            Map<String, Object> before,
            Map<String, Object> after
    ) {
        return new CdcTransactionEvent(
                transactionId,
                "mini-user-sync",
                binlogFilename,
                88L,
                nextPosition,
                1L,
                List.of(new CdcTransactionRow(
                        "mini",
                        "user",
                        eventIndex,
                        "UPDATE",
                        Map.of("id", after.get("id")),
                        before,
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
                binlogFilename,
                88L,
                nextPosition,
                1L,
                List.of(new CdcTransactionRow(
                        "mini",
                        "user",
                        eventIndex,
                        "DELETE",
                        Map.of("id", before.get("id")),
                        before,
                        null
                ))
        );
    }
}
