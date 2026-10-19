package com.campus.ai;

import com.campus.common.exception.BusinessException;
import com.campus.ai.config.LlmProperties;
import com.campus.ai.llm.LlmClient;
import com.campus.ai.llm.LlmResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LlmClient 与 OpenAI 兼容 SSE 流的契约测试（阶段 D：分片容错/歪流/非200/取消）。
 * 用 JDK 内置 HttpServer 起本地桩，载荷一律 ObjectMapper 构造杜绝手拼 JSON 配对错误；不依赖 Spring，秒级运行。
 */
class LlmClientStreamContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 启动桩服务器并返回指向它的 LlmClient（context 固定 /v1/chat/completions） */
    private LlmClient start(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", handler);
        server.setExecutor(Executors.newFixedThreadPool(3));
        server.start();
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("contract-test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        return new LlmClient(properties);
    }

    private List<Map<String, Object>> userMessage() {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", "周末去哪逛逛");
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(message);
        return messages;
    }

    private String data(Object payload) throws Exception {
        return "data: " + MAPPER.writeValueAsString(payload);
    }

    /** choice 载荷：delta + 可选 finish_reason */
    private Map<String, Object> choice(Object delta, String finishReason) {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("delta", delta);
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("choices", List.of(choice));
        return root;
    }

    /** tool_calls 分片片段；id/name/arguments 缺哪个传 null（不进序列化） */
    private Map<String, Object> toolFragment(int index, String id, String name, String arguments) {
        Map<String, Object> fragment = new LinkedHashMap<>();
        fragment.put("index", index);
        if (id != null) {
            fragment.put("id", id);
        }
        Map<String, Object> function = new LinkedHashMap<>();
        if (name != null) {
            function.put("name", name);
        }
        if (arguments != null) {
            function.put("arguments", arguments);
        }
        fragment.put("function", function);
        return fragment;
    }

    private void writeResponse(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void writeStream(HttpExchange exchange, String... chunks) throws IOException {
        exchange.sendResponseHeaders(200, 0);
        OutputStream out = exchange.getResponseBody();
        for (String chunk : chunks) {
            out.write(chunk.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        out.close();
    }

    @Test
    void toolCallFragmentWithoutIdStillAssembled() throws Exception {
        String chunk1 = data(choice(Map.of("tool_calls", List.of(
                toolFragment(0, null, "submit_plan", "{\"tit"))), null));
        String chunk2 = data(choice(Map.of("tool_calls", List.of(
                toolFragment(0, null, null, "le\":\"校园夜跑}"))), "tool_calls"));
        LlmResponse response = start(exchange -> writeStream(exchange,
                chunk1 + "\n\n", chunk2 + "\n\n", "data: [DONE]\n\n"))
                .chat(userMessage(), null, null, null);

        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).name()).isEqualTo("submit_plan");
        assertThat(response.toolCalls().get(0).id()).isEqualTo("piece_0");
        assertThat(response.toolCalls().get(0).arguments()).isEqualTo("{\"title\":\"校园夜跑}");
        assertThat(response.finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void realIdOverridesSyntheticId() throws Exception {
        String chunk1 = data(choice(Map.of("tool_calls", List.of(
                toolFragment(0, null, "list_my_maps", "{}"))), null));
        String chunk2 = data(choice(Map.of("tool_calls", List.of(
                toolFragment(0, "call_real", null, "{}"))), "tool_calls"));
        LlmResponse response = start(exchange -> writeStream(exchange,
                chunk1 + "\n\n", chunk2 + "\n\n", "data: [DONE]\n\n"))
                .chat(userMessage(), null, null, null);

        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).id()).isEqualTo("call_real");
    }

    @Test
    void malformedLinesAndCommentSkippedContentAssembled() throws Exception {
        String good = data(choice(Map.of("content", "你好"), null)) + "\n\n";
        LlmResponse response = start(exchange -> writeStream(exchange,
                "event: ping\n\n",
                ": comment only\n\n",
                "data: this-is-not-json\n",
                good,
                "data: [DONE]\n\n"))
                .chat(userMessage(), null, null, null);

        assertThat(response.content()).isEqualTo("你好");
        assertThat(response.toolCalls()).isEmpty();
        assertThat(response.finishReason()).isEqualTo("stop");
    }

    @Test
    void usageTokensTakenFromLastUsageChunk() throws Exception {
        Map<String, Object> root1 = new LinkedHashMap<>(choice(Map.of("content", "第一段"), null));
        root1.put("usage", Map.of("total_tokens", 10));
        Map<String, Object> root2 = new LinkedHashMap<>(choice(new LinkedHashMap<>(), "stop"));
        root2.put("usage", Map.of("total_tokens", 66));
        String chunk1 = data(root1) + "\n\n";
        String chunk2 = data(root2) + "\n\n";
        LlmResponse response = start(exchange -> writeStream(exchange,
                chunk1, chunk2, "data: [DONE]\n\n"))
                .chat(userMessage(), null, null, null);

        assertThat(response.content()).isEqualTo("第一段");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.totalTokens()).isEqualTo(66);
    }

    @Test
    void usagePromptCompletionAndTotalAreParsedWhenReturned() throws Exception {
        Map<String, Object> root = new LinkedHashMap<>(choice(Map.of("content", "好的"), "stop"));
        root.put("usage", Map.of("total_tokens", 30, "prompt_tokens", 20, "completion_tokens", 10));
        String chunk = data(root) + "\n\n";
        LlmResponse response = start(exchange -> writeStream(exchange,
                chunk, "data: [DONE]\n\n"))
                .chat(userMessage(), null, null, null);

        assertThat(response.totalTokens()).isEqualTo(30);
        assertThat(response.promptTokens()).isEqualTo(20);
        assertThat(response.completionTokens()).isEqualTo(10);
    }

    @Test
    void missingUsageLeavesTokensNullInsteadOfZero() throws Exception {
        String chunk = data(choice(Map.of("content", "没有 usage"), "stop")) + "\n\n";
        LlmResponse response = start(exchange -> writeStream(exchange,
                chunk, "data: [DONE]\n\n"))
                .chat(userMessage(), null, null, null);

        assertThat(response.totalTokens()).isNull();
        assertThat(response.promptTokens()).isNull();
        assertThat(response.completionTokens()).isNull();
    }

    @Test
    void nonHttp200SurfacesStatusAndKey() throws Exception {
        LlmClient client = start(exchange ->
                writeResponse(exchange, 500, "provider boom".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> client.chat(userMessage(), null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("500");
    }

    @Test
    void cancellationCheckedPerLineThrowsFast() throws Exception {
        BooleanSupplier cancelled = () -> true;
        String payload = data(choice(Map.of("content", "迟到的流"), null)) + "\n\n";
        assertThatThrownBy(() -> start(exchange -> writeStream(exchange, payload))
                .chat(userMessage(), null, null, cancelled))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已取消");
    }
}
