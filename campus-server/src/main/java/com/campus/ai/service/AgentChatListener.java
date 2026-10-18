package com.campus.ai.service;

import com.campus.ai.dto.PlanDto;

/** Agent 对话事件出口：AssistantController 实现，向 SseEmitter 推送 */
public interface AgentChatListener {

    /** 客户端断开/超时为 true，AgentService 据此尽快退出循环 */
    boolean isCancelled();

    void onStatus(String stage);

    void onDelta(String text);

    void onPlan(PlanDto plan);

    /** 本轮工具产出的地理信息（地点/路线/用户位置）：前端实时上图与可点击联动；默认忽略 */
    default void onGeo(java.util.List<com.campus.ai.dto.GeoEvent> events) {
    }

    /** 结束（含最终答复已完成、草稿可能已提交） */
    void onDone();

    void onError(String message);
}
