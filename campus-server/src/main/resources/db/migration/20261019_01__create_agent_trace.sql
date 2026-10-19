-- 2026-10-19 Agent Trace P0：执行追踪会话与事件
--
-- 执行方式（已初始化的既有库）：
--   docker compose exec -T mysql mysql -ucampus -pcampus123 campus < 本文件
-- 全新初始化无需手动执行：schema.sql 已同步包含；测试库见 src/test/resources/db/test-init.sql。
--
-- 回滚（按顺序）：
--   DROP TABLE IF EXISTS agent_trace_event;
--   DROP TABLE IF EXISTS agent_trace_session;
--
-- 说明：
--   1. 这是纯新增表，不修改任何既有业务表；
--   2. seq 由 agent_trace_session.next_seq 行锁分配，唯一键 (session_id, seq) 兜底；
--   3. payload_json 只保存经过 TraceDataRedactor 白名单脱敏后的摘要，
--      不保存聊天原文、地点名/地址/备注、精确经纬度/轨迹、JWT/API Key。

USE campus;

CREATE TABLE IF NOT EXISTS agent_trace_session (
    id                  CHAR(36) PRIMARY KEY COMMENT '追踪会话ID（UUID）',
    user_id             BIGINT NOT NULL COMMENT '会话归属用户（发起人）',
    conversation_id     BIGINT NULL COMMENT 'AI 规划会话ID（ai_conversation.id）',
    shared_map_id       BIGINT NULL COMMENT '关联共享地图ID（可空）',
    group_id            BIGINT NULL COMMENT '关联群ID（群聊 @AI 时由后端写入）',
    draft_id            BIGINT NULL COMMENT '草稿ID（P0 取 ai_conversation.id）',
    trigger_source      VARCHAR(32) NOT NULL DEFAULT 'chat' COMMENT '触发来源：chat/group_chat/save/revise',
    provider            VARCHAR(64) NULL COMMENT '模型提供方',
    model               VARCHAR(128) NULL COMMENT '模型名称',
    status              VARCHAR(24) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/COMPLETED/INTERRUPTED/FAILED',
    started_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '开始时间',
    ended_at            DATETIME(3) NULL COMMENT '结束时间',
    duration_ms         BIGINT NULL COMMENT '端到端耗时（毫秒）',
    prompt_tokens       BIGINT NULL COMMENT '提示词 Token（服务端未返回则为空）',
    completion_tokens   BIGINT NULL COMMENT '补全 Token（服务端未返回则为空）',
    total_tokens        BIGINT NULL COMMENT '总 Token（服务端未返回则为空）',
    event_count         INT NOT NULL DEFAULT 0 COMMENT '已持久化事件数',
    tool_call_count     INT NOT NULL DEFAULT 0 COMMENT 'tool/call 事件数',
    error_count         INT NOT NULL DEFAULT 0 COMMENT '带 error_code 的事件数',
    dropped_count       INT NOT NULL DEFAULT 0 COMMENT '队列满/写入失败丢弃或延迟的事件数',
    next_seq            BIGINT NOT NULL DEFAULT 0 COMMENT '下一个事件序号（行锁分配）',
    last_error_code     VARCHAR(128) NULL COMMENT '最近错误码/中断标记',
    created_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                        ON UPDATE CURRENT_TIMESTAMP(3),
    INDEX idx_trace_user_time (user_id, started_at),
    INDEX idx_trace_conversation (conversation_id, started_at),
    INDEX idx_trace_map_time (shared_map_id, started_at),
    INDEX idx_trace_group_time (group_id, started_at),
    INDEX idx_trace_status_time (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 执行追踪会话';

CREATE TABLE IF NOT EXISTS agent_trace_event (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '事件ID',
    session_id          CHAR(36) NOT NULL COMMENT '追踪会话ID',
    trace_id            CHAR(36) NOT NULL COMMENT '单次请求/执行尝试ID',
    seq                 BIGINT NOT NULL COMMENT '会话内严格递增序号',
    event_type          VARCHAR(64) NOT NULL COMMENT '事件类型 domain/action',
    occurred_at_ms      BIGINT NOT NULL COMMENT '业务发生时间（UTC epoch 毫秒）',
    persisted_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '持久化时间',
    turn_no             INT NULL COMMENT '交互轮次',
    step_no             INT NULL COMMENT '执行步骤',
    call_id             VARCHAR(128) NULL COMMENT '工具调用关联键',
    parent_event_id     BIGINT NULL COMMENT '父事件ID（P0 暂不填）',
    status              VARCHAR(24) NULL COMMENT 'RUNNING/SUCCESS/FAILED/CANCELLED/PARTIAL 等',
    duration_ms         BIGINT NULL COMMENT '本事件耗时',
    tool_name           VARCHAR(128) NULL COMMENT '工具名',
    error_code          VARCHAR(128) NULL COMMENT '错误码/规则ID',
    payload_json        JSON NOT NULL COMMENT '脱敏后的 data 摘要',
    UNIQUE KEY uk_trace_session_seq (session_id, seq),
    INDEX idx_trace_type_time (event_type, occurred_at_ms),
    INDEX idx_trace_call (session_id, call_id),
    INDEX idx_trace_request (trace_id, occurred_at_ms),
    CONSTRAINT fk_trace_event_session FOREIGN KEY (session_id)
        REFERENCES agent_trace_session(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 执行追踪事件';
