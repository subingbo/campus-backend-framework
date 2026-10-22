package com.campus;

import com.campus.ai.service.GroupAgentRunRegistry;
import com.campus.ai.service.GroupAgentRunRegistry.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** runId 运行注册表：同群单飞、取消标记、终态只能进入一次。 */
class GroupAgentRunRegistryTest {

    private final GroupAgentRunRegistry registry = new GroupAgentRunRegistry();

    @Test
    void onlyOneActiveRunPerConversation() {
        GroupAgentRunRegistry.Run first = registry.start(28L, 1L);
        assertThat(first).isNotNull();
        assertThat(registry.start(28L, 2L)).isNull();
        assertThat(registry.active(28L)).isSameAs(first);
    }

    @Test
    void cancelIsBoundToRunAndTerminalStateIsUnique() {
        GroupAgentRunRegistry.Run run = registry.start(28L, 1L);
        assertThat(registry.find(run.getRunId())).isSameAs(run);
        assertThat(run.requestCancel()).isTrue();
        assertThat(run.isCancelRequested()).isTrue();

        assertThat(registry.finish(run, Status.CANCELLED)).isTrue();
        assertThat(registry.finish(run, Status.DONE)).isFalse();
        assertThat(registry.active(28L)).isNull();
    }

    @Test
    void lateCancelAfterFinishDoesNotAffectNextRun() {
        GroupAgentRunRegistry.Run first = registry.start(28L, 1L);
        registry.finish(first, Status.DONE);
        assertThat(first.requestCancel()).isFalse();

        GroupAgentRunRegistry.Run second = registry.start(28L, 2L);
        assertThat(second.getRunId()).isNotEqualTo(first.getRunId());
        assertThat(second.isCancelRequested()).isFalse();
    }
}
