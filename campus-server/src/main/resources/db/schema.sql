CREATE DATABASE IF NOT EXISTS campus DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE campus;

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    username VARCHAR(64) NOT NULL COMMENT '登录用户名',
    password VARCHAR(128) NOT NULL COMMENT 'BCrypt 密码哈希',
    nickname VARCHAR(64) DEFAULT NULL COMMENT '昵称',
    avatar VARCHAR(512) DEFAULT NULL COMMENT '头像 URL',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1 正常 0 禁用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统用户';

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '角色ID',
    name VARCHAR(64) NOT NULL COMMENT '角色名称',
    code VARCHAR(64) NOT NULL COMMENT '角色编码',
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统角色';

CREATE TABLE IF NOT EXISTS sys_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '权限ID',
    name VARCHAR(64) NOT NULL COMMENT '权限名称',
    code VARCHAR(64) NOT NULL COMMENT '权限编码',
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统权限';

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role_id BIGINT NOT NULL COMMENT '角色ID',
    PRIMARY KEY (user_id, role_id),
    KEY idx_role_id (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户角色关联';

CREATE TABLE IF NOT EXISTS sys_role_permission (
    role_id BIGINT NOT NULL COMMENT '角色ID',
    permission_id BIGINT NOT NULL COMMENT '权限ID',
    PRIMARY KEY (role_id, permission_id),
    KEY idx_permission_id (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='角色权限关联';

CREATE TABLE IF NOT EXISTS place_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '分类ID',
    name VARCHAR(64) NOT NULL COMMENT '分类名称',
    icon VARCHAR(128) DEFAULT NULL COMMENT '图标标识',
    sort INT NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1 启用 0 禁用'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地点分类';

CREATE TABLE IF NOT EXISTS place (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '地点ID',
    name VARCHAR(128) NOT NULL COMMENT '地点名称',
    description VARCHAR(512) DEFAULT NULL COMMENT '地点描述',
    category_id BIGINT NOT NULL COMMENT '分类ID',
    longitude DECIMAL(10, 7) NOT NULL COMMENT '经度 WGS84',
    latitude DECIMAL(10, 7) NOT NULL COMMENT '纬度 WGS84',
    address VARCHAR(256) DEFAULT NULL COMMENT '文字地址',
    creator_id BIGINT NOT NULL COMMENT '创建者用户ID',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1 正常 0 隐藏',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_lat_lng (latitude, longitude),
    KEY idx_category_id (category_id),
    KEY idx_creator_id (creator_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='校园地点 POI';

CREATE TABLE IF NOT EXISTS post (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '动态ID',
    user_id BIGINT NOT NULL COMMENT '发布用户ID',
    place_id BIGINT NOT NULL COMMENT '关联地点ID',
    content VARCHAR(2000) NOT NULL COMMENT '动态正文',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1 正常 0 隐藏',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_place_id (place_id),
    KEY idx_user_id (user_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地点关联动态';

CREATE TABLE IF NOT EXISTS post_comment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '评论ID',
    post_id BIGINT NOT NULL COMMENT '动态ID',
    user_id BIGINT NOT NULL COMMENT '评论用户ID',
    parent_id BIGINT DEFAULT NULL COMMENT '父评论ID，楼中楼',
    content VARCHAR(1000) NOT NULL COMMENT '评论内容',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_post_id (post_id),
    KEY idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='动态评论';

CREATE TABLE IF NOT EXISTS post_like (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '点赞记录ID',
    post_id BIGINT NOT NULL COMMENT '动态ID',
    user_id BIGINT NOT NULL COMMENT '点赞用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_post_user (post_id, user_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='动态点赞';

CREATE TABLE IF NOT EXISTS activity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '活动ID',
    place_id BIGINT NOT NULL COMMENT '关联地点ID',
    creator_id BIGINT NOT NULL COMMENT '创建者用户ID',
    title VARCHAR(128) NOT NULL COMMENT '活动标题',
    description VARCHAR(1000) DEFAULT NULL COMMENT '活动描述',
    start_time DATETIME NOT NULL COMMENT '开始时间',
    end_time DATETIME NOT NULL COMMENT '结束时间',
    max_members INT NOT NULL COMMENT '人数上限',
    category VARCHAR(16) NOT NULL DEFAULT 'MEETING' COMMENT '活动类型：MEETING 会议 COMPETITION 比赛 OTHER 其他',
    checkin_code VARCHAR(8) DEFAULT NULL COMMENT '数字签到码（创建时生成，仅创建者可查）',
    checkin_open_until DATETIME DEFAULT NULL COMMENT '签到窗口截止时间',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1 进行中 0 已取消',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_place_id (place_id),
    KEY idx_start_time (start_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地点活动';

CREATE TABLE IF NOT EXISTS activity_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '报名记录ID',
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    checked_in TINYINT NOT NULL DEFAULT 0 COMMENT '是否已签到：0 否 1 是（签到时间见 checked_in_at）',
    checked_in_at DATETIME DEFAULT NULL COMMENT '签到时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '报名时间',
    UNIQUE KEY uk_activity_user (activity_id, user_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='活动成员/签到记录';

CREATE TABLE IF NOT EXISTS activity_invite (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '邀请ID',
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    inviter_id BIGINT NOT NULL COMMENT '邀请人（活动创建者）用户ID',
    invitee_id BIGINT NOT NULL COMMENT '被邀请人用户ID',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 待响应 1 已接受 2 已拒绝',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '邀请时间',
    handled_at DATETIME DEFAULT NULL COMMENT '响应时间',
    UNIQUE KEY uk_activity_invitee (activity_id, invitee_id),
    KEY idx_invitee (invitee_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='活动召集邀请';

CREATE TABLE IF NOT EXISTS activity_stop (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '行程站点ID',
    activity_id BIGINT NOT NULL COMMENT '活动ID',
    seq INT NOT NULL COMMENT '站点顺序，从 1 递增',
    name VARCHAR(128) NOT NULL COMMENT '站点名称',
    place_id BIGINT DEFAULT NULL COMMENT '关联地点ID，可空（外部 POI 坐标直存）',
    longitude DECIMAL(10, 7) DEFAULT NULL COMMENT '经度 GCJ-02',
    latitude DECIMAL(10, 7) DEFAULT NULL COMMENT '纬度 GCJ-02',
    start_time DATETIME DEFAULT NULL COMMENT '到达/开始时间',
    end_time DATETIME DEFAULT NULL COMMENT '离开/结束时间',
    transport_mode VARCHAR(16) DEFAULT NULL COMMENT '前往本站的交通方式：WALK 步行 RIDE 骑行 DRIVE 驾车 TRANSIT 公交',
    note VARCHAR(256) DEFAULT NULL COMMENT '站点备注',
    UNIQUE KEY uk_activity_seq (activity_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='多站活动行程站点';

CREATE TABLE IF NOT EXISTS report (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '举报ID',
    reporter_id BIGINT NOT NULL COMMENT '举报人用户ID',
    target_type VARCHAR(16) NOT NULL COMMENT '目标类型：POST COMMENT PLACE USER',
    target_id BIGINT NOT NULL COMMENT '目标ID',
    reason VARCHAR(256) NOT NULL COMMENT '举报原因',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0 待处理 1 通过 2 驳回',
    handler_id BIGINT DEFAULT NULL COMMENT '处理人用户ID',
    handle_action VARCHAR(32) DEFAULT NULL COMMENT '处理动作',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    handled_at DATETIME DEFAULT NULL COMMENT '处理时间',
    KEY idx_status_created (status, created_at),
    KEY idx_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='内容举报';

CREATE TABLE IF NOT EXISTS campus_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '群组ID',
    name VARCHAR(128) NOT NULL COMMENT '群组名称',
    description VARCHAR(512) DEFAULT NULL COMMENT '群组描述',
    owner_id BIGINT NOT NULL COMMENT '群主用户ID',
    invite_code VARCHAR(16) NOT NULL COMMENT '邀请码',
    visibility VARCHAR(16) NOT NULL DEFAULT 'MEMBERS' COMMENT '可见性：MEMBERS 仅成员 PUBLIC 公开可读',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_invite_code (invite_code),
    KEY idx_owner_id (owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='协作群组';

CREATE TABLE IF NOT EXISTS shared_map (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '共享地图ID',
    group_id BIGINT NOT NULL COMMENT '所属群组ID，一对一',
    name VARCHAR(128) NOT NULL COMMENT '地图名称',
    description VARCHAR(512) DEFAULT NULL COMMENT '地图描述',
    cover_image VARCHAR(512) DEFAULT NULL COMMENT '封面图 URL',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_group_id (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群组共享地图';

CREATE TABLE IF NOT EXISTS map_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '成员记录ID',
    map_id BIGINT NOT NULL COMMENT '地图ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    role VARCHAR(16) NOT NULL COMMENT '角色：OWNER 群主 EDITOR 编辑 VIEWER 查看',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    UNIQUE KEY uk_map_user (map_id, user_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地图成员与权限';

CREATE TABLE IF NOT EXISTS map_place (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '关联ID',
    map_id BIGINT NOT NULL COMMENT '地图ID',
    place_id BIGINT NOT NULL COMMENT '地点ID',
    created_by BIGINT NOT NULL COMMENT '添加到本图的用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '添加时间',
    UNIQUE KEY uk_map_place (map_id, place_id),
    KEY idx_place_id (place_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地图与地点多对多关联';

CREATE TABLE IF NOT EXISTS place_comment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '评论ID',
    map_id BIGINT NOT NULL COMMENT '地图ID，评论归属该地图',
    place_id BIGINT NOT NULL COMMENT '地点ID',
    user_id BIGINT NOT NULL COMMENT '评论用户ID',
    content VARCHAR(1000) NOT NULL COMMENT '评论内容',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_map_place (map_id, place_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='地图维度地点评论';

CREATE TABLE IF NOT EXISTS friend_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '请求ID',
    from_user_id BIGINT NOT NULL COMMENT '发起方用户ID',
    to_user_id BIGINT NOT NULL COMMENT '接收方用户ID',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0待处理 1已接受 2已拒绝',
    message VARCHAR(128) NULL COMMENT '验证留言',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发起时间',
    handled_at DATETIME NULL COMMENT '处理时间',
    KEY idx_to_user_status (to_user_id, status),
    KEY idx_from_user (from_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='好友申请';

CREATE TABLE IF NOT EXISTS friend (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    friend_id BIGINT NOT NULL COMMENT '好友用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '成为好友时间',
    UNIQUE KEY uk_user_friend (user_id, friend_id),
    KEY idx_friend_id (friend_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='好友关系（双向行）';

CREATE TABLE IF NOT EXISTS conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '会话ID',
    type VARCHAR(16) NOT NULL COMMENT '类型：PRIVATE私聊 GROUP地图群聊 PLACE地点聊天室',
    map_id BIGINT NULL COMMENT 'GROUP会话绑定的地图ID',
    place_id BIGINT NULL COMMENT 'PLACE会话绑定的地点ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_map_id (map_id),
    KEY idx_place_id (place_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天会话';

CREATE TABLE IF NOT EXISTS conversation_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '记录ID',
    conversation_id BIGINT NOT NULL COMMENT '会话ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    UNIQUE KEY uk_conv_user (conversation_id, user_id),
    KEY idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话成员';

CREATE TABLE IF NOT EXISTS message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
    conversation_id BIGINT NOT NULL COMMENT '会话ID',
    sender_id BIGINT NOT NULL COMMENT '发送者用户ID',
    content VARCHAR(1000) NOT NULL COMMENT '消息内容',
    type VARCHAR(16) NOT NULL DEFAULT 'TEXT' COMMENT '类型：TEXT文本（二期扩展图片/位置）',
    client_msg_id VARCHAR(64) DEFAULT NULL COMMENT '客户端幂等ID（断线重试去重）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    UNIQUE KEY uk_client_msg (conversation_id, sender_id, client_msg_id),
    KEY idx_conv_created (conversation_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='聊天消息';

CREATE TABLE IF NOT EXISTS ai_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '会话ID',
    user_id BIGINT NOT NULL COMMENT '归属用户ID',
    title VARCHAR(128) DEFAULT NULL COMMENT '会话标题（通常取首条需求摘要）',
    status VARCHAR(16) NOT NULL DEFAULT 'CHATTING' COMMENT '状态：CHATTING 对话中 DRAFT 已出草稿 SAVED 已保存为活动',
    plan_json MEDIUMTEXT DEFAULT NULL COMMENT '最近校验通过的规划草稿 JSON',
    plan_version INT NOT NULL DEFAULT 0 COMMENT '草稿版本号，每次产出+1',
    saved_activity_id BIGINT DEFAULT NULL COMMENT '草稿落库后的活动ID（save 幂等依据）',
    source_group_conversation_id BIGINT DEFAULT NULL COMMENT '群聊 @AI 来源的群会话ID（只读共享授权依据）',
    source_map_id BIGINT DEFAULT NULL COMMENT '群聊 @AI 来源地图ID（继续规划恢复群语境）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    KEY idx_user_updated (user_id, updated_at),
    KEY idx_source_group_conv (source_group_conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 规划会话';

CREATE TABLE IF NOT EXISTS ai_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '消息ID',
    conversation_id BIGINT NOT NULL COMMENT '会话ID',
    role VARCHAR(16) NOT NULL COMMENT '角色：USER 用户 ASSISTANT 助手',
    content TEXT NOT NULL COMMENT '消息内容',
    geo_json MEDIUMTEXT DEFAULT NULL COMMENT '助手消息附带的地理事件JSON（地图回放）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    KEY idx_conv (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 规划会话消息';

CREATE TABLE IF NOT EXISTS group_agent_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '群规划快照ID',
    group_conversation_id BIGINT NOT NULL COMMENT '群会话ID（message.conversation_id）',
    message_id BIGINT NOT NULL COMMENT '对应 AGENT 消息ID',
    ai_conversation_id BIGINT NOT NULL COMMENT '发起人的私有 AI 会话ID',
    asker_id BIGINT NOT NULL COMMENT '发起人用户ID',
    plan_json MEDIUMTEXT NOT NULL COMMENT '生成时不可变规划快照',
    plan_version INT NOT NULL DEFAULT 1 COMMENT '生成时草稿版本',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_group_plan_message (message_id),
    KEY idx_group_conv (group_conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='群规划卡快照（成员只读摘要来源）';

CREATE TABLE IF NOT EXISTS notification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '通知ID',
    user_id BIGINT NOT NULL COMMENT '接收用户ID',
    type VARCHAR(32) NOT NULL COMMENT 'FRIEND_REQUEST/FRIEND_ACCEPTED/ACTIVITY_INVITED/ACTIVITY_REMINDER/ACTIVITY_CANCELLED/ACTIVITY_MEMBER_JOINED',
    title VARCHAR(128) NOT NULL COMMENT '标题',
    content VARCHAR(512) DEFAULT NULL COMMENT '正文（不含签到码/私人日程标题/私有坐标）',
    target_type VARCHAR(32) DEFAULT NULL COMMENT 'ACTIVITY/FRIEND_REQUEST/FRIEND/ACTIVITY_INVITE/CONVERSATION/NONE',
    target_id BIGINT DEFAULT NULL COMMENT '跳转目标ID',
    payload_json TEXT DEFAULT NULL COMMENT '最小路由附加数据',
    read_at DATETIME DEFAULT NULL COMMENT '已读时间，空表示未读',
    dedupe_key VARCHAR(160) DEFAULT NULL COMMENT '幂等键；同一用户同一键只落一条',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_notification_dedupe (user_id, dedupe_key),
    KEY idx_user_read (user_id, read_at, id),
    KEY idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户通知收件箱';

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
