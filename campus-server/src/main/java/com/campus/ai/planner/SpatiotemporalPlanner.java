package com.campus.ai.planner;

import com.campus.ai.travel.RouteInfo;
import com.campus.ai.travel.TravelInfoProvider;
import com.campus.schedule.dto.BusyEntryDto;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * P1.5 确定性时空联合求解（开发计划 §五.2）：
 * 共同空闲窗口（FreeWindowSolver）→ 候选地点受限排列枚举 → 逐段 TravelInfoProvider 真实交通耗时 →
 * 终点保留回流缓冲 → 硬约束过滤 → 固定权重评分 → Top-3。
 *
 * 全程纯确定性计算：不调用 LLM，不访问数据库；任何“方案是否放得下”的判断都在这里完成。
 * 交通耗时来自 provider（高德失败时内部退化为直线估算并标记 estimated）。
 */
public final class SpatiotemporalPlanner {

    /** 候选地点上限：超过会带来排列爆炸，由 tool 层截断 */
    public static final int MAX_CANDIDATES = 4;
    /** 排列总量上限：4 地点全排列 = 24，超限则剪枝到首个子集维度 */
    public static final int MAX_PERMUTATIONS = 24;
    /** 输出方案数上限 */
    public static final int MAX_SOLUTIONS = 3;
    /** 默认每站停留（分钟）；调用方可按站覆盖 */
    public static final long DEFAULT_STAY_MINUTES = 60;
    /** 默认站点高度（终点后必须保留的缓冲，覆盖回程与后续日程衔接） */
    public static final long DEFAULT_BUFFER_MINUTES = 10;
    /** 交通段支持的出行方式（TRANSIT 无 v5 接口，不做联合求解） */
    public static final List<String> SUPPORTED_MODES = List.of("WALK", "RIDE", "DRIVE");

    private SpatiotemporalPlanner() {
    }

    /**
     * @param busy      授权范围内的忙闲（已知成员的并集；未授权成员由调用方标注 UNKNOWN，不参与计算）
     * @param candidates 候选地点（rank 代表意图相关性，0 最优；地点间停留由 place 自带）
     * @param headBufferMinutes 头部衔接缓冲：与窗口前相邻忙闲的最小间隔（复用收尾语义，缺省同 buffer）
     */
    public static PlannerModels.PlannerOutcome plan(List<BusyEntryDto> busy,
                                                    LocalDateTime windowStart,
                                                    LocalDateTime windowEnd,
                                                    List<PlannerModels.PlannerPlace> candidates,
                                                    String transportMode,
                                                    long bufferAfterMinutes,
                                                    long headBufferMinutes,
                                                    TravelInfoProvider provider) {
        return plan(busy, windowStart, windowEnd, candidates, transportMode, bufferAfterMinutes,
                headBufferMinutes, provider, null, false);
    }

    /**
     * 带起点的求解（往返行程）：origin 非空时把「从起点出发」作为首站（停留 0），
     * returnToStart=true 时末尾追加「返回起点」站；两段均走真实路线并计入总交通与收尾缓冲，
     * 模型无需手拼起点/返程站及其时间。
     */
    public static PlannerModels.PlannerOutcome plan(List<BusyEntryDto> busy,
                                                    LocalDateTime windowStart,
                                                    LocalDateTime windowEnd,
                                                    List<PlannerModels.PlannerPlace> candidates,
                                                    String transportMode,
                                                    long bufferAfterMinutes,
                                                    long headBufferMinutes,
                                                    TravelInfoProvider provider,
                                                    PlannerModels.OriginPoint origin,
                                                    boolean returnToStart) {
        if (windowEnd.isBefore(windowStart) || windowStart.equals(windowEnd)) {
            return noSolution("时间窗口无效");
        }
        if (transportMode == null || !SUPPORTED_MODES.contains(transportMode)) {
            return noSolution("交通方式仅支持 WALK / RIDE / DRIVE（公交暂不支持联合求解）");
        }
        if (candidates == null || candidates.isEmpty()) {
            return noSolution("至少需要一个候选地点（共建地图或系统地点）");
        }
        if (candidates.size() > MAX_CANDIDATES) {
            candidates = candidates.subList(0, MAX_CANDIDATES);
        }
        long buffer = Math.max(0, bufferAfterMinutes);
        long headBuffer = Math.max(0, headBufferMinutes);

        long stayFloor = candidates.stream().mapToLong(PlannerModels.PlannerPlace::stayMinutes).sum();
        long need = stayFloor + buffer + headBuffer;
        List<FreeWindow> freeWindows = FreeWindowSolver.findFree(busy, windowStart, windowEnd, need);
        if (freeWindows.isEmpty()) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add(String.format("所选时间范围内没有 ≥%d 分钟的共同空闲（最少需要：停留 %d + 收尾缓冲 %d + 首段衔接 %d）",
                    need, stayFloor, buffer, headBuffer));
            suggestions.add("可尝试：换一天、缩短活动时长、减少地点数量，或调整参与成员");
            return new PlannerModels.PlannerOutcome(List.of(), suggestions);
        }

        List<Permutation> permutations = enumerate(candidates);
        if (permutations.isEmpty()) {
            return noSolution("候选地点组合超出求解能力（可减少地点数量）");
        }

        List<PlannerModels.PlannerSolution> solutions = new ArrayList<>();
        for (FreeWindow window : freeWindows) {
            for (Permutation permutation : permutations) {
                PlannerModels.PlannerSolution solution =
                        solve(window, permutation.places(), transportMode, buffer, headBuffer, busy, provider,
                                origin, returnToStart);
                if (solution != null) {
                    solutions.add(solution);
                }
            }
            if (solutions.size() >= MAX_PERMUTATIONS) {
                break;
            }
        }
        if (solutions.isEmpty()) {
            List<String> suggestions = new ArrayList<>();
            suggestions.add(String.format("窗口时长可容纳停留（%d 分钟），但加入%s实际交通耗时（%s）或首尾衔接缓冲后超出窗口",
                    stayFloor, transportMode.equals("WALK") ? "步行" : transportMode.equals("RIDE") ? "骑行" : "驾车",
                    origin != null ? "含出发与返程两段" : "含站间交通"));
            suggestions.add("可尝试：选择更近的地点、更快交通方式、缩短停留、推迟开始时间或减少地点");
            return new PlannerModels.PlannerOutcome(List.of(), suggestions);
        }
        solutions.sort(Comparator.comparingDouble(PlannerModels.PlannerSolution::score).reversed());
        return new PlannerModels.PlannerOutcome(solutions.subList(0, Math.min(MAX_SOLUTIONS, solutions.size())),
                List.of());
    }

    private static PlannerModels.PlannerOutcome noSolution(String reason) {
        return new PlannerModels.PlannerOutcome(List.of(), List.of(reason));
    }

    /** 全排列（≤4 地点最多 24 个），超限即剪枝；候选顺序即相关性顺序 */
    private static List<Permutation> enumerate(List<PlannerModels.PlannerPlace> candidates) {
        List<Permutation> result = new ArrayList<>();
        heapPermute(0, candidateList(candidates), result);
        return result;
    }

    private static List<PlannerModels.PlannerPlace> candidateList(List<PlannerModels.PlannerPlace> src) {
        List<PlannerModels.PlannerPlace> copy = new ArrayList<>();
        for (PlannerModels.PlannerPlace place : src) {
            copy.add(place);
        }
        return copy;
    }

    private static void heapPermute(int k, List<PlannerModels.PlannerPlace> items,
                                    List<Permutation> out) {
        if (out.size() >= MAX_PERMUTATIONS) {
            return;
        }
        if (k == items.size()) {
            out.add(new Permutation(new ArrayList<>(items)));
            return;
        }
        for (int i = k; i < items.size(); i++) {
            java.util.Collections.swap(items, i, k);
            heapPermute(k + 1, items, out);
            java.util.Collections.swap(items, i, k);
            if (out.size() >= MAX_PERMUTATIONS) {
                return;
            }
        }
    }

    /** 起点站来源标签（模型不可指定；保存时由 PlanSaveService 落成/复用 place） */
    private static final String SOURCE_ORIGIN = "USER_LOCATION";

    /** 单窗口内落定时间线：可行返回计分方案，不可行返回 null（尾部缓冲/头部衔接不足或超出窗口） */
    private static PlannerModels.PlannerSolution solve(FreeWindow window,
                                                       List<PlannerModels.PlannerPlace> order,
                                                       String transportMode,
                                                       long bufferAfter,
                                                       long headBuffer,
                                                       List<BusyEntryDto> busy,
                                                       TravelInfoProvider provider,
                                                       PlannerModels.OriginPoint origin,
                                                       boolean returnToStart) {
        LocalDateTime cursor = window.start();
        // 头部衔接：窗口恰好从某段忙碌结束点开始时，链条整体后推 headBuffer（如 14:00 下课不能 14:05 到远处集合）
        if (headBuffer > 0) {
            for (BusyEntryDto entry : busy) {
                if (entry.getEndTime() == null || entry.getEndTime().isAfter(window.start())) {
                    continue;
                }
                LocalDateTime earliest = entry.getEndTime().plusMinutes(headBuffer);
                if (earliest.isAfter(cursor)) {
                    cursor = earliest;
                }
            }
        }
        List<PlannerModels.PlannerStop> stops = new ArrayList<>();
        List<PlannerModels.PlannerSegment> segments = new ArrayList<>();
        long travelTotal = 0;
        boolean anyEstimated = false;

        Double prevLng = null;
        Double prevLat = null;
        // 出发站：占用「从起点到首站」的交通时段（endTime 即抵达首站的时刻，供校验与展示对齐）
        if (origin != null) {
            RouteInfo outbound = provider.searchRoute(transportMode, origin.longitude(), origin.latitude(),
                    order.get(0).longitude(), order.get(0).latitude());
            long outMinutes = minutesOf(outbound);
            boolean outEstimated = isEstimated(outbound);
            anyEstimated |= outEstimated;
            travelTotal += outMinutes;
            LocalDateTime arrive = cursor.plusMinutes(outMinutes);
            if (outMinutes > 0) {
                segments.add(new PlannerModels.PlannerSegment(0, 1, transportMode,
                        outbound.getDistanceMeters(), outMinutes, outEstimated, polylineOf(outbound)));
            }
            stops.add(new PlannerModels.PlannerStop(null, origin.name(), origin.longitude(), origin.latitude(),
                    SOURCE_ORIGIN, null, cursor, arrive, 0, null));
            cursor = arrive;
            prevLng = origin.longitude();
            prevLat = origin.latitude();
        }

        // 起点段已把 cursor 推到首站抵达时刻：首站不再重复计算"出发→首站"这一段
        boolean outboundHandled = origin != null;
        for (PlannerModels.PlannerPlace place : order) {
            long transitMinutes = 0;
            Double distance = null;
            boolean estimated = false;
            List<double[]> polyline = List.of();
            int currentIndex = stops.size();
            boolean hasPrev = prevLng != null;
            if (hasPrev && !outboundHandled) {
                RouteInfo route = provider.searchRoute(transportMode, prevLng, prevLat,
                        place.longitude(), place.latitude());
                if (route.getDurationSeconds() != null) {
                    transitMinutes = Math.max(0, (route.getDurationSeconds() + 59) / 60);
                }
                distance = route.getDistanceMeters();
                estimated = isEstimated(route);
                polyline = polylineOf(route);
                if (transitMinutes > 0) {
                    segments.add(new PlannerModels.PlannerSegment(currentIndex - 1, currentIndex, transportMode,
                            distance, transitMinutes, estimated, polyline));
                }
                anyEstimated |= estimated;
                travelTotal += transitMinutes;
                cursor = cursor.plusMinutes(transitMinutes);
            }
            outboundHandled = false;
            LocalDateTime stayStart = cursor;
            LocalDateTime stayEnd = cursor.plusMinutes(place.stayMinutes());
            stops.add(new PlannerModels.PlannerStop(place.placeId(), place.name(), place.longitude(),
                    place.latitude(), place.source(), place.sourceMapId(), stayStart, stayEnd,
                    place.stayMinutes(), hasPrev ? transportMode : null));
            cursor = stayEnd;
            prevLng = place.longitude();
            prevLat = place.latitude();
        }

        // 返程段：回到起点并落一个停留 0 的收尾站（start=end=抵达时刻，供校验器对齐"前站 end → 后站 start"）
        if (returnToStart && origin != null) {
            RouteInfo inbound = provider.searchRoute(transportMode, prevLng, prevLat,
                    origin.longitude(), origin.latitude());
            long backMinutes = minutesOf(inbound);
            boolean backEstimated = isEstimated(inbound);
            anyEstimated |= backEstimated;
            travelTotal += backMinutes;
            LocalDateTime arrive = cursor.plusMinutes(backMinutes);
            int currentIndex = stops.size();
            if (backMinutes > 0) {
                segments.add(new PlannerModels.PlannerSegment(currentIndex - 1, currentIndex, transportMode,
                        inbound.getDistanceMeters(), backMinutes, backEstimated, polylineOf(inbound)));
            }
            stops.add(new PlannerModels.PlannerStop(null, origin.name() + "（返回）", origin.longitude(),
                    origin.latitude(), SOURCE_ORIGIN, null, arrive, arrive, 0, transportMode));
            cursor = arrive;
        }

        long remaining = Duration.between(cursor, window.end()).toMinutes();
        if (remaining < bufferAfter) {
            return null;
        }
        int groupStops = 0;
        for (PlannerModels.PlannerStop stop : stops) {
            if (com.campus.ai.plan.PlanSource.GROUP_MAP.equals(stop.source())) {
                groupStops++;
            }
        }
        // 固定权重透明计分：共建优先 > 排序贴合用户意图 > 余量 > 少绕路
        double score = groupStops * 30
                + order.stream().mapToDouble(p -> Math.max(0, 12 - p.rank())).average().orElse(0)
                + remaining
                + Math.max(0, 200 - travelTotal);
        String routeConfidence = segments.isEmpty() ? "NONE" : anyEstimated ? "ESTIMATED" : "AMAP";
        return new PlannerModels.PlannerSolution(0, stops, segments, travelTotal, remaining,
                score, groupStops, routeConfidence);
    }

    private static long minutesOf(RouteInfo route) {
        return route.getDurationSeconds() == null ? 0 : Math.max(0, (route.getDurationSeconds() + 59) / 60);
    }

    private static boolean isEstimated(RouteInfo route) {
        return route.isEstimated() || "estimate".equals(route.getProvider());
    }

    private static List<double[]> polylineOf(RouteInfo route) {
        return route.getPolyline() == null ? List.of() : route.getPolyline();
    }

    private record Permutation(List<PlannerModels.PlannerPlace> places) {
    }
}
