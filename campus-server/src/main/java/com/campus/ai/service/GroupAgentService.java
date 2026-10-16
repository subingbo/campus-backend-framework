package com.campus.ai.service;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.entity.AiConversation;
import com.campus.ai.mapper.AiConversationMapper;
import com.campus.ai.tool.AgentToolkit;
import com.campus.map.entity.SharedMap;
import com.campus.map.mapper.MapMemberMapper;
import com.campus.map.mapper.SharedMapMapper;
import com.campus.social.dto.ChatMessageResponse;
import com.campus.social.entity.Conversation;
import com.campus.social.entity.Message;
import com.campus.social.mapper.ConversationMapper;
import com.campus.social.mapper.MessageMapper;
import com.campus.user.entity.User;
import com.campus.user.mapper.UserMapper;
import com.campus.ws.WsSessionRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 群聊 @AI 协同（计划 模式二 / P2）：
 * ChatMessageService 在 GROUP 会话里检测到 "@AI" 前缀后交由本服务：
 * 1) 建一条归属发起人的 ai_conversation 以复用既有 AgentService/工具链与幂等保存；
 * 2) 线程语境注入 viewerId+groupId（AgentToolkit.find_free_windows 走群体忙闲，GROUP CONTEXT 由后端会话注入，
 *    不可被模型伪造）；未授权成员的忙闲不出现在窗口计算里，规划结果会注明"暂定"；
 * 3) 结束后把最终答复落库为群消息（type=AGENT，sender 明示发起人代发），并向群成员广播 AGENT_PLAN_CARD 帧。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupAgentService {

    private static final String PEOPLE_PREFIX = "@AI";
    private static final String FRAME_TYPE = "AGENT_PLAN_CARD";

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final UserMapper userMapper;
    private final SharedMapMapper sharedMapMapper;
    private final MapMemberMapper mapMemberMapper;
    private final AiConversationMapper aiConversationMapper;
    private final AgentService agentService;
    private final WsSessionRegistry wsSessionRegistry;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private final ExecutorService pool = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "agent-group");
        thread.setDaemon(true);
        return thread;
    });

    /** 判定并触发：仅 GROUP 会话 + "@AI" 开头（已在调用方完成消息落库） */
    public boolean maybeTrigger(Long askerId, Conversation conversation, String text) {
        if (conversation == null
                || conversation.getMapId() == null
                || text == null
                || !text.startsWith(PEOPLE_PREFIX)) {
            return false;
        }
        String prompt = text.substring(PEOPLE_PREFIX.length()).trim();
        if (!StringUtils.hasText(prompt)) {
            return false;
        }
        Long mapId = conversation.getMapId();
        SharedMap map = sharedMapMapper.selectById(mapId);
        if (map == null) {
            log.warn("@AI 触发被忽略：会话 {} 的地图不存在", conversation.getId());
            return false;
        }
        long groupId = map.getGroupId();
        pool.submit(() -> runForGroup(askerId, conversation.getId(), groupId, prompt));
        return true;
    }

    private void runForGroup(Long askerId, Long conversationId, long groupId, String prompt) {
        AiConversation conversation = new AiConversation();
        conversation.setUserId(askerId);
        conversation.setStatus(AiConversation.STATUS_CHATTING);
        conversation.setPlanVersion(0);
        aiConversationMapper.insert(conversation);
        AiConversation prepared = aiConversationMapper.selectById(conversation.getId());
        if (prepared == null) {
            log.error("@AI 会话创建失败 asker={} conversation={}", askerId, conversationId);
            return;
        }

        StringBuilder finalText = new StringBuilder();
        Map<String, PlanDto> planHolder = new LinkedHashMap<>();
        AgentChatListener listener = createListener(askerId, finalText, planHolder);
        AgentToolkit.setGroupContext(askerId, groupId);
        try {
            // 场景 D 续聊：把 24h 内该群上一轮草稿与 AGENT 回复带进本轮输入，支持“把第二个方案创建成活动”
            String effectivePrompt = prompt + continuationContext(askerId, groupId, conversationId);
            agentService.run(prepared, effectivePrompt, listener);
        } catch (Exception ex) {
            log.error("@AI 规划异常 conversation={}：{}", conversationId, ex.toString());
        } finally {
            AgentToolkit.clearGroupContext();
        }

        String summary = finalText.toString().trim();
        if (!StringUtils.hasText(summary)) {
            summary = "AI 暂时无法完成这个规划，请换个说法或在 /assistant 里重试。";
        }
        persistGroupReply(conversationId, askerId, summary);
        PlanDto plan = planHolder.get("plan");
        if (plan != null) {
            broadcastCard(conversationId, plan);
        }
    }

    /**
     * 续聊上下文注入：24h 内发起人在本群最近一次的 AGENT 回复（方案对比文本就在其中），
     * 追加到本轮输入末尾；没有则返回空串。内容一律截断防膨胀。
     */
    private String continuationContext(Long askerId, long groupId, long chatConversationId) {
        try {
            com.campus.social.entity.Message recent = messageMapper.selectAgentReply(chatConversationId);
            if (recent == null || recent.getCreatedAt() == null
                    || recent.getCreatedAt().isBefore(java.time.LocalDateTime.now().minusHours(24))) {
                return "";
            }
            String content = recent.getContent();
            if (!StringUtils.hasText(content)) {
                return "";
            }
            if (content.length() > 800) {
                content = content.substring(0, 800);
            }
            String context = "（上下文：这是本群上一轮 @AI 的回复，用户可能基于它说“第几个方案”之类的续聊，"
                    + "如需此前数据请重新调用工具核实）：" + content;
            return "\n" + context;
        } catch (Exception ex) {
            log.warn("续聊上下文构建失败：{}", ex.toString());
            return "";
        }
    }

    private AgentChatListener createListener(Long askerId, StringBuilder finalText,
                                             Map<String, PlanDto> planHolder) {
        return new AgentChatListener() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onStatus(String stage) {
                // 群聊不推送状态—结束发卡片即可
            }

            @Override
            public void onDelta(String text) {
                if (StringUtils.hasText(text)) {
                    finalText.append(text);
                }
            }

            @Override
            public void onPlan(PlanDto plan) {
                planHolder.put("plan", plan);
            }

            @Override
            public void onDone() {
            }

            @Override
            public void onError(String message) {
                finalText.setLength(0);
                finalText.append("AI 处理失败：").append(message == null ? "请稍后重试" : message);
            }
        };
    }

    /** 群消息落库 + WS 广播（type=AGENT，sender=发起人代发；前端有卡片渲染提示）。
     *  message.content 为 VARCHAR(1000)：超长截断（全文已在 ai_message/卡片），异常必须显式记录——
     *  线程池里未捕获异常会被静默吞掉（实测教训：长回复曾导致群回复“消失”且无任何日志）。 */
    private void persistGroupReply(Long conversationId, Long askerId, String content) {
        String safe = content == null ? "" : content;
        if (safe.length() > 1000) {
            // message.content 为 VARCHAR(1000)：正文 992 +省略符号 8 字以内，保证 ≤1000（全文已在 ai_message 与卡片）
            safe = safe.substring(0, 992) + "……(后略)";
        }
        try {
            User asker = userMapper.selectById(askerId);
            Message message = new Message();
            message.setConversationId(conversationId);
            message.setSenderId(askerId);
            message.setContent(safe);
            message.setType("AGENT");
            messageMapper.insert(message);
            ChatMessageResponse response = new ChatMessageResponse();
            response.setId(message.getId());
            response.setConversationId(conversationId);
            response.setSenderId(askerId);
            response.setSenderNickname(asker != null
                    ? (StringUtils.hasText(asker.getNickname()) ? asker.getNickname() : asker.getUsername())
                    : "");
            response.setContent(safe);
            response.setType("AGENT");
            response.setCreatedAt(message.getCreatedAt());
            broadcastChatMessage(conversationId, "CHAT_MESSAGE", Map.of("message", response));
        } catch (Exception ex) {
            log.error("AGENT 群回复落库/广播失败 conversation={} asker={}：{}",
                    conversationId, askerId, ex.toString());
        }
    }

    private void broadcastCard(Long conversationId, PlanDto plan) {
        try {
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("type", FRAME_TYPE);
            card.put("conversationId", conversationId);
            card.put("plan", plan);
            broadcastChatMessage(conversationId, FRAME_TYPE, card);
        } catch (Exception ex) {
            log.warn("群规划卡片广播失败 conversation={}：{}", conversationId, ex.toString());
        }
    }

    private void broadcastChatMessage(Long conversationId, String type, Map<String, Object> payload) {
        List<Long> recipients = recipientsOf(conversationId);
        Map<String, Object> frame = new LinkedHashMap<>(payload);
        frame.put("type", type);
        wsSessionRegistry.sendToUsers(recipients, frame);
    }

    private List<Long> recipientsOf(Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || conversation.getMapId() == null) {
            return List.of();
        }
        return mapMemberMapper.selectByMapId(conversation.getMapId()).stream()
                .map(m -> m.getUserId())
                .distinct()
                .toList();
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
