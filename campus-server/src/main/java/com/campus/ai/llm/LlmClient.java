package com.campus.ai.llm;

import com.campus.ai.config.LlmProperties;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * OpenAI 兼容流式客户端（DashScope 兼容模式已验证）。
 * SSE 逐行解析：文本 delta 实时回调（最终回复才流式出境）；工具调用按 index 累积参数片段，
 * 流结束后整体返回给 AgentService 状态机，避免半截参数被误用。
 */
@Slf4j
@Component
public class LlmClient {

    private final LlmProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmClient(LlmProperties properties) {
        this.properties = properties;
    }

    public LlmResponse chat(List<Map<String, Object>> messages,
                            List<Map<String, Object>> tools,
                            Consumer<String> deltaSink,
                            BooleanSupplier cancelled) {
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            log.error("campus.llm.api-key 未配置（application-secrets.properties 或环境变量 DASHSCOPE_API_KEY）");
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "AI 助手未配置模型 API Key");
        }
        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", messages);
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        body.put("max_tokens", properties.getMaxTokens());
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
        }
        HttpRequest request = buildRequest(body);

        StringBuilder content = new StringBuilder();
        TreeMap<Integer, String> toolIds = new TreeMap<>();
        TreeMap<Integer, String> toolNames = new TreeMap<>();
        TreeMap<Integer, String> toolArgs = new TreeMap<>();
        String finishReason = null;
        int totalTokens = 0;
        try {
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("LLM 调用失败 HTTP {} body={}", response.statusCode(), abbreviate(error));
                throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(),
                        "模型服务暂时不可用（HTTP " + response.statusCode() + "）");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "客户端已取消");
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if (payload.equals("[DONE]")) {
                        break;
                    }
                    JsonNode chunk;
                    try {
                        chunk = mapper.readTree(payload);
                    } catch (Exception ex) {
                        // 厂商流式输出偶发坏行：跳过该行而不是整个调用失败（D1 歪流容错）
                        log.warn("LLM 流式 data 行解析失败，已跳过：{}", abbreviate(payload));
                        continue;
                    }
                    JsonNode usage = chunk.path("usage");
                    if (usage.isObject()) {
                        totalTokens = usage.path("total_tokens").asInt(totalTokens);
                    }
                    JsonNode choice = chunk.path("choices").path(0);
                    JsonNode delta = choice.path("delta");
                    String piece = delta.path("content").asText("");
                    if (!piece.isEmpty()) {
                        content.append(piece);
                        if (deltaSink != null) {
                            deltaSink.accept(piece);
                        }
                    }
                    JsonNode toolCalls = delta.path("tool_calls");
                    if (toolCalls.isArray()) {
                        for (JsonNode call : toolCalls) {
                            int index = call.path("index").asInt(0);
                            // 分片兜底：首个片段缺 id 时用合成 id 占位（真实 id 迟到可覆盖），
                            // 否则 name/args 完整的调用会因 id 为空被整体丢弃
                            String id = call.path("id").asText("");
                            if (!id.isEmpty()) {
                                toolIds.put(index, id);
                            } else {
                                toolIds.putIfAbsent(index, "piece_" + index);
                            }
                            String name = call.path("function").path("name").asText("");
                            if (!name.isEmpty()) {
                                toolNames.merge(index, name, String::concat);
                            }
                            String args = call.path("function").path("arguments").asText("");
                            if (!args.isEmpty()) {
                                toolArgs.merge(index, args, String::concat);
                            }
                        }
                    }
                    String finish = choice.path("finish_reason").asText("");
                    if (!finish.isEmpty()) {
                        finishReason = finish;
                    }
                }
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (java.io.IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("LLM 调用异常：{}", ex.toString());
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "模型调用失败或超时，请稍后重试");
        }

        List<LlmToolCall> toolCallList = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : toolIds.entrySet()) {
            String name = toolNames.getOrDefault(entry.getKey(), "");
            if (entry.getValue().isBlank() || name.isBlank()) {
                continue;
            }
            toolCallList.add(new LlmToolCall(
                    entry.getValue(),
                    name,
                    toolArgs.getOrDefault(entry.getKey(), "{}")));
        }
        return new LlmResponse(
                content.toString(),
                finishReason == null ? "stop" : finishReason,
                toolCallList,
                totalTokens);
    }

    private HttpRequest buildRequest(Map<String, Object> body) {
        try {
            return HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (Exception ex) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR.getCode(), "模型请求构造失败");
        }
    }

    private String abbreviate(String text) {
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
