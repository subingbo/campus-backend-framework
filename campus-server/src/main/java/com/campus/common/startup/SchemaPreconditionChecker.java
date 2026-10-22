package com.campus.common.startup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动前的数据库结构前置检查：缺迁移时 fail-fast，避免应用启动成功、
 * 首次发消息才报 MySQL Unknown column。
 *
 * <p>第二轮新增 group_agent_plan / ai_conversation.source_* 后，
 * 在 {@link #verify()} 中继续追加检查，保证“启动安全”与“新功能可用”一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaPreconditionChecker implements ApplicationRunner {

    static final String ACTIVITY_STOP_MIGRATION =
            "campus-server/src/main/resources/db/migration/20261015_01__create_activity_stop.sql";
    static final String PERSONAL_SCHEDULE_MIGRATION =
            "campus-server/src/main/resources/db/migration/20261016_01__create_personal_schedule.sql";
    static final String AI_MESSAGE_GEO_MIGRATION =
            "campus-server/src/main/resources/db/migration/20261018_01__add_ai_message_geo_json.sql";
    static final String AGENT_TRACE_MIGRATION =
            "campus-server/src/main/resources/db/migration/20261019_01__create_agent_trace.sql";
    static final String MESSAGE_IDEMPOTENCY_MIGRATION =
            "campus-server/src/main/resources/db/migration/20261022_01__add_message_client_msg_id.sql";
    static final String GROUP_PLAN_MIGRATION =
            "campus-server/src/main/resources/db/migration/20261022_02__add_group_agent_plan.sql";
    static final String NOTIFICATION_MIGRATION =
            "campus-server/src/main/resources/db/migration/20261023_01__create_notification.sql";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        verify();
    }

    void verify() {
        requireExists(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'activity_stop'",
                "activity_stop",
                ACTIVITY_STOP_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'activity_stop' "
                        + "AND INDEX_NAME = 'uk_activity_seq'",
                "activity_stop.uk_activity_seq",
                ACTIVITY_STOP_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'personal_schedule'",
                "personal_schedule",
                PERSONAL_SCHEDULE_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'schedule_share_permission'",
                "schedule_share_permission",
                PERSONAL_SCHEDULE_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_message' "
                        + "AND COLUMN_NAME = 'geo_json'",
                "ai_message.geo_json",
                AI_MESSAGE_GEO_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_trace_session'",
                "agent_trace_session",
                AGENT_TRACE_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_trace_event'",
                "agent_trace_event",
                AGENT_TRACE_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'message' "
                        + "AND COLUMN_NAME = 'client_msg_id'",
                "message.client_msg_id",
                MESSAGE_IDEMPOTENCY_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'message' "
                        + "AND INDEX_NAME = 'uk_client_msg'",
                "message.uk_client_msg",
                MESSAGE_IDEMPOTENCY_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' "
                        + "AND COLUMN_NAME = 'source_group_conversation_id'",
                "ai_conversation.source_group_conversation_id",
                GROUP_PLAN_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_conversation' "
                        + "AND COLUMN_NAME = 'source_map_id'",
                "ai_conversation.source_map_id",
                GROUP_PLAN_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'group_agent_plan'",
                "group_agent_plan",
                GROUP_PLAN_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'group_agent_plan' "
                        + "AND INDEX_NAME = 'uk_group_plan_message'",
                "group_agent_plan.uk_group_plan_message",
                GROUP_PLAN_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification'",
                "notification",
                NOTIFICATION_MIGRATION);
        requireExists(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'notification' "
                        + "AND INDEX_NAME = 'uk_notification_dedupe'",
                "notification.uk_notification_dedupe",
                NOTIFICATION_MIGRATION);
        log.info("数据库结构前置检查通过（多站行程 + 个人日程 + AI 地图回放 + Trace + 聊天幂等 + 群规划快照 + 通知收件箱）");
    }

    private void requireExists(String sql, String objectName, String migration) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        if (count == null || count == 0) {
            throw new IllegalStateException(
                    "数据库缺少 " + objectName + "，请先执行迁移：" + migration);
        }
    }
}
