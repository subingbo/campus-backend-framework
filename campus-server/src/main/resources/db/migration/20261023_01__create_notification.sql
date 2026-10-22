-- 第三轮：统一消息中心收件箱
CREATE TABLE IF NOT EXISTS notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '通知ID',
    user_id BIGINT NOT NULL COMMENT '接收用户ID',
    type VARCHAR(32) NOT NULL COMMENT '通知类型',
    title VARCHAR(128) NOT NULL COMMENT '标题',
    content VARCHAR(512) DEFAULT NULL COMMENT '正文（不含敏感内容）',
    target_type VARCHAR(32) DEFAULT NULL COMMENT '跳转目标类型',
    target_id BIGINT DEFAULT NULL COMMENT '跳转目标ID',
    payload_json TEXT DEFAULT NULL COMMENT '最小路由附加数据',
    read_at DATETIME DEFAULT NULL COMMENT '已读时间',
    dedupe_key VARCHAR(160) DEFAULT NULL COMMENT '幂等键',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_notification_dedupe (user_id, dedupe_key),
    KEY idx_user_read (user_id, read_at, id),
    KEY idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户通知收件箱';
