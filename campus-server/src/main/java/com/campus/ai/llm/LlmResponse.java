package com.campus.ai.llm;

import java.util.List;

/** 一次流式调用的完整结果 */
public record LlmResponse(String content, String finishReason, List<LlmToolCall> toolCalls, int totalTokens) {
}
