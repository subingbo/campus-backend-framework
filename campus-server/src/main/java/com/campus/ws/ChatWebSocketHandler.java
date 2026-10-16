package com.campus.ws;

import com.campus.social.service.ChatMessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * 聊天 WebSocket 处理器。
 * 上行帧：CHAT_SEND / TYPING / READ / PING；下行帧见各 service 的 push 逻辑。
 * 身份在握手阶段写入 session attributes，此处不再走 SecurityContext。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final WsSessionRegistry wsSessionRegistry;
    private final ChatMessageService chatMessageService;
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
            sendError(session, "消息格式错误");
            return;
        }
        String type = node.path("type").asText("");
        try {
            switch (type) {
                case "CHAT_SEND" -> chatMessageService.send(
                        userId, node.path("conversationId").asLong(), node.path("content").asText(""));
                case "TYPING" -> chatMessageService.forwardTyping(
                        userId, node.path("conversationId").asLong());
                case "READ" -> chatMessageService.clearUnread(
                        userId, node.path("conversationId").asLong());
                case "PING" -> wsSessionRegistry.sendToUser(userId, Map.of("type", "PONG"));
                default -> sendError(session, "未知的消息类型");
            }
        } catch (Exception ex) {
            sendError(session, ex.getMessage());
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

    private void sendError(WebSocketSession session, String message) {
        wsSessionRegistry.sendToUser(userId(session), Map.of("type", "ERROR", "message", message == null ? "处理失败" : message));
    }
}
