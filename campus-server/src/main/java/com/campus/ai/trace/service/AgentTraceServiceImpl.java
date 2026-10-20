package com.campus.ai.trace.service;

import com.campus.ai.config.LlmProperties;
import com.campus.ai.entity.AiConversation;
import com.campus.ai.trace.adapter.TraceRun;
import com.campus.ai.trace.adapter.TraceSaveRun;
import com.campus.ai.trace.config.AgentTraceProperties;
import com.campus.ai.trace.domain.AgentEventType;
import com.campus.ai.trace.domain.AgentTraceEvent;
import com.campus.ai.trace.domain.AgentTraceSession;
import com.campus.ai.trace.domain.TraceStatus;
import com.campus.ai.trace.dto.TraceContext;
import com.campus.ai.trace.dto.TraceDraftVersionView;
import com.campus.ai.trace.dto.TraceEventRecord;
import com.campus.ai.trace.dto.TraceEventView;
import com.campus.ai.trace.dto.TraceLlmUsageStat;
import com.campus.ai.trace.dto.TraceRequest;
import com.campus.ai.trace.dto.TraceSessionView;
import com.campus.ai.trace.dto.TraceStartCommand;
import com.campus.ai.trace.dto.TraceTrigger;
import com.campus.ai.trace.mapper.AgentTraceEventMapper;
import com.campus.ai.trace.mapper.AgentTraceSessionMapper;
import com.campus.ai.trace.security.TraceDataRedactor;
import com.campus.ai.trace.store.AgentTraceWriter;
import com.campus.ai.trace.store.TraceEventStore;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.PageResult;
import com.campus.common.result.ResultCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent Trace P0 统一入口。
 */
@Slf4j
@Service
public class AgentTraceServiceImpl implements AgentTraceService {

    private final AgentTraceSessionMapper sessionMapper;
    private final AgentTraceEventMapper eventMapper;
    private final TraceEventStore store;
    private final AgentTraceWriter writer;
    private final TraceDataRedactor redactor;
    private final TraceAccessPolicy accessPolicy;
    private final AgentTraceExportService exportService;
    private final AgentTraceProperties properties;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final TraceCostCalculator costCalculator;

    private final ConcurrentHashMap<Long, Object> conversationLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> turnCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> activeRuns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingFinish> pendingFinishes = new ConcurrentHashMap<>();
    private final AtomicBoolean schemaWarned = new AtomicBoolean(false);

    public AgentTraceServiceImpl(AgentTraceSessionMapper sessionMapper,
                                 AgentTraceEventMapper eventMapper,
                                 TraceEventStore store,
                                 AgentTraceWriter writer,
                                 TraceDataRedactor redactor,
                                 TraceAccessPolicy accessPolicy,
                                 AgentTraceExportService exportService,
                                 AgentTraceProperties properties,
                                 LlmProperties llmProperties,
                                 @Qualifier("redisObjectMapper") ObjectMapper objectMapper,
                                 TraceCostCalculator costCalculator) {
        this.sessionMapper = sessionMapper;
        this.eventMapper = eventMapper;
        this.store = store;
        this.writer = writer;
        this.redactor = redactor;
        this.accessPolicy = accessPolicy;
        this.exportService = exportService;
        this.properties = properties;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.costCalculator = costCalculator;
    }

    // ---------- 写入侧 ----------

    @Override
    public TraceRun beginRun(AiConversation conversation,
                             TraceTrigger trigger,
                             int messageLength,
                             boolean hasUserLocation,
                             int historyLimit) {
        if (!active() || conversation == null || conversation.getId() == null
                || conversation.getUserId() == null) {
            return TraceRun.noop();
        }
        TraceTrigger actual = trigger == null ? TraceTrigger.chat() : trigger;
        try {
            SessionResolution resolution = resolve(command(conversation, actual), false, true, true);
            if (resolution == null) {
                return TraceRun.noop();
            }
            TraceContext context = new TraceContext(resolution.sessionId(),
                    UUID.randomUUID().toString(), conversation.getUserId(), conversation.getId(),
                    resolution.nextTurn(), 0);
            TraceRequest request = new TraceRequest(context, resolution.created(),
                    resolution.lastTurn(), resolution.nextTurn());
            TraceRun run = new TraceRun(this, redactor, request,
                    providerName(), llmProperties.getModel(), llmProperties.getMaxTokens());
            try {
                run.open(actual, messageLength, hasUserLocation, historyLimit);
            } catch (Throwable ex) {
                run.close();
                warnFailure("beginRun.open", ex);
                return TraceRun.noop();
            }
            return run;
        } catch (Throwable ex) {
            warnFailure("beginRun", ex);
            return TraceRun.noop();
        }
    }

    @Override
    public TraceSaveRun beginSave(AiConversation conversation,
                                  TraceTrigger trigger,
                                  boolean reuseCompleted) {
        if (!active() || conversation == null || conversation.getId() == null
                || conversation.getUserId() == null) {
            return TraceSaveRun.noop();
        }
        TraceTrigger actual = trigger == null ? TraceTrigger.save() : trigger;
        try {
            // 保存/修订本身不推进 turn；turn 只由真实 Agent run 推进
            SessionResolution resolution = resolve(command(conversation, actual),
                    reuseCompleted, true, false);
            if (resolution == null) {
                return TraceSaveRun.noop();
            }
            Integer turn = resolution.lastTurn() > 0 ? resolution.lastTurn() : null;
            TraceContext context = new TraceContext(resolution.sessionId(),
                    UUID.randomUUID().toString(), conversation.getUserId(), conversation.getId(),
                    turn, 0);
            TraceRequest request = new TraceRequest(context, resolution.created(),
                    resolution.lastTurn(), resolution.nextTurn());
            try {
                return new TraceSaveRun(this, request);
            } catch (Throwable ex) {
                onRunClosed(resolution.sessionId());
                warnFailure("beginSave.create", ex);
                return TraceSaveRun.noop();
            }
        } catch (Throwable ex) {
            warnFailure("beginSave", ex);
            return TraceSaveRun.noop();
        }
    }

    @Override
    public String ensureSessionForConversation(AiConversation conversation,
                                               TraceTrigger trigger,
                                               boolean reuseCompleted) {
        if (!active() || conversation == null || conversation.getId() == null
                || conversation.getUserId() == null) {
            return null;
        }
        TraceTrigger actual = trigger == null ? TraceTrigger.chat() : trigger;
        try {
            SessionResolution resolution = resolve(command(conversation, actual),
                    reuseCompleted, false, false);
            return resolution == null ? null : resolution.sessionId();
        } catch (Throwable ex) {
            warnFailure("ensureSession", ex);
            return null;
        }
    }

    @Override
    public void record(TraceEventRecord event) {
        if (!active() || event == null || event.getSessionId() == null
                || event.getSessionId().isBlank()) {
            return;
        }
        try {
            Map<String, Object> safe = redactor.sanitize(event.getData());
            TraceEventRecord sanitized = event.toBuilder().data(safe).build();
            writer.enqueue(sanitized);
        } catch (Throwable ex) {
            warnFailure("record", ex);
        }
    }

    @Override
    public void finishSession(String sessionId, String status, String reason) {
        if (!active() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        String actualStatus = TraceStatus.isSessionStatus(status) ? status : TraceStatus.COMPLETED;
        synchronized (lockFor(sessionId)) {
            if (activeCount(sessionId) > 0) {
                pendingFinishes.put(sessionId, new PendingFinish(actualStatus, reason));
                return;
            }
            AgentTraceSession session;
            try {
                session = sessionMapper.selectById(sessionId);
            } catch (Throwable ex) {
                warnFailure("finishSession", ex);
                return;
            }
            if (session == null || TraceStatus.isTerminalSessionStatus(session.getStatus())) {
                return;
            }
            long duration = durationMs(session);
            long effectiveDropped = safeLong(session.getDroppedCount())
                    + writer.pendingDroppedCount(sessionId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", actualStatus);
            data.put("reason", reason);
            data.put("durationMs", duration);
            data.put("droppedCount", effectiveDropped);
            record(TraceEventRecord.builder()
                    .sessionId(sessionId)
                    .traceId(UUID.randomUUID().toString())
                    .occurredAtMs(System.currentTimeMillis())
                    .turn(0)
                    .step(null)
                    .type(AgentEventType.SESSION_END)
                    .status(actualStatus)
                    .durationMs(duration)
                    .data(data)
                    .critical(true)
                    .build());
            turnCounters.remove(sessionId);
            pendingFinishes.remove(sessionId);
        }
    }

    @Override
    public void onRunClosed(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        PendingFinish pending = null;
        synchronized (lockFor(sessionId)) {
            AtomicInteger count = activeRuns.get(sessionId);
            if (count == null) {
                return;
            }
            if (count.decrementAndGet() <= 0) {
                activeRuns.remove(sessionId);
                pending = pendingFinishes.remove(sessionId);
            }
        }
        if (pending != null) {
            finishSession(sessionId, pending.status(), pending.reason());
        }
    }

    @Override
    public void bindContext(String sessionId, Long sharedMapId, Long groupId) {
        if (!active() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            store.bindContext(sessionId, sharedMapId, groupId);
        } catch (Throwable ex) {
            warnFailure("bindContext", ex);
        }
    }

    @Override
    public void bindDraft(String sessionId, Long draftId) {
        if (!active() || sessionId == null || sessionId.isBlank() || draftId == null) {
            return;
        }
        try {
            store.bindDraft(sessionId, draftId);
        } catch (Throwable ex) {
            warnFailure("bindDraft", ex);
        }
    }

    @Override
    public boolean isRunActive(String sessionId) {
        AtomicInteger count = activeRuns.get(sessionId);
        return count != null && count.get() > 0;
    }

    // ---------- 查询/导出 ----------

    @Override
    public TraceSessionView getSession(String sessionId, Long requesterUserId) {
        AgentTraceSession session = accessPolicy.requireReadable(sessionId, requesterUserId);
        TraceSessionView view = toView(session, loadUsageStats(List.of(session)).get(session.getId()));
        enrichDetail(view, sessionId);
        return view;
    }

    @Override
    public PageResult<TraceSessionView> listSessions(Long requesterUserId, String status,
                                                     int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String statusFilter = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (statusFilter != null && !TraceStatus.isSessionStatus(statusFilter)) {
            statusFilter = null;
        }
        long total = sessionMapper.countPage(requesterUserId, statusFilter);
        List<AgentTraceSession> sessions = sessionMapper.selectPage(requesterUserId, statusFilter,
                (safePage - 1) * safeSize, safeSize);
        Map<String, TraceLlmUsageStat> usageStats = loadUsageStats(sessions);
        List<TraceSessionView> list = sessions.stream()
                .map(session -> toView(session, usageStats.get(session.getId())))
                .toList();
        return PageResult.of(list, safePage, safeSize, total);
    }

    @Override
    public PageResult<TraceEventView> listEvents(String sessionId, Long requesterUserId,
                                                 String type, String callId, Integer step,
                                                 Long afterSeq, int page, int size) {
        accessPolicy.requireReadable(sessionId, requesterUserId);
        if (afterSeq != null && afterSeq < 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "afterSeq 不能为负数");
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size));
        String typeFilter = type == null || type.isBlank() ? null : type.trim();
        String callFilter = callId == null || callId.isBlank() ? null : callId.trim();
        Integer stepFilter = step == null || step < 0 ? null : step;
        long total = eventMapper.countPage(sessionId, typeFilter, callFilter, stepFilter);
        List<AgentTraceEvent> rows;
        int responsePage;
        if (afterSeq != null) {
            // 增量模式：afterSeq 优先于 page，page 固定返回 1
            rows = eventMapper.selectAfterSeq(sessionId, typeFilter, callFilter, stepFilter,
                    afterSeq, safeSize);
            responsePage = 1;
        } else {
            rows = eventMapper.selectPage(sessionId, typeFilter, callFilter, stepFilter,
                    (safePage - 1) * safeSize, safeSize);
            responsePage = safePage;
        }
        List<TraceEventView> list = rows.stream().map(this::toEventView).toList();
        return PageResult.of(list, responsePage, safeSize, total);
    }

    @Override
    public void exportJsonl(String sessionId, Long requesterUserId, OutputStream out) throws IOException {
        exportService.exportJsonl(sessionId, requesterUserId, out);
    }

    @Override
    public void flush() {
        writer.flushNow();
    }

    // ---------- 内部 ----------

    private SessionResolution resolve(TraceStartCommand command,
                                      boolean reuseCompleted,
                                      boolean markActive,
                                      boolean advanceTurn) {
        if (!active() || command == null || command.conversationId() == null
                || command.userId() == null) {
            return null;
        }
        Object lock = conversationLocks.computeIfAbsent(command.conversationId(), key -> new Object());
        synchronized (lock) {
            AgentTraceSession latest;
            try {
                latest = sessionMapper.selectLatestByConversation(command.conversationId());
            } catch (Throwable ex) {
                warnFailure("resolve.select", ex);
                return null;
            }
            boolean created = false;
            boolean reuse = latest != null
                    && (TraceStatus.RUNNING.equals(latest.getStatus())
                    || (reuseCompleted && TraceStatus.COMPLETED.equals(latest.getStatus())));
            if (!reuse) {
                latest = startSession(command);
                if (latest == null) {
                    return null;
                }
                created = true;
            }
            String sessionId = latest.getId();
            AtomicInteger counter = turnCounters.computeIfAbsent(sessionId,
                    key -> new AtomicInteger(safeMaxTurn(key)));
            int lastTurn;
            int nextTurn;
            if (advanceTurn) {
                lastTurn = counter.get();
                nextTurn = counter.incrementAndGet();
            } else {
                lastTurn = counter.get();
                nextTurn = lastTurn + 1;
            }
            if (markActive) {
                activeRuns.computeIfAbsent(sessionId, key -> new AtomicInteger()).incrementAndGet();
            }
            return new SessionResolution(sessionId, created, lastTurn, nextTurn);
        }
    }

    private AgentTraceSession startSession(TraceStartCommand command) {
        AgentTraceSession session = new AgentTraceSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(command.userId());
        session.setConversationId(command.conversationId());
        session.setSharedMapId(command.trigger() == null ? null : command.trigger().sharedMapId());
        session.setGroupId(command.trigger() == null ? null : command.trigger().groupId());
        session.setTriggerSource(command.trigger() == null
                ? TraceTrigger.CHAT : command.trigger().sourceOrDefault());
        session.setProvider(command.provider());
        session.setModel(command.model());
        session.setStatus(TraceStatus.RUNNING);
        session.setStartedAt(LocalDateTime.now());
        session.setEventCount(0);
        session.setToolCallCount(0);
        session.setErrorCount(0);
        session.setDroppedCount(0);
        session.setNextSeq(0L);
        try {
            store.createSession(session);
        } catch (Throwable ex) {
            warnFailure("startSession", ex);
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("trigger", session.getTriggerSource());
        data.put("conversationId", session.getConversationId());
        data.put("provider", session.getProvider());
        data.put("model", session.getModel());
        record(TraceEventRecord.builder()
                .sessionId(session.getId())
                .traceId(UUID.randomUUID().toString())
                .occurredAtMs(System.currentTimeMillis())
                .turn(0)
                .step(0)
                .type(AgentEventType.SESSION_START)
                .status(TraceStatus.RUNNING)
                .data(data)
                .critical(true)
                .build());
        return session;
    }

    private TraceStartCommand command(AiConversation conversation, TraceTrigger trigger) {
        return new TraceStartCommand(conversation.getId(), conversation.getUserId(), trigger,
                providerName(), llmProperties.getModel());
    }

    private int safeMaxTurn(String sessionId) {
        try {
            Integer max = eventMapper.selectMaxTurn(sessionId);
            return max == null ? 0 : max;
        } catch (Throwable ex) {
            warnFailure("selectMaxTurn", ex);
            return 0;
        }
    }

    private Map<String, TraceLlmUsageStat> loadUsageStats(List<AgentTraceSession> sessions) {
        if (sessions == null || sessions.isEmpty()) {
            return Map.of();
        }
        List<String> ids = sessions.stream()
                .map(AgentTraceSession::getId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, TraceLlmUsageStat> result = new HashMap<>();
            for (TraceLlmUsageStat stat : eventMapper.selectLlmUsageStats(ids)) {
                if (stat != null && stat.getSessionId() != null) {
                    result.put(stat.getSessionId(), stat);
                }
            }
            return result;
        } catch (Throwable ex) {
            warnFailure("selectLlmUsageStats", ex);
            return Map.of();
        }
    }

    private TraceSessionView toView(AgentTraceSession session, TraceLlmUsageStat usage) {
        TraceSessionView view = new TraceSessionView();
        view.setSessionId(session.getId());
        view.setConversationId(session.getConversationId());
        view.setUserId(session.getUserId());
        view.setSharedMapId(session.getSharedMapId());
        view.setGroupId(session.getGroupId());
        view.setDraftId(session.getDraftId());
        view.setTriggerSource(session.getTriggerSource());
        view.setProvider(session.getProvider());
        view.setModel(session.getModel());
        view.setStatus(session.getStatus());
        view.setStartedAt(session.getStartedAt());
        view.setEndedAt(session.getEndedAt());
        view.setDurationMs(session.getDurationMs());
        view.setPromptTokens(session.getPromptTokens());
        view.setCompletionTokens(session.getCompletionTokens());
        view.setTotalTokens(session.getTotalTokens());
        view.setEventCount(session.getEventCount());
        view.setToolCallCount(session.getToolCallCount());
        view.setErrorCount(session.getErrorCount());
        long pendingEvents = writer.pendingCount(session.getId());
        long effectiveDropped = safeLong(session.getDroppedCount())
                + writer.pendingDroppedCount(session.getId());
        view.setDroppedCount((int) Math.min(Integer.MAX_VALUE, effectiveDropped));
        view.setLastErrorCode(session.getLastErrorCode());
        view.setLastSeq(session.getNextSeq() == null ? 0L : session.getNextSeq());
        view.setPendingEvents(pendingEvents);
        view.setComplete(TraceSessionView.isComplete(session.getStatus(),
                view.getDroppedCount(), pendingEvents));
        TraceCostCalculator.CostResult cost = costCalculator.calculate(
                session.getModel(), session.getPromptTokens(), session.getCompletionTokens(),
                usage, effectiveDropped);
        view.setEstimatedCost(cost.amount());
        view.setCostCurrency(cost.currency());
        view.setCostStatus(cost.status());
        view.setCostPricingVersion(cost.pricingVersion());
        return view;
    }

    private long safeLong(Number value) {
        return value == null ? 0L : Math.max(0L, value.longValue());
    }

    private TraceEventView toEventView(AgentTraceEvent event) {
        TraceEventView view = new TraceEventView();
        view.setEventId(event.getId());
        view.setType(event.getEventType());
        view.setSeq(event.getSeq());
        view.setTime(event.getOccurredAtMs());
        view.setTurn(event.getTurnNo());
        view.setStep(event.getStepNo());
        view.setCallId(event.getCallId());
        view.setParentEventId(event.getParentEventId());
        view.setStatus(event.getStatus());
        view.setDurationMs(event.getDurationMs());
        view.setTool(event.getToolName());
        view.setErrorCode(event.getErrorCode());
        try {
            view.setData(event.getPayloadJson() == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(event.getPayloadJson()));
        } catch (Exception ex) {
            view.setData(objectMapper.createObjectNode());
        }
        return view;
    }

    /** P1 增强：详情附耗时统计与草稿版本聚合；失败不影响详情主数据 */
    private void enrichDetail(TraceSessionView view, String sessionId) {
        try {
            view.setStats(TraceStatsCalculator.calculate(eventMapper.selectDurations(sessionId)));
            view.setDraftVersions(buildDraftVersions(eventMapper.selectPlanEvents(sessionId)));
        } catch (Throwable ex) {
            log.warn("Agent Trace 详情统计失败 sessionId={}：{}", sessionId, ex.getClass().getSimpleName());
        }
    }

    private List<TraceDraftVersionView> buildDraftVersions(List<AgentTraceEvent> planEvents) {
        if (planEvents == null || planEvents.isEmpty()) {
            return List.of();
        }
        Map<Integer, TraceDraftVersionView> byVersion = new TreeMap<>();
        for (AgentTraceEvent event : planEvents) {
            String type = event.getEventType() == null ? "" : event.getEventType();
            JsonNode data = parsePayload(event);
            switch (type) {
                case AgentEventType.PLAN_DRAFT -> applyPlanDraft(byVersion, data);
                case AgentEventType.PLAN_VALIDATE -> applyPlanValidate(byVersion, data);
                case AgentEventType.PLAN_REVISE -> applyPlanRevise(byVersion, data);
                default -> {
                }
            }
        }
        return new ArrayList<>(byVersion.values());
    }

    private void applyPlanDraft(Map<Integer, TraceDraftVersionView> byVersion, JsonNode data) {
        int version = data.path("draftVersion").asInt(0);
        if (version <= 0) {
            return;
        }
        TraceDraftVersionView view = draftVersionEntry(byVersion, version);
        if (data.hasNonNull("draftId")) {
            view.setDraftId(data.get("draftId").asLong());
        }
        // plan/draft 与 plan/validate 的摘要是扁平结构（TraceRun.planSummary 直接铺平）
        view.setStopCount(intOrNull(data.path("stopCount")));
        view.setTotalTravelMinutes(intOrNull(data.path("totalTravelMinutes")));
        view.setRouteConfidence(textOrNull(data.path("routeConfidence")));
        view.setConstraintStatus(textOrNull(data.path("constraintStatus")));
        view.setSelectedPlaceIds(longList(data.path("selectedPlaceIds")));
        view.setSourceMapIds(longList(data.path("sourceMapIds")));
    }

    private void applyPlanValidate(Map<Integer, TraceDraftVersionView> byVersion, JsonNode data) {
        int version = data.path("draftVersion").asInt(0);
        if (version <= 0) {
            return;
        }
        TraceDraftVersionView view = draftVersionEntry(byVersion, version);
        // SUBMIT 阶段结果优先；保存/修订阶段的复核不覆盖
        if (view.getValidatePassed() == null || "SUBMIT".equals(data.path("phase").asText(""))) {
            view.setValidatePassed(data.path("passed").asBoolean());
            view.setValidatePhase(data.path("phase").asText(null));
            view.setRuleIds(stringList(data.path("ruleIds")));
        }
    }

    private void applyPlanRevise(Map<Integer, TraceDraftVersionView> byVersion, JsonNode data) {
        int toVersion = data.path("toVersion").asInt(0);
        if (toVersion <= 0) {
            return;
        }
        TraceDraftVersionView view = draftVersionEntry(byVersion, toVersion);
        view.setReviseChanged(data.path("changed").asBoolean());
        view.setRevisedFromVersion(data.path("fromVersion").asInt(0));
    }

    private TraceDraftVersionView draftVersionEntry(Map<Integer, TraceDraftVersionView> byVersion,
                                                    int version) {
        return byVersion.computeIfAbsent(version, key -> {
            TraceDraftVersionView created = new TraceDraftVersionView();
            created.setVersion(key);
            return created;
        });
    }

    private JsonNode parsePayload(AgentTraceEvent event) {
        try {
            return event.getPayloadJson() == null
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(event.getPayloadJson());
        } catch (Exception ex) {
            return objectMapper.createObjectNode();
        }
    }

    private Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    private List<Long> longList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<Long> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asLong()));
        return values;
    }

    private List<String> stringList(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private String providerName() {
        String baseUrl = llmProperties.getBaseUrl();
        return baseUrl != null && baseUrl.toLowerCase().contains("dashscope")
                ? "dashscope-compatible" : "openai-compatible";
    }

    private Object lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, key -> new Object());
    }

    private int activeCount(String sessionId) {
        AtomicInteger count = activeRuns.get(sessionId);
        return count == null ? 0 : count.get();
    }

    private long durationMs(AgentTraceSession session) {
        if (session.getStartedAt() == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(session.getStartedAt(), LocalDateTime.now()).toMillis());
    }

    private boolean active() {
        return properties.isTraceActive();
    }

    private void warnFailure(String operation, Throwable ex) {
        if (schemaWarned.compareAndSet(false, true)) {
            log.warn("Agent Trace 暂不可用（业务继续）operation={}：{}；"
                            + "请确认已执行 db/migration/20261019_01__create_agent_trace.sql",
                    operation, ex.getClass().getSimpleName());
        } else {
            log.debug("Agent Trace 写入失败 operation={}：{}", operation, ex.getClass().getSimpleName());
        }
    }

    private record SessionResolution(String sessionId, boolean created, int lastTurn, int nextTurn) {
    }

    private record PendingFinish(String status, String reason) {
    }
}
