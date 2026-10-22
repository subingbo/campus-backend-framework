package com.campus.social.service;

import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.map.dto.MapMemberResponse;
import com.campus.map.entity.MapMember;
import com.campus.map.mapper.MapMemberMapper;
import com.campus.social.constant.ConversationType;
import com.campus.social.constant.SocialRedisKey;
import com.campus.social.dto.ChatMessageResponse;
import com.campus.social.entity.Conversation;
import com.campus.social.entity.Message;
import com.campus.social.mapper.ConversationMapper;
import com.campus.social.mapper.ConversationMemberMapper;
import com.campus.social.mapper.MessageMapper;
import com.campus.social.mapper.FriendMapper;
import com.campus.user.entity.User;
import com.campus.user.mapper.UserMapper;
import com.campus.ws.WsSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 聊天消息管线：访问校验 → MySQL 落库 → 在线成员实时推送 → 离线成员未读计数。
 * 群聊接收人以地图成员为准（conversation_member 仅用于会话列表归属）。
 */
@Service
@RequiredArgsConstructor
public class ChatMessageService {

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper conversationMemberMapper;
    private final MessageMapper messageMapper;
    private final MapMemberMapper mapMemberMapper;
    private final FriendMapper friendMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final WsSessionRegistry wsSessionRegistry;
    private final com.campus.ai.service.GroupAgentService groupAgentService;

    /** 发送结果；duplicate=true 表示命中了 clientMsgId 幂等键，未再次落库/广播 */
    public record SendResult(ChatMessageResponse response, boolean duplicate) {
    }

    public ChatMessageResponse send(Long senderId, Long conversationId, String content) {
        return send(senderId, conversationId, content, null).response();
    }

    public SendResult send(Long senderId, Long conversationId, String content, String clientMsgId) {
        Conversation conversation = requireConversation(conversationId);
        assertCanAccess(conversation, senderId);
        String text = content == null ? "" : content.trim();
        if (!StringUtils.hasText(text)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "消息内容不能为空");
        }
        if (text.length() > 1000) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "消息最长 1000 字");
        }
        String normalizedClientId = StringUtils.hasText(clientMsgId) ? clientMsgId.trim() : null;
        if (normalizedClientId != null && normalizedClientId.length() > 64) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "clientMsgId 过长");
        }
        User sender = userMapper.selectById(senderId);
        if (sender == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }

        // 幂等命中：断线重试同一 clientMsgId，不重复落库、不重复广播给其他成员、不重复触发群 @AI
        if (normalizedClientId != null) {
            ChatMessageResponse existing = messageMapper.selectByClientMsgId(
                    conversationId, senderId, normalizedClientId);
            if (existing != null) {
                wsSessionRegistry.sendToUser(senderId, frame("CHAT_MESSAGE", existing));
                return new SendResult(existing, true);
            }
        }

        Message message = new Message();
        message.setConversationId(conversationId);
        message.setSenderId(senderId);
        message.setContent(text);
        message.setType("TEXT");
        message.setClientMsgId(normalizedClientId);
        try {
            messageMapper.insert(message);
        } catch (DuplicateKeyException ex) {
            // 并发重试：唯一键已存在，读回原消息
            if (normalizedClientId == null) {
                throw ex;
            }
            ChatMessageResponse existing = messageMapper.selectByClientMsgId(
                    conversationId, senderId, normalizedClientId);
            if (existing == null) {
                throw ex;
            }
            wsSessionRegistry.sendToUser(senderId, frame("CHAT_MESSAGE", existing));
            return new SendResult(existing, true);
        }

        ChatMessageResponse response = toResponse(message, resolveNickname(sender));
        fanout(conversation, senderId, response);

        // P2 群聊 @AI：GROUP 会话 + "@AI" 前缀触发时空协同规划（异步，不阻塞 ws 线程）
        if (ConversationType.GROUP.equals(conversation.getType())) {
            groupAgentService.maybeTrigger(senderId, conversation, text);
        }
        return new SendResult(response, false);
    }

    public void forwardTyping(Long senderId, Long conversationId) {
        Conversation conversation = requireConversation(conversationId);
        assertCanAccess(conversation, senderId);
        User sender = userMapper.selectById(senderId);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "TYPING");
        frame.put("conversationId", conversationId);
        frame.put("userId", senderId);
        frame.put("nickname", sender != null ? resolveNickname(sender) : "");
        for (Long userId : resolveRecipients(conversation)) {
            if (!userId.equals(senderId) && wsSessionRegistry.isOnline(userId)) {
                wsSessionRegistry.sendToUser(userId, frame);
            }
        }
    }

    /** 访问规则：PRIVATE=仍是好友；GROUP=地图成员；PLACE=登录即可（全校可见） */
    public Conversation assertCanAccess(Conversation conversation, Long userId) {
        if (conversation == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "会话不存在");
        }
        String type = conversation.getType();
        if (ConversationType.PRIVATE.equals(type)) {
            List<Long> memberIds = conversationMemberMapper.selectUserIdsByConversation(conversation.getId());
            if (!memberIds.contains(userId)) {
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "不是会话成员");
            }
            Long peer = memberIds.stream().filter(id -> !id.equals(userId)).findFirst().orElse(null);
            if (peer == null || friendMapper.existsBetween(userId, peer) == 0) {
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "已成为非好友，无法继续会话");
            }
        } else if (ConversationType.GROUP.equals(type)) {
            MapMember member = conversation.getMapId() == null ? null
                    : mapMemberMapper.selectByMapAndUser(conversation.getMapId(), userId);
            if (member == null) {
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "不是地图成员");
            }
        } else if (!ConversationType.PLACE.equals(type)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "未知会话类型");
        }
        return conversation;
    }

    public List<Long> resolveRecipients(Conversation conversation) {
        if (ConversationType.GROUP.equals(conversation.getType()) && conversation.getMapId() != null) {
            return mapMemberMapper.selectByMapId(conversation.getMapId()).stream()
                    .map(MapMemberResponse::getUserId)
                    .distinct()
                    .toList();
        }
        return conversationMemberMapper.selectUserIdsByConversation(conversation.getId());
    }

    public void clearUnread(Long userId, Long conversationId) {
        stringRedisTemplate.delete(SocialRedisKey.unread(userId, conversationId));
    }

    private void fanout(Conversation conversation, Long senderId, ChatMessageResponse response) {
        List<Long> recipients = resolveRecipients(conversation);
        for (Long userId : recipients) {
            if (userId.equals(senderId)) {
                wsSessionRegistry.sendToUser(userId, frame("CHAT_MESSAGE", response));
                continue;
            }
            Long unread = stringRedisTemplate.opsForValue()
                    .increment(SocialRedisKey.unread(userId, conversation.getId()));
            if (wsSessionRegistry.isOnline(userId)) {
                wsSessionRegistry.sendToUser(userId, frame("CHAT_MESSAGE", response));
                Map<String, Object> unreadFrame = new LinkedHashMap<>();
                unreadFrame.put("type", "UNREAD");
                unreadFrame.put("conversationId", conversation.getId());
                unreadFrame.put("count", unread == null ? 1 : unread);
                wsSessionRegistry.sendToUser(userId, unreadFrame);
            }
        }
    }

    private Map<String, Object> frame(String type, ChatMessageResponse message) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.put("message", message);
        return frame;
    }

    private Conversation requireConversation(Long conversationId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "会话不存在");
        }
        return conversation;
    }

    private ChatMessageResponse toResponse(Message message, String senderNickname) {
        ChatMessageResponse response = new ChatMessageResponse();
        response.setId(message.getId());
        response.setConversationId(message.getConversationId());
        response.setSenderId(message.getSenderId());
        response.setSenderNickname(senderNickname);
        response.setContent(message.getContent());
        response.setType(message.getType());
        response.setClientMsgId(message.getClientMsgId());
        response.setCreatedAt(message.getCreatedAt());
        return response;
    }

    private String resolveNickname(User user) {
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }
}
