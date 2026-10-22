package com.campus;

import com.campus.common.startup.SchemaPreconditionChecker;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 迁移前置检查：缺表/缺列/缺索引时启动失败，并给出可执行的迁移路径。 */
class SchemaPreconditionCheckerTest {

    @Test
    void passesWhenAllRequiredObjectsExist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        new SchemaPreconditionChecker(jdbc).run(null);
    }

    @Test
    void failsWhenActivityStopTableMissing() {
        assertMissing("TABLE_NAME = 'activity_stop'", "activity_stop",
                "20261015_01__create_activity_stop.sql");
    }

    @Test
    void failsWhenPersonalScheduleMissing() {
        assertMissing("TABLE_NAME = 'personal_schedule'", "personal_schedule",
                "20261016_01__create_personal_schedule.sql");
    }

    @Test
    void failsWhenAiMessageGeoJsonMissing() {
        assertMissing("COLUMN_NAME = 'geo_json'", "ai_message.geo_json",
                "20261018_01__add_ai_message_geo_json.sql");
    }

    @Test
    void failsWhenTraceEventTableMissing() {
        assertMissing("TABLE_NAME = 'agent_trace_event'", "agent_trace_event",
                "20261019_01__create_agent_trace.sql");
    }

    @Test
    void failsWithMigrationHintWhenMessageClientMsgIdMissing() {
        assertMissing("COLUMN_NAME = 'client_msg_id'", "message.client_msg_id",
                "20261022_01__add_message_client_msg_id.sql");
    }

    @Test
    void failsWhenGroupPlanSchemaMissing() {
        assertMissing("TABLE_NAME = 'group_agent_plan'", "group_agent_plan",
                "20261022_02__add_group_agent_plan.sql");
    }

    @Test
    void failsWhenNotificationSchemaMissing() {
        assertMissing("TABLE_NAME = 'notification'", "notification",
                "20261023_01__create_notification.sql");
    }

    private void assertMissing(String sqlFragment, String objectName, String migration) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains(sqlFragment) ? 0 : 1;
        });
        assertThatThrownBy(() -> new SchemaPreconditionChecker(jdbc).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(objectName)
                .hasMessageContaining(migration);
    }
}
