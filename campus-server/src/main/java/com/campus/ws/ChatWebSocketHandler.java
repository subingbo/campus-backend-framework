package com.campus.ws;

import com.campus.social.service.ChatMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 聊天 WebSocket 处理器。
 * 上行帧：CHAT_SEND / AGENT_CANCEL / TYPING / READ / PING；下行帧见各 service 的 push 逻辑。
 * 身份在握手阶段写入 session attributes，此处不再走 SecurityContext。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final WsSessionRegistry wsSessionRegistry;
    private final ChatMessageService chatMessageService;
    private final com.campus.ai.service.GroupAgentService groupAgentService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = userId(session);
        if (userId == null) {
            return;
        }
        wsSessionRegistry.register(userId, session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = userId(session);
        if (userId == null) {
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(message.getPayload());
        } catch (Exception ex) {
            sendError(session, "消息格式错误", null);
            return;
        }
        String type = node.path("type").asText("");
        String clientMsgId = node.path("clientMsgId").asText(null);
        try {
            switch (type) {
                case "CHAT_SEND" -> {
                    long conversationId = node.path("conversationId").asLong();
                    ChatMessageService.SendResult result = chatMessageService.send(
                            userId, conversationId, node.path("content").asText(""), clientMsgId);
                    // 落库成功后才回 ACK；发送方据此出队，重试靠 clientMsgId 唯一键去重
                    Map<String, Object> ack = new LinkedHashMap<>();
                    ack.put("type", "CHAT_ACK");
                    ack.put("conversationId", conversationId);
                    ack.put("clientMsgId", result.response().getClientMsgId());
                    ack.put("messageId", result.response().getId());
                    ack.put("duplicate", result.duplicate());
                    wsSessionRegistry.sendToUser(userId, ack);
                }
                case "AGENT_CANCEL" -> {
                    boolean cancelled = groupAgentService.cancel(
                            userId,
                            node.path("conversationId").asLong(),
                            node.path("runId").asText(null));
                    if (!cancelled) {
                        sendError(session, "无法取消：任务已结束或没有权限", clientMsgId);
                    }
                }
                case "TYPING" -> chatMessageService.forwardTyping(
                        userId, node.path("conversationId").asLong());
                case "READ" -> {
                    long conversationId = node.path("conversationId").asLong();
                    chatMessageService.clearUnread(userId, conversationId);
                    // 回执 count=0：前端据此刷新消息中心角标，避免“已读后角标仍停在旧值”
                    Map<String, Object> readFrame = new LinkedHashMap<>();
                    readFrame.put("type", "UNREAD");
                    readFrame.put("conversationId", conversationId);
                    readFrame.put("count", 0);
                    wsSessionRegistry.sendToUser(userId, readFrame);
                }
                case "PING" -> wsSessionRegistry.sendToUser(userId, Map.of("type", "PONG"));
                default -> sendError(session, "未知的消息类型", clientMsgId);
            }
        } catch (Exception ex) {
            sendError(session, ex.getMessage(), clientMsgId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        wsSessionRegistry.unregister(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        try {
            session.close();
        } catch (Exception ignored) {
            // 传输层已异常，关闭失败无需处理
        }
        wsSessionRegistry.unregister(session);
    }

    private Long userId(WebSocketSession session) {
        return (Long) session.getAttributes().get(WsHandshakeInterceptor.ATTR_USER_ID);
    }

    private void sendError(WebSocketSession session, String message, String clientMsgId) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "ERROR");
        frame.put("message", message == null ? "处理失败" : message);
        if (StringUtils.hasText(clientMsgId)) {
            frame.put("clientMsgId", clientMsgId);
        }
        wsSessionRegistry.sendToUser(userId(session), frame);
    }
}
