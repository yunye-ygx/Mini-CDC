package com.yunye.mncdc.redis;

import com.yunye.mncdc.model.CdcTransactionEvent;

public interface RedisApplyStrategy {

    RedisTransactionApplier.ApplyResult apply(CdcTransactionEvent transactionEvent);
}
