package com.campus.ai.controller;

import com.campus.ai.dto.ChatDetailResponse;
import com.campus.ai.dto.ChatMessageItem;
import com.campus.ai.dto.ChatSessionResponse;
import com.campus.ai.dto.PlanDto;
import com.campus.ai.dto.PlanSaveRequest;
import com.campus.ai.dto.PlanSaveResponse;
import com.campus.ai.dto.RevisePlanRequest;
import com.campus.ai.dto.RevisePlanResponse;
import com.campus.ai.dto.SendMessageRequest;
import com.campus.ai.entity.AiConversation;
import com.campus.ai.entity.AiMessage;
import com.campus.ai.mapper.AiConversationMapper;
import com.campus.ai.mapper.AiMessageMapper;
import com.campus.ai.plan.PlanValidator;
import com.campus.ai.service.AgentChatListener;
import com.campus.ai.service.AgentService;
import com.campus.ai.service.PlanSaveService;
import com.campus.ai.trace.dto.TraceTrigger;
import com.campus.ai.trace.service.AgentTraceService;
import com.campus.ai.travel.RouteInfo;
import com.campus.ai.travel.TravelInfoProvider;
import com.campus.common.exception.BusinessException;
import com.campus.common.ratelimit.RateLimiter;
import com.campus.common.result.Result;
import com.campus.common.result.ResultCode;
import com.campus.security.user.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 助手：会话管理 + 对话（SSE 流式）+ 草稿确认保存。
 * SSE 事件：status（工具/阶段提示）、delta（最终答复增量）、plan（草稿 JSON）、done、error。
 */
@Slf4j
@Tag(name = "AI 助手")
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private static final long SSE_TIMEOUT_MILLIS = 150_000L;
    private static final ExecutorService SSE_POOL = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "assistant-sse");
        thread.setDaemon(true);
        return thread;
    });

    private final AgentService agentService;
    private final PlanSaveService planSaveService;
    private final PlanValidator planValidator;
    private final TravelInfoProvider travelInfoProvider;
    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final RateLimiter rateLimiter;
    private final AgentTraceService traceService;
    private final @Qualifier("redisObjectMapper") ObjectMapper objectMapper;

    @Operation(summary = "创建规划会话")
    @PostMapping("/chats")
    public Result<ChatSessionResponse> create() {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(SecurityUtils.getUserId());
        conversation.setStatus(AiConversation.STATUS_CHATTING);
        conversation.setPlanVersion(0);
        conversationMapper.insert(conversation);
        return Result.success(toSession(conversation));
    }

    @Operation(summary = "我的会话列表")
    @GetMapping("/chats")
    public Result<List<ChatSessionResponse>> list() {
        return Result.success(conversationMapper.selectByUser(SecurityUtils.getUserId())
                .stream().map(this::toSession).toList());
    }

    @Operation(summary = "会话详情（历史消息+当前草稿）")
    @GetMapping("/chats/{id}")
    public Result<ChatDetailResponse> detail(@PathVariable Long id) {
        AiConversation conversation = requireOwned(id);
        ChatDetailResponse response = new ChatDetailResponse();
        response.setConversation(toSession(conversation));
        List<AiMessage> recent = messageMapper.selectRecent(id, 30);
        Collections.reverse(recent);
        response.setMessages(recent.stream().map(this::toMessageItem).toList());
        if (conversation.getPlanJson() != null && !conversation.getPlanJson().isBlank()) {
            try {
                response.setPlan(objectMapper.readValue(conversation.getPlanJson(), PlanDto.class));
            } catch (Exception ex) {
                log.warn("会话 {} 草稿解析失败：{}", id, ex.toString());
            }
        }
        return Result.success(response);
    }

    @Operation(summary = "发送消息（SSE 流式返回，event= status/delta/plan/done/error）")
    @PostMapping(path = "/chats/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable Long id, @Valid @RequestBody SendMessageRequest request) {
        Long userId = SecurityUtils.getUserId();
        rateLimiter.check("ai:chat:" + userId, 10, Duration.ofMinutes(1));
        AiConversation conversation = requireOwned(id);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(throwable -> cancelled.set(true));
        AgentChatListener listener = sseListener(emitter, cancelled);

        // SecurityContext 传到异步线程：工具/校验层复用业务侧 member 校验都依赖它
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        SSE_POOL.submit(() -> {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                agentService.run(conversation, request.getContent(), listener, request.getUserLocation(),
                        TraceTrigger.chat());
            } catch (Exception ex) {
                log.error("Agent 执行异常 conversation={}：{}", conversation.getId(), ex.toString());
                if (!cancelled.get()) {
                    listener.onError("助手处理异常，请稍后重试");
                }
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        return emitter;
    }

    @Operation(summary = "保存当前草稿为活动（幂等；body 可选带 expectedPlanVersion 防旧版本覆盖，不一致返回 409）")
    @PostMapping("/chats/{id}/plan/save")
    public Result<PlanSaveResponse> savePlan(@PathVariable Long id,
                                             @RequestBody(required = false) PlanSaveRequest request) {
        // 在进入 @Transactional 保存前预创建 trace session：避免业务事务内额外申请连接
        AiConversation conversation = requireOwned(id);
        traceService.ensureSessionForConversation(conversation, TraceTrigger.save(), true);
        return Result.success(planSaveService.save(id,
                request == null ? null : request.getExpectedPlanVersion()));
    }

    @Operation(summary = "修订草稿（前端编辑后重校验 + 版本+1；expectedPlanVersion 不符返回 409；asNewDraft=true 时复制为新草稿以便另建活动）")
    @PostMapping("/chats/{id}/plan/revise")
    public Result<RevisePlanResponse> revisePlan(@PathVariable Long id, @Valid @RequestBody RevisePlanRequest request) {
        AiConversation conversation = requireOwned(id);
        traceService.ensureSessionForConversation(conversation, TraceTrigger.revise(), false);
        return Result.success(planSaveService.revise(id, request.getPlan(),
                request.getExpectedPlanVersion(), request.getAsNewDraft()));
    }

    /** 草稿合法性预检（前端展示前先向后端确认，避免保存期才发现问题） */
    @Operation(summary = "草稿预检")
    @PostMapping("/chats/{id}/plan/validate")
    public Result<PlanDto> validatePlan(@PathVariable Long id, @RequestBody PlanDto plan) {
        AiConversation conversation = requireOwned(id);
        return Result.success(planValidator.validate(plan, conversation.getUserId()));
    }

    @Operation(summary = "站间路线预览（折线与规划阶段同源，10 分钟缓存不重复计费）")
    @GetMapping("/routes")
    public Result<RouteInfo> route(@RequestParam double fromLng, @RequestParam double fromLat,
                                   @RequestParam double toLng, @RequestParam double toLat,
                                   @RequestParam(defaultValue = "WALK") String mode) {
        return Result.success(travelInfoProvider.searchRoute(mode, fromLng, fromLat, toLng, toLat));
    }

    private AiConversation requireOwned(Long id) {
        AiConversation conversation = conversationMapper.selectById(id);
        if (conversation == null || !conversation.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "会话不存在");
        }
        return conversation;
    }

    private ChatSessionResponse toSession(AiConversation conversation) {
        ChatSessionResponse session = new ChatSessionResponse();
        session.setId(conversation.getId());
        session.setTitle(conversation.getTitle() == null ? "新的规划" : conversation.getTitle());
        session.setStatus(conversation.getStatus());
        session.setPlanVersion(conversation.getPlanVersion());
        session.setSavedActivityId(conversation.getSavedActivityId());
        session.setUpdatedAt(conversation.getUpdatedAt());
        return session;
    }

    private ChatMessageItem toMessageItem(AiMessage message) {
        ChatMessageItem item = new ChatMessageItem();
        item.setId(message.getId());
        item.setRole(message.getRole());
        item.setContent(message.getContent());
        item.setGeoJson(message.getGeoJson());
        item.setCreatedAt(message.getCreatedAt());
        return item;
    }

    private AgentChatListener sseListener(SseEmitter emitter, AtomicBoolean cancelled) {
        return new AgentChatListener() {
            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }

            @Override
            public void onStatus(String stage) {
                sendSafe("status", stage);
            }

            @Override
            public void onDelta(String text) {
                sendSafe("delta", text);
            }

            @Override
            public void onPlan(PlanDto plan) {
                sendSafe("plan", toJson(plan));
            }

            @Override
            public void onGeo(java.util.List<com.campus.ai.dto.GeoEvent> events) {
                sendSafe("geo", toJson(java.util.Map.of("events", events)));
            }

            @Override
            public void onDone() {
                sendSafe("done", "{}");
                complete();
            }

            @Override
            public void onError(String message) {
                sendSafe("error", toJson(java.util.Map.of("message", message)));
                complete();
            }

            private void sendSafe(String event, String data) {
                try {
                    emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException ex) {
                    cancelled.set(true);
                }
            }

            private void complete() {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 客户端已断开
                }
            }
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
