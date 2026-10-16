-- 2026-10-16 时空协同 Agent：个人日程与忙闲授权
--
-- 执行方式（已初始化的既有库）：
--   docker compose exec -T mysql mysql -ucampus -pcampus123 campus < 本文件
-- 全新初始化无需手动执行：schema.sql 已同步包含。

USE campus;

CREATE TABLE IF NOT EXISTS personal_schedule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '个人日程ID',
    user_id BIGINT NOT NULL COMMENT '归属用户',
    title VARCHAR(128) NOT NULL COMMENT '事项标题（仅本人可见）',
    start_time DATETIME NOT NULL COMMENT '开始时间',
    end_time DATETIME NOT NULL COMMENT '结束时间',
    note VARCHAR(256) DEFAULT NULL COMMENT '备注（仅本人可见）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user_time (user_id, start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='个人日程（多人规划时的忙碌来源之一）';

CREATE TABLE IF NOT EXISTS schedule_share_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_user_id BIGINT NOT NULL COMMENT '日程所有者',
    target_type VARCHAR(16) NOT NULL COMMENT '授权对象类型：USER 单人 / GROUP 群体',
    target_id BIGINT NOT NULL COMMENT '目标ID（用户ID 或 campus_group.id）',
    permission_level VARCHAR(16) NOT NULL DEFAULT 'FREE_BUSY' COMMENT '授权等级：FREE_BUSY 仅忙闲（不暴露标题/地点）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_owner_target (owner_user_id, target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='日程共享授权：授予他人/群体仅忙闲可见';

-- 注意：个人日程与已报名活动均为忙碌来源；不把活动复制进个人日程表，避免同一占用被重复计算（计划 §4.3）。
