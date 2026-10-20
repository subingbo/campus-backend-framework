package com.campus.ai.trace.controller;

import com.campus.ai.trace.config.AgentTraceProperties;
import com.campus.ai.trace.dto.TraceEventView;
import com.campus.ai.trace.dto.TraceSessionView;
import com.campus.ai.trace.service.AgentTraceService;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.PageResult;
import com.campus.common.result.Result;
import com.campus.common.result.ResultCode;
import com.campus.security.user.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Agent Trace P0 查询与 JSONL 导出。
 * 所有接口都要求登录；数据范围由 TraceAccessPolicy 限定为本人（非本人 404）。
 */
@Tag(name = "Agent 追踪")
@RestController
@RequestMapping("/api/agent/traces")
@RequiredArgsConstructor
public class AgentTraceController {

    private static final String NDJSON = "application/x-ndjson;charset=UTF-8";

    private final AgentTraceService traceService;
    private final AgentTraceProperties traceProperties;

    @Operation(summary = "我的追踪会话列表")
    @GetMapping
    public Result<PageResult<TraceSessionView>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status) {
        return Result.success(traceService.listSessions(SecurityUtils.getUserId(), status, page, size));
    }

    @Operation(summary = "追踪会话详情")
    @GetMapping("/{sessionId}")
    public Result<TraceSessionView> detail(@PathVariable String sessionId) {
        return Result.success(traceService.getSession(sessionId, SecurityUtils.getUserId()));
    }

    @Operation(summary = "事件分页（按 seq 升序）")
    @GetMapping("/{sessionId}/events")
    public Result<PageResult<TraceEventView>> events(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String callId,
            @RequestParam(required = false) Integer step,
            @RequestParam(required = false) Long afterSeq) {
        return Result.success(traceService.listEvents(sessionId, SecurityUtils.getUserId(),
                type, callId, step, afterSeq, page, size));
    }

    @Operation(summary = "导出 JSONL（Content-Type: application/x-ndjson）")
    @GetMapping("/{sessionId}/export")
    public ResponseEntity<StreamingResponseBody> export(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "jsonl") String format) {
        if (!"jsonl".equalsIgnoreCase(format)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "仅支持 format=jsonl");
        }
        if (!traceProperties.getExport().isEnabled()) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "追踪导出未启用");
        }
        Long userId = SecurityUtils.getUserId();
        // 先做权限校验：越权/不存在以 JSON 404 返回，而不是开始流式输出后才失败
        traceService.getSession(sessionId, userId);
        String safeId = sessionId == null ? "session" : sessionId.replaceAll("[^A-Za-z0-9-]", "");
        String filename = "session-" + (safeId.isBlank() ? "unknown" : safeId) + ".jsonl";
        StreamingResponseBody body = out -> traceService.exportJsonl(sessionId, userId, out);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(NDJSON))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
