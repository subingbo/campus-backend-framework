package com.campus.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 会话详情：历史消息 + 当前草稿（plan 为空表示还没有草稿） */
@Data
public class ChatDetailResponse {

    private ChatSessionResponse conversation;
    private List<ChatMessageItem> messages = new ArrayList<>();
    private PlanDto plan;
    /** 群聊 @AI 来源：继续规划时恢复群语境，以服务端持久化为准 */
    private Long sourceGroupConversationId;
    private Long sourceMapId;
}
