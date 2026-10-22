-- 第二轮：群规划卡快照 + 群会话/地图来源（成员只读摘要与发起人深链）
ALTER TABLE ai_conversation
    ADD COLUMN source_group_conversation_id BIGINT DEFAULT NULL COMMENT '群聊 @AI 来源的群会话ID' AFTER saved_activity_id,
    ADD COLUMN source_map_id BIGINT DEFAULT NULL COMMENT '群聊 @AI 来源地图ID' AFTER source_group_conversation_id,
    ADD KEY idx_source_group_conv (source_group_conversation_id);

CREATE TABLE IF NOT EXISTS group_agent_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '群规划快照ID',
    group_conversation_id BIGINT NOT NULL COMMENT '群会话ID',
    message_id BIGINT NOT NULL COMMENT '对应 AGENT 消息ID',
    ai_conversation_id BIGINT NOT NULL COMMENT '发起人的私有 AI 会话ID',
    asker_id BIGINT NOT NULL COMMENT '发起人用户ID',
    plan_json MEDIUMTEXT NOT NULL COMMENT '生成时不可变规划快照',
    plan_version INT NOT NULL DEFAULT 1 COMMENT '生成时草稿版本',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_group_plan_message (message_id),
    KEY idx_group_conv (group_conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群规划卡快照';
