package com.campus.ai.trace.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** complete 必须同时满足终态、无丢弃、无待落库事件。 */
class TraceSessionViewTest {

    @Test
    void completeRequiresTerminalNoDropsAndNoPendingEvents() {
        assertThat(TraceSessionView.isComplete("COMPLETED", 0, 0L)).isTrue();
        assertThat(TraceSessionView.isComplete("COMPLETED", null, null)).isTrue();

        assertThat(TraceSessionView.isComplete("RUNNING", 0, 0L)).isFalse();
        assertThat(TraceSessionView.isComplete("FAILED", 0, 0L)).isFalse();
        assertThat(TraceSessionView.isComplete("COMPLETED", 1, 0L)).isFalse();
        assertThat(TraceSessionView.isComplete("COMPLETED", 0, 1L)).isFalse();
    }
}
