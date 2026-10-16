package com.campus.ai.llm;

/** OpenAI 兼容协议的工具调用（SSE tool_calls 按 index 累积完成后给出完整 arguments） */
public record LlmToolCall(String id, String name, String arguments) {
}
