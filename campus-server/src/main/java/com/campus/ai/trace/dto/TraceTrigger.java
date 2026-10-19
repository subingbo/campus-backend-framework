package com.campus.ai.trace.dto;

/**
 * 追踪触发源（由调用端显式传入，模型和前端不可伪造）。
 * groupChat 的 mapId/groupId 来自 GroupAgentService 的后端会话，不读取 ThreadLocal。
 */
public record TraceTrigger(String source, Long sharedMapId, Long groupId) {

    public static final String CHAT = "chat";
    public static final String GROUP_CHAT = "group_chat";
    public static final String SAVE = "save";
    public static final String REVISE = "revise";

    public static TraceTrigger chat() {
        return new TraceTrigger(CHAT, null, null);
    }

    public static TraceTrigger groupChat(Long sharedMapId, Long groupId) {
        return new TraceTrigger(GROUP_CHAT, sharedMapId, groupId);
    }

    public static TraceTrigger save() {
        return new TraceTrigger(SAVE, null, null);
    }

    public static TraceTrigger revise() {
        return new TraceTrigger(REVISE, null, null);
    }

    public String sourceOrDefault() {
        return source == null || source.isBlank() ? CHAT : source;
    }
}
