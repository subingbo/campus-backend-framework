package com.campus.ai.trace.service;

import com.campus.ai.trace.domain.AgentTraceSession;
import com.campus.ai.trace.mapper.AgentTraceSessionMapper;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import org.springframework.stereotype.Component;

/**
 * P0 访问策略：只看自己的 Trace。
 * 非本人与不存在统一 404，避免通过 sessionId 探测他人会话；
 * 管理员诊断权限、群成员共享查看不在 P0 暴露。
 */
@Component
public class TraceAccessPolicy {

    private final AgentTraceSessionMapper sessionMapper;

    public TraceAccessPolicy(AgentTraceSessionMapper sessionMapper) {
        this.sessionMapper = sessionMapper;
    }

    public AgentTraceSession requireReadable(String sessionId, Long requesterUserId) {
        if (requesterUserId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "追踪会话不存在");
        }
        AgentTraceSession session = sessionMapper.selectByIdAndUser(sessionId, requesterUserId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "追踪会话不存在");
        }
        return session;
    }
}
