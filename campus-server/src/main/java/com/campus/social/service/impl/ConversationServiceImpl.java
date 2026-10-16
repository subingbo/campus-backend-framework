package com.campus.social.service.impl;

import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.map.dto.MapMemberResponse;
import com.campus.map.entity.SharedMap;
import com.campus.map.mapper.MapMemberMapper;
import com.campus.map.mapper.SharedMapMapper;
import com.campus.place.entity.Place;
import com.campus.place.mapper.PlaceMapper;
import com.campus.security.user.SecurityUtils;
import com.campus.social.constant.ConversationType;
import com.campus.social.constant.SocialRedisKey;
import com.campus.social.dto.ChatMessageResponse;
import com.campus.social.dto.ConversationResponse;
import com.campus.social.entity.Conversation;
import com.campus.social.entity.ConversationMember;
import com.campus.social.mapper.ConversationMapper;
import com.campus.social.mapper.ConversationMemberMapper;
import com.campus.social.mapper.FriendMapper;
import com.campus.social.mapper.MessageMapper;
import com.campus.social.service.ChatMessageService;
import com.campus.social.service.ConversationService;
import com.campus.user.entity.User;
import com.campus.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private final ConversationMapper conversationMapper;
    private final ConversationMemberMapper conversationMemberMapper;
    private final MessageMapper messageMapper;
    private final MapMemberMapper mapMemberMapper;
    private final SharedMapMapper sharedMapMapper;
    private final PlaceMapper placeMapper;
    private final UserMapper userMapper;
    private final FriendMapper friendMapper;
    private final ChatMessageService chatMessageService;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ConversationResponse> listMine() {
        Long me = SecurityUtils.getUserId();
        List<Long> conversationIds = conversationMemberMapper.selectConversationIdsByUser(me);
        List<ConversationResponse> result = new ArrayList<>();
        for (Long conversationId : conversationIds) {
            Conversation conversation = conversationMapper.selectById(conversationId);
            if (conversation != null) {
                result.add(buildResponse(conversation, me));
            }
        }
        result.sort(Comparator.comparing(
                (ConversationResponse c) -> c.getLastMessage() == null ? null : c.getLastMessage().getCreatedAt(),
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    @Override
    @Transactional
    public ConversationResponse getOrCreatePrivate(Long targetUserId) {
        Long me = SecurityUtils.getUserId();
        if (targetUserId.equals(me)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能和自己建立会话");
        }
        Conversation existing = conversationMapper.selectPrivateBetween(me, targetUserId);
        if (existing != null) {
            chatMessageService.assertCanAccess(existing, me);
            return buildResponse(existing, me);
        }
        if (friendMapper.existsBetween(me, targetUserId) == 0) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只能和好友私聊");
        }
        User target = userMapper.selectById(targetUserId);
        if (target == null || target.getStatus() == null || target.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        Conversation conversation = new Conversation();
        conversation.setType(ConversationType.PRIVATE);
        conversationMapper.insert(conversation);
        joinMember(conversation.getId(), me);
        joinMember(conversation.getId(), targetUserId);
        return buildResponse(conversation, me);
    }

    @Override
    @Transactional
    public ConversationResponse getOrCreateForMap(Long mapId) {
        Long me = SecurityUtils.getUserId();
        if (mapMemberMapper.selectByMapAndUser(mapId, me) == null) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "不是地图成员");
        }
        Conversation conversation = conversationMapper.selectByMapId(mapId);
        if (conversation == null) {
            conversation = new Conversation();
            conversation.setType(ConversationType.GROUP);
            conversation.setMapId(mapId);
            conversationMapper.insert(conversation);
        }
        syncMapMembers(conversation.getId(), mapId);
        return buildResponse(conversation, me);
    }

    @Override
    @Transactional
    public ConversationResponse getOrCreateForPlace(Long placeId) {
        Long me = SecurityUtils.getUserId();
        Conversation conversation = conversationMapper.selectByPlaceId(placeId);
        if (conversation == null) {
            conversation = new Conversation();
            conversation.setType(ConversationType.PLACE);
            conversation.setPlaceId(placeId);
            conversationMapper.insert(conversation);
        }
        joinMember(conversation.getId(), me);
        return buildResponse(conversation, me);
    }

    @Override
    public List<ChatMessageResponse> listMessages(Long conversationId, Long beforeId, Integer limit) {
        Long me = SecurityUtils.getUserId();
        Conversation conversation = conversationMapper.selectById(conversationId);
        chatMessageService.assertCanAccess(conversation, me);
        int size = limit == null ? 50 : Math.min(Math.max(limit, 1), 100);
        List<ChatMessageResponse> messages = messageMapper.selectByConversation(conversationId, beforeId, size);
        Collections.reverse(messages);
        chatMessageService.clearUnread(me, conversationId);
        return messages;
    }

    /** 群聊成员以地图成员为准，进入会话时幂等同步 */
    private void syncMapMembers(Long conversationId, Long mapId) {
        for (MapMemberResponse member : mapMemberMapper.selectByMapId(mapId)) {
            joinMember(conversationId, member.getUserId());
        }
    }

    private void joinMember(Long conversationId, Long userId) {
        ConversationMember member = new ConversationMember();
        member.setConversationId(conversationId);
        member.setUserId(userId);
        conversationMemberMapper.insertIgnore(member);
    }

    private ConversationResponse buildResponse(Conversation conversation, Long me) {
        ConversationResponse response = new ConversationResponse();
        response.setId(conversation.getId());
        response.setType(conversation.getType());
        response.setMapId(conversation.getMapId());
        response.setPlaceId(conversation.getPlaceId());
        response.setCreatedAt(conversation.getCreatedAt());

        if (ConversationType.PRIVATE.equals(conversation.getType())) {
            Long peerId = conversationMemberMapper.selectUserIdsByConversation(conversation.getId()).stream()
                    .filter(id -> !id.equals(me))
                    .findFirst()
                    .orElse(null);
            if (peerId != null) {
                User peer = userMapper.selectById(peerId);
                response.setPeerUserId(peerId);
                response.setPeerNickname(peer == null ? "" : nickname(peer));
                response.setTitle(response.getPeerNickname());
            } else {
                response.setTitle("已注销用户");
            }
        } else if (ConversationType.GROUP.equals(conversation.getType())) {
            SharedMap map = sharedMapMapper.selectById(conversation.getMapId());
            response.setTitle(map != null ? map.getName() : "群聊");
        } else if (ConversationType.PLACE.equals(conversation.getType())) {
            Place place = placeMapper.selectById(conversation.getPlaceId());
            response.setTitle(place != null ? place.getName() : "地点聊天室");
        }

        response.setUnread(readUnread(me, conversation.getId()));
        response.setLastMessage(messageMapper.selectLastByConversation(conversation.getId()));
        return response;
    }

    private long readUnread(Long userId, Long conversationId) {
        String raw = stringRedisTemplate.opsForValue().get(SocialRedisKey.unread(userId, conversationId));
        if (raw == null) {
            return 0;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private String nickname(User user) {
        return user.getNickname() == null || user.getNickname().isBlank() ? user.getUsername() : user.getNickname();
    }
}
