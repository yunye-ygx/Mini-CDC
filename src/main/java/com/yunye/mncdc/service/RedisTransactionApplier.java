package com.yunye.mncdc.service;

import com.yunye.mncdc.config.MiniCdcProperties;
import com.yunye.mncdc.model.CdcTransactionEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RedisTransactionApplier {

    private final MiniCdcProperties properties;

    private final RedisApplyStrategy simpleStrategy;

    private final RedisApplyStrategy metaStrategy;

    @Autowired
    public RedisTransactionApplier(
            MiniCdcProperties properties,
            @Qualifier("simpleRedisApplyStrategy") ObjectProvider<RedisApplyStrategy> simpleStrategyProvider,
            @Qualifier("metaRedisApplyStrategy") ObjectProvider<RedisApplyStrategy> metaStrategyProvider
    ) {
        this(properties, simpleStrategyProvider.getIfAvailable(), metaStrategyProvider.getIfAvailable());
    }

    RedisTransactionApplier(
            MiniCdcProperties properties,
            RedisApplyStrategy simpleStrategy,
            RedisApplyStrategy metaStrategy
    ) {
        this.properties = properties;
        this.simpleStrategy = simpleStrategy;
        this.metaStrategy = metaStrategy;
    }

    public ApplyResult apply(CdcTransactionEvent transactionEvent) {
        RedisApplyStrategy strategy = switch (properties.getRedis().getApplyMode()) {
            case META -> metaStrategy;
            case SIMPLE -> simpleStrategy;
        };
        if (strategy == null) {
            throw new IllegalStateException(
                    "Redis apply strategy is not available for mode " + properties.getRedis().getApplyMode()
            );
        }
        return strategy.apply(transactionEvent);
    }

    public enum ApplyResult {
        APPLIED,
        DUPLICATE;

        public static ApplyResult from(String value) {
            if ("APPLIED".equals(value)) {
                return APPLIED;
            }
            if ("DUPLICATE".equals(value)) {
                return DUPLICATE;
            }
            throw new IllegalStateException("Unexpected Redis apply result: " + value);
        }
    }
}
