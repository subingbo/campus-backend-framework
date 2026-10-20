package com.campus.ai;

import com.campus.ai.trace.config.AgentTraceProperties;
import com.campus.ai.trace.dto.TraceLlmUsageStat;
import com.campus.ai.trace.service.TraceCostCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 成本估算的费率、usage 完整性与状态优先级。 */
class TraceCostCalculatorTest {

    @Test
    void knownRateAndCompleteUsageProducesExactAmount() {
        TraceCostCalculator calculator = calculator(true, "CNY", "test-1",
                "qwen-plus", "1.0", "2.0");

        TraceCostCalculator.CostResult result = calculator.calculate(
                "qwen-plus", 520L, 200L, usage("qwen-plus", 4, 0, 0, 1));

        assertThat(result.status()).isEqualTo(TraceCostCalculator.KNOWN);
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("0.000920"));
        assertThat(result.currency()).isEqualTo("CNY");
        assertThat(result.pricingVersion()).isEqualTo("test-1");
    }

    @Test
    void zeroTokensIsKnownZeroCost() {
        TraceCostCalculator calculator = calculator(true, "CNY", "test-1",
                "qwen-plus", "1.0", "2.0");

        TraceCostCalculator.CostResult result = calculator.calculate(
                "qwen-plus", 0L, 0L, usage("qwen-plus", 1, 0, 0, 1));

        assertThat(result.status()).isEqualTo(TraceCostCalculator.KNOWN);
        assertThat(result.amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void missingOrMixedModelIsUnknownModel() {
        TraceCostCalculator calculator = calculator(true, "CNY", "test-1",
                "qwen-plus", "1.0", "2.0");

        assertThat(calculator.calculate("qwen-plus", 10L, 5L,
                usage("qwen-plus", 1, 0, 1, 1)).status())
                .isEqualTo(TraceCostCalculator.UNKNOWN_MODEL);
        assertThat(calculator.calculate("qwen-plus", 10L, 5L,
                usage("qwen-plus", 1, 0, 0, 2)).status())
                .isEqualTo(TraceCostCalculator.UNKNOWN_MODEL);
        assertThat(calculator.calculate("qwen-plus", 10L, 5L,
                usage("qwen-max", 1, 0, 0, 1)).status())
                .isEqualTo(TraceCostCalculator.UNKNOWN_MODEL);
        assertThat(calculator.calculate("qwen-plus", 10L, 5L, null).status())
                .isEqualTo(TraceCostCalculator.UNKNOWN_MODEL);
    }

    @Test
    void missingRateIsUnknownRate() {
        TraceCostCalculator calculator = calculator(true, "CNY", "test-1",
                "qwen-plus", "1.0", "2.0");

        TraceCostCalculator.CostResult result = calculator.calculate(
                "qwen-max", 10L, 5L, usage("qwen-max", 1, 0, 0, 1));

        assertThat(result.status()).isEqualTo(TraceCostCalculator.UNKNOWN_RATE);
        assertThat(result.amount()).isNull();
    }

    @Test
    void incompleteOrNegativeTokensIsUnknownTokens() {
        TraceCostCalculator calculator = calculator(true, "CNY", "test-1",
                "qwen-plus", "1.0", "2.0");

        assertThat(calculator.calculate("qwen-plus", 10L, 5L,
                usage("qwen-plus", 2, 1, 0, 1)).status())
                .isEqualTo(TraceCostCalculator.UNKNOWN_TOKENS);
        assertThat(calculator.calculate("qwen-plus", null, 5L,
                usage("qwen-plus", 1, 0, 0, 1)).status())
                .isEqualTo(TraceCostCalculator.UNKNOWN_TOKENS);
        assertThat(calculator.calculate("qwen-plus", -1L, 5L,
                usage("qwen-plus", 1, 0, 0, 1)).status())
                .isEqualTo(TraceCostCalculator.UNKNOWN_TOKENS);
    }

    @Test
    void disabledWinsOverOtherMissingInputs() {
        TraceCostCalculator calculator = calculator(false, "CNY", "test-1",
                "qwen-plus", "1.0", "2.0");

        TraceCostCalculator.CostResult result = calculator.calculate(
                "unknown", null, null, null);

        assertThat(result.status()).isEqualTo(TraceCostCalculator.DISABLED);
        assertThat(result.amount()).isNull();
    }

    @Test
    void anyDroppedEventMakesCostPartial() {
        TraceCostCalculator calculator = calculator(true, "CNY", "test-1",
                "qwen-plus", "1.0", "2.0");

        TraceCostCalculator.CostResult result = calculator.calculate(
                "qwen-plus", 10L, 5L, usage("qwen-plus", 1, 0, 0, 1), 1L);

        assertThat(result.status()).isEqualTo(TraceCostCalculator.PARTIAL);
        assertThat(result.amount()).isNull();
        // 即使模型/usage 也缺失，PARTIAL 仍然优先，避免宣称完整成本
        assertThat(calculator.calculate("unknown", null, null, null, 2L).status())
                .isEqualTo(TraceCostCalculator.PARTIAL);
    }

    @Test
    void invalidRateIsUnknownRateNotZero() {
        TraceCostCalculator calculator = calculator(true, "CNY", "test-1",
                "qwen-plus", "-1.0", "2.0");

        TraceCostCalculator.CostResult result = calculator.calculate(
                "qwen-plus", 10L, 5L, usage("qwen-plus", 1, 0, 0, 1));

        assertThat(result.status()).isEqualTo(TraceCostCalculator.UNKNOWN_RATE);
        assertThat(result.amount()).isNull();
    }

    private TraceCostCalculator calculator(boolean enabled, String currency, String version,
                                           String model, String input, String output) {
        AgentTraceProperties properties = new AgentTraceProperties();
        AgentTraceProperties.Pricing pricing = properties.getPricing();
        pricing.setEnabled(enabled);
        pricing.setCurrency(currency);
        pricing.setVersion(version);
        Map<String, AgentTraceProperties.ModelPrice> models = new LinkedHashMap<>();
        AgentTraceProperties.ModelPrice price = new AgentTraceProperties.ModelPrice();
        price.setInputPerMillion(new BigDecimal(input));
        price.setOutputPerMillion(new BigDecimal(output));
        models.put(model, price);
        pricing.setModels(models);
        return new TraceCostCalculator(properties);
    }

    private TraceLlmUsageStat usage(String model, long responseCount, long missingUsage,
                                    long missingModel, long modelCount) {
        TraceLlmUsageStat usage = new TraceLlmUsageStat();
        usage.setModel(model);
        usage.setResponseCount(responseCount);
        usage.setMissingUsageCount(missingUsage);
        usage.setMissingModelCount(missingModel);
        usage.setModelCount(modelCount);
        return usage;
    }
}
