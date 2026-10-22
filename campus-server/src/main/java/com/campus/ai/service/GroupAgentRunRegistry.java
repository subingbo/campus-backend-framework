package com.campus.ai.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 群 Agent 运行注册表：runId 绑定一次运行，同一群会话只允许一个活动运行。
 * 终态 DONE/FAILED/CANCELLED 通过 CAS 只允许进入一次，避免延迟取消/重复回调互相覆盖。
 */
@Component
public class GroupAgentRunRegistry {

    public enum Status {
        RUNNING,
        CANCELLING,
        CANCELLED,
        FAILED,
        DONE,
        BUSY
    }

    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final Map<Long, String> activeByConversation = new ConcurrentHashMap<>();

    /** 同一群会话已有运行则返回 null（调用方回 BUSY） */
    public Run start(Long conversationId, Long askerId) {
        Run run = new Run(conversationId, askerId);
        if (activeByConversation.putIfAbsent(conversationId, run.getRunId()) != null) {
            return null;
        }
        runs.put(run.getRunId(), run);
        return run;
    }

    public Run find(String runId) {
        return runId == null ? null : runs.get(runId);
    }

    public Run active(Long conversationId) {
        String runId = activeByConversation.get(conversationId);
        return runId == null ? null : runs.get(runId);
    }

    /** 只有第一次终态切换成功；重复 DONE/FAILED/CANCELLED 不会覆盖 */
    public boolean finish(Run run, Status status) {
        if (run == null || !run.terminate(status)) {
            return false;
        }
        runs.remove(run.getRunId());
        activeByConversation.remove(run.getConversationId(), run.getRunId());
        return true;
    }

    public static final class Run {
        private final String runId = UUID.randomUUID().toString();
        private final Long conversationId;
        private final Long askerId;
        private final long startedAt = System.currentTimeMillis();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final AtomicReference<Status> terminal = new AtomicReference<>();
        private final AtomicInteger toolCount = new AtomicInteger();
        private volatile String currentTool;
        private volatile String stage;
        private volatile Long aiConversationId;
        private volatile String error;

        private Run(Long conversationId, Long askerId) {
            this.conversationId = conversationId;
            this.askerId = askerId;
        }

        public String getRunId() {
            return runId;
        }

        public Long getConversationId() {
            return conversationId;
        }

        public Long getAskerId() {
            return askerId;
        }

        public boolean requestCancel() {
            return terminal.get() == null && cancelRequested.compareAndSet(false, true);
        }

        public boolean isCancelRequested() {
            return cancelRequested.get();
        }

        public boolean terminate(Status status) {
            return terminal.compareAndSet(null, status);
        }

        public boolean isFinished() {
            return terminal.get() != null;
        }

        public void toolStarted(String toolName) {
            toolCount.incrementAndGet();
            currentTool = toolName;
        }

        public int getToolCount() {
            return toolCount.get();
        }

        public String getCurrentTool() {
            return currentTool;
        }

        public String getStage() {
            return stage;
        }

        public void setStage(String stage) {
            this.stage = stage;
        }

        public Long getAiConversationId() {
            return aiConversationId;
        }

        public void setAiConversationId(Long aiConversationId) {
            this.aiConversationId = aiConversationId;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public long elapsedMs() {
            return Math.max(0, System.currentTimeMillis() - startedAt);
        }
    }
}
