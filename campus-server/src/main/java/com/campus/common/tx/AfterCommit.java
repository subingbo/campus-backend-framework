package com.campus.common.tx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 业务事务提交后再执行副作用（WS 推送/缓存失效）。
 * 只有“同步上下文 + 真实活动事务”同时成立才延迟；无事务时立即执行。
 * 副作用异常只记录日志，不能影响已提交业务或掩盖提交结果。
 */
@Slf4j
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (action == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safeRun(action);
                }
            });
        } else {
            safeRun(action);
        }
    }

    private static void safeRun(Runnable action) {
        try {
            action.run();
        } catch (Exception ex) {
            log.warn("afterCommit 副作用执行失败：{}", ex.toString());
        }
    }
}
