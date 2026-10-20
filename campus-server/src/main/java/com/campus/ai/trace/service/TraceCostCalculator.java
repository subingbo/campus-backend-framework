package com.campus.ai.trace.service;

import com.campus.ai.trace.config.AgentTraceProperties;
import com.campus.ai.trace.dto.TraceLlmUsageStat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话级 Token 成本估算。只有模型唯一、usage 完整且费率配置有效时才返回金额；
 * 任何前提不满足都返回明确的状态与 null 金额，避免把“未知”显示成 0。
 */
@Slf4j
@Component
public class TraceCostCalculator {

    public static final String KNOWN = "KNOWN";
    public static final String PARTIAL = "PARTIAL";
    public static final String UNKNOWN_MODEL = "UNKNOWN_MODEL";
    public static final String UNKNOWN_RATE = "UNKNOWN_RATE";
    public static final String UNKNOWN_TOKENS = "UNKNOWN_TOKENS";
    public static final String DISABLED = "DISABLED";

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    private final AgentTraceProperties properties;
    private final AtomicBoolean invalidRateWarned = new AtomicBoolean(false);

    public TraceCostCalculator(AgentTraceProperties properties) {
        this.properties = properties;
    }

    public CostResult calculate(String sessionModel, Long promptTokens, Long completionTokens,
                                TraceLlmUsageStat usage) {
        return calculate(sessionModel, promptTokens, completionTokens, usage, 0L);
    }

    public CostResult calculate(String sessionModel, Long promptTokens, Long completionTokens,
                                TraceLlmUsageStat usage, long droppedCount) {
        AgentTraceProperties.Pricing pricing = properties.getPricing();
        if (pricing == null || !pricing.isEnabled()) {
            return unknown(DISABLED, pricing);
        }
        if (droppedCount > 0) {
            // 有丢失事件时无法确认 Token 是否完整，绝不返回 KNOWN
            return unknown(PARTIAL, pricing);
        }
        if (!modelComplete(sessionModel, usage)) {
            return unknown(UNKNOWN_MODEL, pricing);
        }
        AgentTraceProperties.ModelPrice price = findPrice(pricing, sessionModel);
        if (!validPrice(price)) {
            if (invalidRateWarned.compareAndSet(false, true)) {
                log.warn("Trace 成本费率缺失或非法 model={}，成本将显示为不可估算", sessionModel);
            }
            return unknown(UNKNOWN_RATE, pricing);
        }
        if (!usageComplete(usage) || invalidTokens(promptTokens) || invalidTokens(completionTokens)) {
            return unknown(UNKNOWN_TOKENS, pricing);
        }
        BigDecimal amount = BigDecimal.valueOf(promptTokens)
                .multiply(price.getInputPerMillion())
                .add(BigDecimal.valueOf(completionTokens).multiply(price.getOutputPerMillion()))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        return new CostResult(amount, currency(pricing), KNOWN, version(pricing));
    }

    private boolean modelComplete(String sessionModel, TraceLlmUsageStat usage) {
        if (sessionModel == null || sessionModel.isBlank() || usage == null) {
            return false;
        }
        if (nz(usage.getResponseCount()) <= 0 || nz(usage.getMissingModelCount()) > 0) {
            return false;
        }
        return nz(usage.getModelCount()) == 1
                && sessionModel.trim().equals(usage.getModel());
    }

    private boolean usageComplete(TraceLlmUsageStat usage) {
        return usage != null
                && nz(usage.getResponseCount()) > 0
                && nz(usage.getMissingUsageCount()) == 0;
    }

    private AgentTraceProperties.ModelPrice findPrice(AgentTraceProperties.Pricing pricing,
                                                      String sessionModel) {
        Map<String, AgentTraceProperties.ModelPrice> models = pricing.getModels();
        return models == null ? null : models.get(sessionModel.trim());
    }

    private boolean validPrice(AgentTraceProperties.ModelPrice price) {
        return price != null
                && price.getInputPerMillion() != null
                && price.getInputPerMillion().signum() >= 0
                && price.getOutputPerMillion() != null
                && price.getOutputPerMillion().signum() >= 0;
    }

    private boolean invalidTokens(Long tokens) {
        return tokens == null || tokens < 0;
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    private CostResult unknown(String status, AgentTraceProperties.Pricing pricing) {
        return new CostResult(null, currency(pricing), status, version(pricing));
    }

    private String currency(AgentTraceProperties.Pricing pricing) {
        return pricing == null || pricing.getCurrency() == null || pricing.getCurrency().isBlank()
                ? "CNY" : pricing.getCurrency();
    }

    private String version(AgentTraceProperties.Pricing pricing) {
        return pricing == null || pricing.getVersion() == null || pricing.getVersion().isBlank()
                ? "config" : pricing.getVersion();
    }

    public record CostResult(BigDecimal amount, String currency, String status, String pricingVersion) {
    }
}
