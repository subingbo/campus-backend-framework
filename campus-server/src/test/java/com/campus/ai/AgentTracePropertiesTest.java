package com.campus.ai;

import com.campus.ai.trace.config.AgentTraceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** 配置开关语义：关闭/OFF 必须让所有入口退化为 no-op。 */
class AgentTracePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(
                    "campus.agent.trace.pricing.enabled=true",
                    "campus.agent.trace.pricing.currency=CNY",
                    "campus.agent.trace.pricing.version=test-config",
                    "campus.agent.trace.pricing.models.qwen-plus.input-per-million=1.0",
                    "campus.agent.trace.pricing.models.qwen-plus.output-per-million=2.0");

    @Test
    void bindsPricingMapForConfiguredModel() {
        contextRunner.run(context -> {
            AgentTraceProperties properties = context.getBean(AgentTraceProperties.class);
            assertThat(properties.getPricing().isEnabled()).isTrue();
            assertThat(properties.getPricing().getCurrency()).isEqualTo("CNY");
            assertThat(properties.getPricing().getVersion()).isEqualTo("test-config");
            AgentTraceProperties.ModelPrice price =
                    properties.getPricing().getModels().get("qwen-plus");
            assertThat(price).isNotNull();
            assertThat(price.getInputPerMillion()).isEqualByComparingTo("1.0");
            assertThat(price.getOutputPerMillion()).isEqualByComparingTo("2.0");
        });
    }

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

    @Configuration
    @EnableConfigurationProperties(AgentTraceProperties.class)
    static class TestConfig {
    }
}
