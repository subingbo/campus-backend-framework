package com.campus.ai.llm;

import java.util.List;

/**
 * 一次流式调用的完整结果。
 *
 * <p>totalTokens/promptTokens/completionTokens 只在服务端实际返回 usage 时非 null；
 * 未返回保持 null，不能伪造 0 或强行估算成官方账单。</p>
 */
public record LlmResponse(String content,
                          String finishReason,
                          List<LlmToolCall> toolCalls,
                          Integer totalTokens,
                          Integer promptTokens,
                          Integer completionTokens) {

    /** 兼容既有调用/测试：只有总 token 时 prompt/completion 为 null */
    public LlmResponse(String content, String finishReason, List<LlmToolCall> toolCalls, Integer totalTokens) {
        this(content, finishReason, toolCalls, totalTokens, null, null);
    }
}
