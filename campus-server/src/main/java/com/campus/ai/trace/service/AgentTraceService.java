package com.campus.ai.trace.service;

import com.campus.ai.entity.AiConversation;
import com.campus.ai.trace.adapter.TraceRun;
import com.campus.ai.trace.adapter.TraceSaveRun;
import com.campus.ai.trace.dto.TraceEventRecord;
import com.campus.ai.trace.dto.TraceEventView;
import com.campus.ai.trace.dto.TraceSessionView;
import com.campus.ai.trace.dto.TraceTrigger;
import com.campus.common.result.PageResult;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 统一追踪入口。所有方法必须“失败不阻断业务”：实现内部捕获异常并记录告警。
 */
public interface AgentTraceService {

    TraceRun beginRun(AiConversation conversation,
                      TraceTrigger trigger,
                      int messageLength,
                      boolean hasUserLocation,
                      int historyLimit);

    TraceSaveRun beginSave(AiConversation conversation,
                           TraceTrigger trigger,
                           boolean reuseCompleted);

    /** 控制器在进入 @Transactional 保存前预创建 session，避免业务事务内额外申请连接 */
    String ensureSessionForConversation(AiConversation conversation,
                                        TraceTrigger trigger,
                                        boolean reuseCompleted);

    void record(TraceEventRecord event);

    /** 只结束 RUNNING session；重复调用是幂等的 */
    void finishSession(String sessionId, String status, String reason);

    void onRunClosed(String sessionId);

    void bindContext(String sessionId, Long sharedMapId, Long groupId);

    void bindDraft(String sessionId, Long draftId);

    boolean isRunActive(String sessionId);

    TraceSessionView getSession(String sessionId, Long requesterUserId);

    PageResult<TraceSessionView> listSessions(Long requesterUserId, String status, int page, int size);

    PageResult<TraceEventView> listEvents(String sessionId, Long requesterUserId,
                                          String type, String callId, Integer step,
                                          Long afterSeq, int page, int size);

    void exportJsonl(String sessionId, Long requesterUserId, OutputStream out) throws IOException;

    /** 等待队列落库；测试与优雅关闭使用 */
    void flush();
}
