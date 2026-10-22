package com.campus.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 发起人从群卡片继续规划：服务端返回私有会话与服务端持久化的群语境 */
@Data
@AllArgsConstructor
public class GroupPlanHandoff {

    private Long planId;
    private Long aiConversationId;
    private Long sourceMapId;
    private Long sourceGroupConversationId;
}
