package com.campus.ai.planner;

import java.time.LocalDateTime;
import java.util.List;

/**
 * P1.5 时空联合规划的确定性输入/输出模型：LLM 只产生意图与候选，时间与可行性全部由
 * SpatiotemporalPlanner 计算，submit_plan 的时间线必须来自这里的输出。
 */
public final class PlannerModels {

    private PlannerModels() {
    }

    /** 候选地点：rank 即调用方传入的相关性顺序（0 最优） */
    public record PlannerPlace(Long placeId,
                               String name,
                               double longitude,
                               double latitude,
                               long stayMinutes,
                               String source,
                               Long sourceMapId,
                               int rank) {
    }

    /** 单个站点的落定结果 */
    public record PlannerStop(Long placeId,
                              String name,
                              double longitude,
                              double latitude,
                              String source,
                              Long sourceMapId,
                              LocalDateTime startTime,
                              LocalDateTime endTime,
                              long stayMinutes,
                              String transportMode) {
    }

    /** 站点间交通段（真实路线计算结果，可能为估算值） */
    public record PlannerSegment(int fromIndex,
                                 int toIndex,
                                 String transportMode,
                                 Double distanceMeters,
                                 long durationMinutes,
                                 boolean estimated,
                                 List<double[]> polyline) {
    }

    /** 一个可行方案：完整时间线 + 交通 + 硬约束余量 */
    public record PlannerSolution(int rank,
                                  List<PlannerStop> stops,
                                  List<PlannerSegment> segments,
                                  long totalTravelMinutes,
                                  long bufferRemainingMinutes,
                                  double score,
                                  /** 方案中 GROUP_MAP 站点数（计分与展示用） */
                                  int groupMapStops,
                                  /** 路线数据置信度：AMAP（高德真实返回）/ ESTIMATED（退化估算，不能宣称已验证） */
                                  String routeConfidence) {
    }

    /** 求解整体输出：可行方案 + 无可行时的建议 */
    public record PlannerOutcome(List<PlannerSolution> solutions, List<String> suggestions) {
    }
}
