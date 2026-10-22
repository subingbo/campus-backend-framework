package com.campus.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/** 群规划卡快照：生成时不可变；保存状态从 ai_conversation.saved_activity_id 动态读取 */
@Data
public class GroupAgentPlan {

    private Long id;
    private Long groupConversationId;
    private Long messageId;
    private Long aiConversationId;
    private Long askerId;
    private String planJson;
    private Integer planVersion;
    private LocalDateTime createdAt;
}
