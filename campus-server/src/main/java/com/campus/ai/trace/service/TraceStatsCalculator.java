package com.campus.ai.trace.service;

import com.campus.ai.trace.domain.AgentTraceEvent;
import com.campus.ai.trace.dto.TraceDurationStat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 详情耗时统计：按事件类型聚合，tool/result 再按工具名细分；百分位用最近秩法（结果偏保守） */
public final class TraceStatsCalculator {

    private TraceStatsCalculator() {
    }

    public static List<TraceDurationStat> calculate(List<AgentTraceEvent> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<String, List<Long>> grouped = new LinkedHashMap<>();
        for (AgentTraceEvent row : rows) {
            if (row.getDurationMs() == null || row.getDurationMs() < 0) {
                continue;
            }
            grouped.computeIfAbsent(groupKey(row.getEventType(), row.getToolName()),
                    key -> new ArrayList<>()).add(row.getDurationMs());
        }
        List<TraceDurationStat> stats = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<Long>> entry : grouped.entrySet()) {
            List<Long> durations = entry.getValue();
            durations.sort(Comparator.naturalOrder());
            String[] keyParts = entry.getKey().split("\\|", 2);
            TraceDurationStat stat = new TraceDurationStat();
            stat.setType(keyParts[0]);
            stat.setTool(keyParts.length > 1 && !keyParts[1].isBlank() ? keyParts[1] : null);
            stat.setCount(durations.size());
            stat.setTotalMs(durations.stream().mapToLong(Long::longValue).sum());
            stat.setAvgMs(Math.round((double) stat.getTotalMs() / durations.size()));
            stat.setP50Ms(percentile(durations, 50));
            stat.setP95Ms(percentile(durations, 95));
            stats.add(stat);
        }
        return stats;
    }

    /** 最近秩百分位：index = ceil(rank/100 * n) - 1 */
    static long percentile(List<Long> sortedAsc, int rank) {
        int n = sortedAsc.size();
        if (n == 0) {
            return 0L;
        }
        int index = (int) Math.ceil(rank / 100.0 * n) - 1;
        return sortedAsc.get(Math.min(n - 1, Math.max(0, index)));
    }

    private static String groupKey(String type, String tool) {
        return (type == null ? "" : type) + "|" + (tool == null ? "" : tool);
    }
}
