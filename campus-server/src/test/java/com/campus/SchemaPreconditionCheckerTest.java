package com.campus;

import com.campus.common.startup.SchemaPreconditionChecker;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 迁移前置检查：缺列/缺索引时启动失败，并给出可执行的迁移路径。 */
class SchemaPreconditionCheckerTest {

    @Test
    void passesWhenColumnAndIndexExist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        new SchemaPreconditionChecker(jdbc).run(null);
    }

    @Test
    void failsWithMigrationHintWhenColumnMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        assertThatThrownBy(() -> new SchemaPreconditionChecker(jdbc).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("message.client_msg_id")
                .hasMessageContaining("20261022_01__add_message_client_msg_id.sql");
    }

    @Test
    void failsWhenGroupPlanSchemaMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 前 4 个检查通过，group_agent_plan 表缺失
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 1, 1, 1, 0, 0);
        assertThatThrownBy(() -> new SchemaPreconditionChecker(jdbc).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("group_agent_plan")
                .hasMessageContaining("20261022_02__add_group_agent_plan.sql");
    }

    @Test
    void failsWhenNotificationSchemaMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        // 前 6 个检查通过，notification 表缺失
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 1, 1, 1, 1, 1, 0, 0);
        assertThatThrownBy(() -> new SchemaPreconditionChecker(jdbc).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("notification")
                .hasMessageContaining("20261023_01__create_notification.sql");
    }
}
