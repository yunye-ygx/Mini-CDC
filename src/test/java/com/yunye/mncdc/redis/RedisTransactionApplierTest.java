package com.yunye.mncdc.redis;

import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.CdcTransactionEvent;
import com.yunye.mncdc.model.CdcTransactionRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisTransactionApplierTest {

    @Mock
    private RedisApplyStrategy simpleStrategy;

    @Mock
    private RedisApplyStrategy metaStrategy;

    @Test
    void delegatesToSimpleStrategyByDefault() {
        MiniCdcProperties properties = new MiniCdcProperties();
        RedisTransactionApplier applier = new RedisTransactionApplier(properties, simpleStrategy, metaStrategy);
        CdcTransactionEvent transaction = sampleTransaction();
        when(simpleStrategy.apply(transaction)).thenReturn(RedisTransactionApplier.ApplyResult.APPLIED);

        assertThat(applier.apply(transaction)).isEqualTo(RedisTransactionApplier.ApplyResult.APPLIED);
        verify(simpleStrategy).apply(transaction);
        verifyNoInteractions(metaStrategy);
    }

    @Test
    void delegatesToMetaStrategyWhenConfigured() {
        MiniCdcProperties properties = new MiniCdcProperties();
        properties.getRedis().setApplyMode(MiniCdcProperties.Redis.ApplyMode.META);
        RedisTransactionApplier applier = new RedisTransactionApplier(properties, simpleStrategy, metaStrategy);
        CdcTransactionEvent transaction = sampleTransaction();
        when(metaStrategy.apply(transaction)).thenReturn(RedisTransactionApplier.ApplyResult.DUPLICATE);

        assertThat(applier.apply(transaction)).isEqualTo(RedisTransactionApplier.ApplyResult.DUPLICATE);
        verify(metaStrategy).apply(transaction);
        verifyNoInteractions(simpleStrategy);
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
