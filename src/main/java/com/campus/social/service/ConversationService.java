package com.campus.social.service;

import com.campus.social.dto.ChatMessageResponse;
import com.campus.social.dto.ConversationResponse;

import java.util.List;

public interface ConversationService {

    List<ConversationResponse> listMine();

    ConversationResponse getOrCreatePrivate(Long targetUserId);

    ConversationResponse getOrCreateForMap(Long mapId);

    ConversationResponse getOrCreateForPlace(Long placeId);

    List<ChatMessageResponse> listMessages(Long conversationId, Long beforeId, Integer limit);
}
