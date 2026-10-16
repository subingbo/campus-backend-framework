package com.campus.place.lock;

import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.place.mapper.PlaceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/**
 * place 去重守护锁（审计 R1）：
 * MySQL GET_LOCK 命名锁九宫格 + 锁内复检 + 事务完成后放锁，替代"先查后插"无锁窗口。
 * 格网单元 0.0001°（纬向约 11m、经向在 23°N 约 10m，均大于 5m 合并半径）
 * ⇒ 相距 ≤5m 的两点必落在查询点 3×3 邻格的至少一格内；键按字典序加锁避免交叉等待。
 * 锁持有到事务提交/回滚完成：并发方拿到锁后复检必能看到本事务的提交结果。
 */
@Slf4j
@Component
public class PlaceGridLock {

    private static final double CELL_DEGREES = 0.0001;
    private static final int LOCK_TIMEOUT_SECONDS = 5;

    private final PlaceMapper placeMapper;

    public PlaceGridLock(PlaceMapper placeMapper) {
        this.placeMapper = placeMapper;
    }

    /** 覆盖 5m 合并半径的九宫格键（含自身格），按字典序返回 */
    public List<String> keysAround(BigDecimal longitude, BigDecimal latitude) {
        long latCell = (long) Math.floor(latitude.doubleValue() / CELL_DEGREES);
        long lngCell = (long) Math.floor(longitude.doubleValue() / CELL_DEGREES);
        List<String> keys = new ArrayList<>(9);
        for (long lat = latCell - 1; lat <= latCell + 1; lat++) {
            for (long lng = lngCell - 1; lng <= lngCell + 1; lng++) {
                keys.add("place_grid_" + lat + "_" + lng);
            }
        }
        keys.sort(Comparator.naturalOrder());
        return keys;
    }

    /**
     * 在九宫格命名锁保护下执行写入。调用方必须处于事务内（锁语句与业务复用同一事务连接）；
     * 事务同步活跃时注册 afterCompletion 统一释放，否则立即执行并同步释放。
     */
    public <T> T withinLock(List<String> keys, Supplier<T> action) {
        List<String> acquired = new ArrayList<>(keys.size());
        boolean deferToCompletion = false;
        try {
            for (String key : keys) {
                Integer result = placeMapper.getLock(key, LOCK_TIMEOUT_SECONDS);
                if (result == null || result != 1) {
                    log.warn("place 格网锁等待超时，已持有 {} 个：{}", acquired.size(), key);
                    throw new BusinessException(ResultCode.TOO_MANY_REQUESTS.getCode(), "地点写入繁忙，请重试");
                }
                acquired.add(key);
            }
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        releaseAll(acquired);
                    }
                });
                deferToCompletion = true;
                return action.get();
            }
            log.warn("place 格网锁在无事务上下文中执行，改为即时释放");
            return action.get();
        } finally {
            if (!deferToCompletion) {
                // 加锁阶段失败（部分到手）或无事务上下文：立即释放；有事务场景交给 afterCompletion
                releaseAll(acquired);
            }
        }
    }

    private void releaseAll(List<String> keys) {
        for (String key : keys) {
            try {
                placeMapper.releaseLock(key);
            } catch (Exception ex) {
                log.warn("place 格网锁释放异常（连接归还或会话结束时 MySQL 会自动回收）：{}", key);
            }
        }
    }
}
