package com.yunye.mncdc.service;

import com.yunye.mncdc.model.CdcTransactionEvent;

public interface RedisApplyStrategy {

    RedisTransactionApplier.ApplyResult apply(CdcTransactionEvent transactionEvent);
}
