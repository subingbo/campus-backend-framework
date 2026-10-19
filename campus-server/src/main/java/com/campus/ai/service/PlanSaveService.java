package com.campus.ai.service;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanSaveResponse;
import com.campus.ai.dto.PlanStop;
import com.campus.ai.dto.RevisePlanResponse;
import com.campus.ai.entity.AiConversation;
import com.campus.ai.mapper.AiConversationMapper;
import com.campus.ai.plan.PlanValidationException;
import com.campus.ai.plan.PlanValidator;
import com.campus.ai.trace.adapter.TraceSaveRun;
import com.campus.ai.trace.dto.TraceTrigger;
import com.campus.ai.trace.service.AgentTraceService;
import com.campus.activity.dto.ActivityResponse;
import com.campus.activity.dto.ActivityStopRequest;
import com.campus.activity.dto.CreateActivityRequest;
import com.campus.activity.service.ActivityService;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.place.dto.PlaceCreateResponse;
import com.campus.place.dto.CreatePlaceRequest;
import com.campus.place.service.PlaceService;
import com.campus.security.user.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 草稿确认保存（评审 P0 的校验+事务+幂等层）：
 * 行锁串行化重复保存 → 幂等命中返回已建活动 → 重新校验草稿 → 库外坐标先建 place（复用 5m 查重）
 * → ActivityService.create 主场地事务 → markSaved；任一步失败整体回滚。
 */
@Slf4j
@Service
public class PlanSaveService {

    /** 高德 POI/坐标站点自动建 place 时的缺省分类（其他） */
    private static final long DEFAULT_PLACE_CATEGORY_ID = 8L;

    private final AiConversationMapper conversationMapper;
    private final PlanValidator planValidator;
    private final PlaceService placeService;
    private final ActivityService activityService;
    private final ObjectMapper objectMapper;
    private final GroupMapContextService groupMapContextService;
    private final com.campus.schedule.service.ScheduleService scheduleService;
    private final AgentTraceService traceService;

    public PlanSaveService(AiConversationMapper conversationMapper,
                           PlanValidator planValidator,
                           PlaceService placeService,
                           ActivityService activityService,
                           @Qualifier("redisObjectMapper") ObjectMapper objectMapper,
                           GroupMapContextService groupMapContextService,
                           com.campus.schedule.service.ScheduleService scheduleService,
                           AgentTraceService traceService) {
        this.conversationMapper = conversationMapper;
        this.planValidator = planValidator;
        this.placeService = placeService;
        this.activityService = activityService;
        this.objectMapper = objectMapper;
        this.groupMapContextService = groupMapContextService;
        this.scheduleService = scheduleService;
        this.traceService = traceService;
    }

    @Transactional
    public PlanSaveResponse save(Long conversationId, Integer expectedPlanVersion) {
        Long userId = SecurityUtils.getUserId();
        AiConversation conversation = conversationMapper.selectByIdForUpdate(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "会话不存在");
        }
        int currentVersion = conversation.getPlanVersion() == null ? 0 : conversation.getPlanVersion();
        TraceSaveRun traceRun = traceService.beginSave(conversation, TraceTrigger.save(), true);

        // 幂等优先：已保存过直接返回之前的活动（即使 expected 版本过期，也不覆盖新版、不重复保存）
        if (AiConversation.STATUS_SAVED.equals(conversation.getStatus())
                && conversation.getSavedActivityId() != null) {
            long activityId = conversation.getSavedActivityId();
            PlanSaveResponse response;
            try {
                response = new PlanSaveResponse(activityId, activityService.get(activityId));
            } catch (RuntimeException ex) {
                traceRun.saveError("SAVE", ResultCode.INTERNAL_ERROR.getCode(), "IDEMPOTENT_REUSE_FAILED");
                traceRun.close();
                throw ex;
            }
            registerTraceAfterTransaction(traceRun,
                    () -> {
                        traceRun.confirmSuccess(activityId, currentVersion, "REUSE", true);
                        traceRun.activityCreated(activityId, currentVersion, "REUSE", true);
                        // 幂等复用不结束 session：它可能只是旧活动被重复点击，不应误伤同会话下新一轮 chat
                    },
                    () -> {
                        traceRun.confirmFailed("IDEMPOTENT_REUSE_FAILED", currentVersion, true);
                        traceRun.saveError("SAVE", ResultCode.INTERNAL_ERROR.getCode(),
                                "IDEMPOTENT_REUSE_FAILED");
                    });
            return response;
        }

        traceRun.confirmRunning("SAVE", currentVersion, expectedPlanVersion);
        if (!StringUtils.hasText(conversation.getPlanJson())) {
            traceRun.confirmFailed("NO_DRAFT", currentVersion, false);
            traceRun.saveError("SAVE", ResultCode.BAD_REQUEST.getCode(), "NO_DRAFT");
            traceRun.close();
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "暂无可保存的规划草稿");
        }
        // 版本竞赛防护：expected 与行锁内读到的当前版本不一致 → 拒绝保存过期/未来草稿（版本只增不减）
        if (expectedPlanVersion != null && !expectedPlanVersion.equals(conversation.getPlanVersion())) {
            traceRun.confirmFailed("PLAN_VERSION_CONFLICT", currentVersion, false);
            traceRun.saveError("SAVE", ResultCode.CONFLICT.getCode(), "PLAN_VERSION_CONFLICT");
            traceRun.close();
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "草稿已更新，请刷新后重新保存");
        }
        // 保存时再次完整校验（地点可能被隐藏/时间可能过期/交通可能放不下）
        PlanDto plan;
        try {
            plan = planValidator.parseAndValidate(conversation.getPlanJson(), userId);
        } catch (PlanValidationException ex) {
            traceRun.validationFailed("SAVE", ex, currentVersion);
            traceRun.confirmFailed(ex.getRuleId(), currentVersion, false);
            traceRun.saveError("SAVE", ex.getCode(), ex.getRuleId());
            traceRun.close();
            throw ex;
        } catch (BusinessException ex) {
            traceRun.validationFailed("SAVE", "PLAN_VALIDATION_FAILED", currentVersion);
            traceRun.confirmFailed("PLAN_VALIDATION_FAILED", currentVersion, false);
            traceRun.saveError("SAVE", ex.getCode(), "PLAN_VALIDATION_FAILED");
            traceRun.close();
            throw ex;
        }
        traceRun.validationPassed("SAVE", plan, currentVersion);
        traceRun.bindContext(plan);
        // P1.5/H 复审提交前二次校验：权限、共建地图、规划指纹、成员忙闲/衔接都在保存一刻重新核实
        try {
            revalidateAgainstWorld(plan, userId, plan.getLineageActivityId());
        } catch (BusinessException ex) {
            traceRun.validationFailed("REVALIDATE", "SAVE_REVALIDATION_FAILED", currentVersion);
            traceRun.confirmFailed("SAVE_REVALIDATION_FAILED", currentVersion, false);
            traceRun.saveError("SAVE", ex.getCode(), "SAVE_REVALIDATION_FAILED");
            traceRun.close();
            throw ex;
        }

        String operation = plan.getLineageActivityId() != null ? "UPDATE" : "CREATE";
        try {
            List<ActivityStopRequest> stops = new ArrayList<>();
            for (PlanStop stop : plan.getStops()) {
                if (stop.getPlaceId() == null) {
                    stop.setPlaceId(ensurePlace(stop));
                }
                ActivityStopRequest request = new ActivityStopRequest();
                request.setName(stop.getName());
                request.setPlaceId(stop.getPlaceId());
                request.setStartTime(stop.getStartTime());
                request.setEndTime(stop.getEndTime());
                request.setTransportMode(stop.getTransportMode());
                request.setNote(stop.getNote());
                stops.add(request);
            }

            // 谱系保存：已保存活动的修订 → 走现有活动编辑接口原地更新（不建重复活动）
            if (plan.getLineageActivityId() != null) {
                com.campus.activity.dto.UpdateActivityRequest update =
                        new com.campus.activity.dto.UpdateActivityRequest();
                update.setTitle(plan.getTitle());
                update.setDescription(plan.getSummary());
                update.setStartTime(plan.getStartTime());
                update.setEndTime(plan.getEndTime());
                update.setMaxMembers(plan.getMaxMembers());
                update.setCategory(plan.getCategory());
                update.setStops(stops);
                ActivityResponse activity = activityService.update(plan.getLineageActivityId(), update);
                int updatedRows = conversationMapper.markSaved(conversation.getId(),
                        plan.getLineageActivityId());
                if (updatedRows == 0) {
                    throw new BusinessException(ResultCode.CONFLICT.getCode(), "保存冲突，请刷新后重试");
                }
                long activityId = plan.getLineageActivityId();
                registerTraceAfterTransaction(traceRun,
                        () -> {
                            traceRun.confirmSuccess(activityId, currentVersion, "UPDATE", false);
                            traceRun.activityCreated(activityId, currentVersion, "UPDATE", false);
                            traceRun.finishCompleted("plan_saved");
                        },
                        () -> {
                            traceRun.confirmFailed("SAVE_FAILED", currentVersion, true);
                            traceRun.saveError("SAVE", ResultCode.INTERNAL_ERROR.getCode(), "SAVE_FAILED");
                        });
                return new PlanSaveResponse(activityId, activity);
            }

            CreateActivityRequest request = new CreateActivityRequest();
            request.setPlaceId(stops.get(0).getPlaceId());
            request.setTitle(plan.getTitle());
            request.setDescription(plan.getSummary());
            request.setCategory(plan.getCategory());
            request.setStartTime(plan.getStartTime());
            request.setEndTime(plan.getEndTime());
            request.setMaxMembers(plan.getMaxMembers());
            request.setStops(stops);
            ActivityResponse activity = activityService.create(request);

            int rows = conversationMapper.markSaved(conversation.getId(), activity.getId());
            if (rows == 0) {
                // 并发保存被抢先：丢弃本事务刚刚创建的活动（随事务回滚），提示重新保存
                throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "保存冲突，请重试一次");
            }
            long activityId = activity.getId();
            registerTraceAfterTransaction(traceRun,
                    () -> {
                        traceRun.confirmSuccess(activityId, currentVersion, "CREATE", false);
                        traceRun.activityCreated(activityId, currentVersion, "CREATE", false);
                        traceRun.finishCompleted("plan_saved");
                    },
                    () -> {
                        traceRun.confirmFailed("SAVE_FAILED", currentVersion, true);
                        traceRun.saveError("SAVE", ResultCode.INTERNAL_ERROR.getCode(), "SAVE_FAILED");
                    });
            return new PlanSaveResponse(activityId, activity);
        } catch (BusinessException ex) {
            traceRun.confirmFailed("SAVE_FAILED", currentVersion, false);
            traceRun.saveError("SAVE", ex.getCode(), "SAVE_FAILED");
            traceRun.close();
            throw ex;
        } catch (RuntimeException ex) {
            traceRun.confirmFailed("SAVE_FAILED", currentVersion, false);
            traceRun.saveError("SAVE", ResultCode.INTERNAL_ERROR.getCode(), "SAVE_FAILED");
            traceRun.close();
            throw ex;
        }
    }

    /** 修订草稿：行锁 + 归属 + 版本防覆盖 + 完整重校验 → 版本+1 写回（F 阶段；重置幂等标记允许再次保存） */
    @Transactional
    public RevisePlanResponse revise(Long conversationId, PlanDto plan, Integer expectedPlanVersion) {
        return revise(conversationId, plan, expectedPlanVersion, null);
    }

    /**
     * H 复审修订语义：
     * - 未保存草稿：编辑后版本+1（保持 F 阶段行为）。
     * - 已保存会话（SAVED）发生真实内容修改：默认盖 lineageActivityId → 再次保存走活动更新接口原地更新；
     *   asNewDraft=true 时清空谱系 → 再次保存创建新活动（显式“复制为新草稿”）。
     * - 无变更修订：不升版、不重置幂等标记。
     */
    @Transactional
    public RevisePlanResponse revise(Long conversationId, PlanDto plan, Integer expectedPlanVersion,
                                     Boolean asNewDraft) {
        Long userId = SecurityUtils.getUserId();
        AiConversation conversation = conversationMapper.selectByIdForUpdate(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "会话不存在");
        }
        int currentVersion = conversation.getPlanVersion() == null ? 0 : conversation.getPlanVersion();
        TraceSaveRun traceRun = traceService.beginSave(conversation, TraceTrigger.revise(), false);
        if (plan == null) {
            traceRun.saveError("REVISE", ResultCode.BAD_REQUEST.getCode(), "NO_PLAN");
            traceRun.close();
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "规划草稿为空");
        }
        if (expectedPlanVersion != null && !expectedPlanVersion.equals(conversation.getPlanVersion())) {
            traceRun.saveError("REVISE", ResultCode.CONFLICT.getCode(), "PLAN_VERSION_CONFLICT");
            traceRun.close();
            throw new BusinessException(ResultCode.CONFLICT.getCode(), "草稿已更新，请刷新后重新编辑");
        }
        PlanDto validated;
        try {
            validated = planValidator.validate(plan, userId);
        } catch (PlanValidationException ex) {
            traceRun.validationFailed("REVISE", ex, currentVersion);
            traceRun.saveError("REVISE", ex.getCode(), ex.getRuleId());
            traceRun.close();
            throw ex;
        } catch (BusinessException ex) {
            traceRun.validationFailed("REVISE", "PLAN_VALIDATION_FAILED", currentVersion);
            traceRun.saveError("REVISE", ex.getCode(), "PLAN_VALIDATION_FAILED");
            traceRun.close();
            throw ex;
        }
        traceRun.validationPassed("REVISE", validated, currentVersion);
        traceRun.bindContext(validated);
        String planJson;
        try {
            planJson = objectMapper.writeValueAsString(validated);
        } catch (Exception ex) {
            traceRun.saveError("REVISE", ResultCode.INTERNAL_ERROR.getCode(), "PLAN_SERIALIZE_FAILED");
            traceRun.close();
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "草稿序列化失败");
        }
        // 无变更修订守卫：提交内容与当前草稿逐字一致时不升版、不重置幂等标记，
        // 杜绝“已保存草稿经空修订后再次保存成重复活动”
        if (planJson.equals(conversation.getPlanJson())) {
            PlanDto current = planValidator.parseAndValidate(conversation.getPlanJson(), userId);
            if (com.campus.ai.entity.AiConversation.STATUS_SAVED.equals(conversation.getStatus())) {
                current.setLineageActivityId(conversation.getSavedActivityId());
            }
            registerTraceAfterTransaction(traceRun,
                    () -> traceRun.revise(currentVersion, currentVersion, false),
                    () -> traceRun.saveError("REVISE", ResultCode.INTERNAL_ERROR.getCode(), "REVISE_FAILED"));
            return new RevisePlanResponse(current, conversation.getPlanVersion());
        }
        // 真实修订的谱系盖章（服务端权威，不信任请求自述）
        boolean copyAsNew = Boolean.TRUE.equals(asNewDraft);
        if (!copyAsNew && AiConversation.STATUS_SAVED.equals(conversation.getStatus())
                && conversation.getSavedActivityId() != null) {
            validated.setLineageActivityId(conversation.getSavedActivityId());
            try {
                planJson = objectMapper.writeValueAsString(validated);
            } catch (Exception ex) {
                traceRun.saveError("REVISE", ResultCode.INTERNAL_ERROR.getCode(), "PLAN_SERIALIZE_FAILED");
                traceRun.close();
                throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "草稿序列化失败");
            }
        } else {
            validated.setLineageActivityId(null);
        }
        int nextVersion = conversation.getPlanVersion() == null ? 1 : conversation.getPlanVersion() + 1;
        try {
            conversationMapper.updatePlanResult(conversation.getId(), planJson, nextVersion,
                    AiConversation.STATUS_DRAFT);
        } catch (RuntimeException ex) {
            traceRun.saveError("REVISE", ResultCode.INTERNAL_ERROR.getCode(), "REVISE_FAILED");
            traceRun.close();
            throw ex;
        }
        registerTraceAfterTransaction(traceRun,
                () -> traceRun.revise(currentVersion, nextVersion, true),
                () -> traceRun.saveError("REVISE", ResultCode.INTERNAL_ERROR.getCode(), "REVISE_FAILED"));
        return new RevisePlanResponse(validated, nextVersion);
    }

    /** 相邻固定日程与活动首末站之间的最小衔接缓冲（分钟）：不足则要求用户重新确认 */
    private static final long MIN_ADJACENT_TRANSITION_MINUTES = 10;

    /**
     * P1.5/H 复审提交前二次校验（开发计划 §七 + 评审五项）：
     * 1) 群体草稿：发起者仍是群地图成员；声明的 sourceMapIds 与所选共建地点仍可访问；
     * 2) planningSourceVersion 与当前地图指纹一致（共建地图/备注在规划期间发生变化 → 要求重新生成）；
     * 3) KNOWN 成员忙闲与活动窗口重叠或衔接不足 → 409（唯一豁免：谱系活动本身，评审项“只排除正在更新的那个活动”）；
     * 4) 存在 UNKNOWN 成员 → constraintStatus 标记 TENTATIVE（卡片必须明示）。
     */
    private void revalidateAgainstWorld(PlanDto plan, Long userId, Long lineageActivityId) {
        java.time.LocalDateTime start = plan.getStartTime();
        java.time.LocalDateTime end = plan.getEndTime();
        java.util.List<com.campus.schedule.dto.BusyEntryDto> known;
        java.util.List<Long> unknown;
        if (plan.getGroupId() != null) {
            List<Long> maps = groupMapContextService.groupMapIds(userId, plan.getGroupId());
            if (maps.isEmpty()) {
                throw groupChanged("你已不属于该群地图，无法保存该群规划，请重新发起");
            }
            if (plan.getSourceMapIds() != null && !plan.getSourceMapIds().isEmpty()) {
                if (!maps.containsAll(plan.getSourceMapIds())) {
                    throw groupChanged("群地图归属已变化，请重新生成规划");
                }
                List<Long> mapAnchored = new ArrayList<>();
                if (plan.getSelectedPlaceIds() != null) {
                    for (PlanStop stop : plan.getStops()) {
                        if (stop.getPlaceId() != null && plan.getSelectedPlaceIds().contains(stop.getPlaceId())) {
                            mapAnchored.add(stop.getPlaceId());
                        }
                    }
                }
                if (!mapAnchored.isEmpty()
                        && !groupMapContextService.placesExistOnMaps(maps, mapAnchored)) {
                    throw groupChanged("共建地图上的地点已变化，请重新生成规划");
                }
                if (!StringUtils.hasText(plan.getPlanningSourceVersion())) {
                    throw groupChanged("规划缺少共建地图快照，无法核验，请重新生成");
                }
                if (!plan.getPlanningSourceVersion()
                        .equals(groupMapContextService.fingerprint(plan.getSourceMapIds()))) {
                    throw groupChanged("共建地图或群内备注在规划期间发生变化，请重新生成规划");
                }
            }
            com.campus.schedule.dto.GroupBusyDto groupBusy =
                    scheduleService.groupBusy(userId, plan.getGroupId(), start, end);
            known = groupBusy.getKnown();
            unknown = groupBusy.getUnknownUserIds();
        } else {
            known = scheduleService.myBusy(userId, start, end);
            unknown = List.of();
        }
        // 冲突与衔接：除“正在被更新的谱系活动”外，一切已知占用一律参与判定（评审：不能豁免所有本人活动）
        java.time.format.DateTimeFormatter hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        for (com.campus.schedule.dto.BusyEntryDto entry : known) {
            boolean lineageSelf = lineageActivityId != null
                    && entry.getSourceType() != null
                    && "ACTIVITY".equals(entry.getSourceType())
                    && lineageActivityId.equals(entry.getSourceId());
            if (lineageSelf) {
                continue;
            }
            boolean overlap = entry.getStartTime().isBefore(end) && entry.getEndTime().isAfter(start);
            boolean headGap = !entry.getEndTime().isAfter(start)
                    && java.time.Duration.between(entry.getEndTime(), start).toMinutes()
                    < MIN_ADJACENT_TRANSITION_MINUTES;
            boolean tailGap = !entry.getStartTime().isBefore(end)
                    && java.time.Duration.between(end, entry.getStartTime()).toMinutes()
                    < MIN_ADJACENT_TRANSITION_MINUTES;
            if (overlap || headGap || tailGap) {
                String reasonKind = overlap ? "时间冲突" : headGap ? "开始前衔接不足" : "结束后的转场缓冲不足";
                throw groupChanged(reasonKind + "：" + entry.getStartTime().toLocalTime().format(hm)
                        + "-" + entry.getEndTime().toLocalTime().format(hm)
                        + " 的既有安排与本次活动相距过近，成员日程或安排已变化，请重新确认规划");
            }
        }
        if (!unknown.isEmpty()) {
            plan.setConstraintStatus("TENTATIVE");
        }
    }

    private BusinessException groupChanged(String message) {
        return new BusinessException(ResultCode.CONFLICT.getCode(), message);
    }

    /**
     * 只有业务事务成功提交后才记录“最终成功/活动已创建”；
     * 回滚时只记录失败，绝不出现 DB 已回滚但 Trace 显示 activity/created 的情况。
     */
    private void registerTraceAfterTransaction(TraceSaveRun run, Runnable onCommitted, Runnable onRolledBack) {
        if (run == null || run.isNoop()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        onCommitted.run();
                    } catch (Exception ex) {
                        log.warn("Trace afterCommit 记录失败（业务已提交）：{}", ex.getClass().getSimpleName());
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        try {
                            onRolledBack.run();
                        } catch (Exception ex) {
                            log.warn("Trace afterCompletion 记录失败（业务已回滚）：{}",
                                    ex.getClass().getSimpleName());
                        }
                    }
                    run.close();
                }
            });
        } else {
            // 没有事务同步上下文（例如测试直接调用非代理对象）：退化为立即记录，标注真实情况由事件 data 表达
            try {
                onCommitted.run();
            } catch (Exception ex) {
                onRolledBack.run();
            } finally {
                run.close();
            }
        }
    }

    /** 高德 POI/坐标站点转系统地点：先走 5m 查重，撞上已有地点就复用，不新建 */
    private Long ensurePlace(PlanStop stop) {
        CreatePlaceRequest request = new CreatePlaceRequest();
        request.setName(stop.getName());
        request.setCategoryId(DEFAULT_PLACE_CATEGORY_ID);
        request.setLongitude(BigDecimal.valueOf(stop.getLongitude()));
        request.setLatitude(BigDecimal.valueOf(stop.getLatitude()));
        request.setConfirmNew(false);
        PlaceCreateResponse response = placeService.createPlace(request);
        if (response.isCreated() && response.getPlace() != null) {
            return response.getPlace().getId();
        }
        if (!response.getCandidates().isEmpty()) {
            return response.getCandidates().get(0).getId();
        }
        // 唯一候选在查重后被并发删除的极端场景：强制新建
        request.setConfirmNew(true);
        PlaceCreateResponse retry = placeService.createPlace(request);
        if (retry.isCreated() && retry.getPlace() != null) {
            return retry.getPlace().getId();
        }
        throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(),
                "行程站点「" + stop.getName() + "」保存失败，请稍后重试");
    }
}
