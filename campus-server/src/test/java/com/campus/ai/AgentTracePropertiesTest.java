package com.campus.ai;

import com.campus.ai.trace.config.AgentTraceProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 配置开关语义：关闭/OFF 必须让所有入口退化为 no-op。 */
class AgentTracePropertiesTest {

    @Test
    void enabledFalseOrLevelOffDisablesTrace() {
        AgentTraceProperties properties = new AgentTraceProperties();
        assertThat(properties.isTraceActive()).isTrue();

        properties.setEnabled(false);
        assertThat(properties.isTraceActive()).isFalse();

        properties.setEnabled(true);
        properties.setLevel("OFF");
        assertThat(properties.isTraceActive()).isFalse();

        properties.setLevel("DEBUG");
        assertThat(properties.isTraceActive()).isTrue();
    }
}
