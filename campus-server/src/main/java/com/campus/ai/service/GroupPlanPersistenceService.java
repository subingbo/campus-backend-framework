package com.campus.ai.service;

import com.campus.ai.dto.PlanDto;
import com.campus.ai.entity.GroupAgentPlan;
import com.campus.ai.mapper.GroupAgentPlanMapper;
import com.campus.social.entity.Message;
import com.campus.social.mapper.MessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 群 AGENT 消息与规划快照同事务写入：任一步失败整体回滚。
 * 事务提交后才允许广播 WS 帧，避免“聊天有回复、刷新无卡片”的半状态。
 */
@Service
public class GroupPlanPersistenceService {

    private final MessageMapper messageMapper;
    private final GroupAgentPlanMapper groupAgentPlanMapper;
    private final ObjectMapper objectMapper;

    public GroupPlanPersistenceService(MessageMapper messageMapper,
                                       GroupAgentPlanMapper groupAgentPlanMapper,
                                       @Qualifier("redisObjectMapper") ObjectMapper objectMapper) {
        this.messageMapper = messageMapper;
        this.groupAgentPlanMapper = groupAgentPlanMapper;
        this.objectMapper = objectMapper;
    }

    public record PersistedReply(Long messageId, Long planId) {
    }

    @Transactional
    public PersistedReply persist(Long groupConversationId, Long askerId, String content,
                                  PlanDto plan, Long aiConversationId, Integer planVersion) {
        String safe = content == null ? "" : content;
        if (safe.length() > 1000) {
            safe = safe.substring(0, 992) + "……(后略)";
        }
        Message message = new Message();
        message.setConversationId(groupConversationId);
        message.setSenderId(askerId);
        message.setContent(safe);
        message.setType("AGENT");
        messageMapper.insert(message);

        Long planId = null;
        if (plan != null) {
            if (!StringUtils.hasText(plan.getTitle())) {
                throw new IllegalStateException("群规划快照缺少标题");
            }
            GroupAgentPlan snapshot = new GroupAgentPlan();
            snapshot.setGroupConversationId(groupConversationId);
            snapshot.setMessageId(message.getId());
            snapshot.setAiConversationId(aiConversationId);
            snapshot.setAskerId(askerId);
            try {
                snapshot.setPlanJson(objectMapper.writeValueAsString(plan));
            } catch (Exception ex) {
                throw new IllegalStateException("群规划快照序列化失败", ex);
            }
            snapshot.setPlanVersion(planVersion == null ? 1 : planVersion);
            groupAgentPlanMapper.insert(snapshot);
            planId = snapshot.getId();
        }
        return new PersistedReply(message.getId(), planId);
    }
}
