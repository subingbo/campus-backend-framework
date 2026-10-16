-- 2026-10-15 AI 助手：规划会话与消息
--
-- 执行方式（已初始化的既有库）：
--   docker compose exec -T mysql mysql -ucampus -pcampus123 campus < 本文件
-- 全新初始化无需手动执行：schema.sql 已同步包含。

USE campus;

CREATE TABLE IF NOT EXISTS ai_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '会话ID',
    user_id BIGINT NOT NULL COMMENT '归属用户ID',
    title VARCHAR(128) DEFAULT NULL COMMENT '会话标题（通常取首条需求摘要）',
    status VARCHAR(16) NOT NULL DEFAULT 'CHATTING' COMMENT '状态：CHATTING 对话中 DRAFT 已出草稿 SAVED 已保存为活动',
    plan_json MEDIUMTEXT DEFAULT NULL COMMENT '最近校验通过的规划草稿 JSON',
    plan_version INT NOT NULL DEFAULT 0 COMMENT '草稿版本号，每次产出+1',
    saved_activity_id BIGINT DEFAULT NULL COMMENT '草稿落库后的活动ID（run/save 幂等依据）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_user_updated (user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 规划会话';

CREATE TABLE IF NOT EXISTS ai_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
    conversation_id BIGINT NOT NULL COMMENT '会话ID',
    role VARCHAR(16) NOT NULL COMMENT '角色：USER 用户 ASSISTANT 助手',
    content TEXT NOT NULL COMMENT '消息内容',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    KEY idx_conv (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 规划会话消息';
