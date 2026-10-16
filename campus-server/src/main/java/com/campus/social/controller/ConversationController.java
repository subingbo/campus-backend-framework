package com.campus.social.controller;

import com.campus.common.result.Result;
import com.campus.social.dto.ChatMessageResponse;
import com.campus.social.dto.ConversationResponse;
import com.campus.social.dto.CreatePrivateConversationRequest;
import com.campus.social.service.ConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @GetMapping
    public Result<List<ConversationResponse>> listMine() {
        return Result.success(conversationService.listMine());
    }

    @PostMapping("/private")
    public Result<ConversationResponse> createPrivate(@Valid @RequestBody CreatePrivateConversationRequest request) {
        return Result.success(conversationService.getOrCreatePrivate(request.getUserId()));
    }

    @GetMapping("/maps/{mapId}")
    public Result<ConversationResponse> forMap(@PathVariable Long mapId) {
        return Result.success(conversationService.getOrCreateForMap(mapId));
    }

    @GetMapping("/places/{placeId}")
    public Result<ConversationResponse> forPlace(@PathVariable Long placeId) {
        return Result.success(conversationService.getOrCreateForPlace(placeId));
    }

    @GetMapping("/{id}/messages")
    public Result<List<ChatMessageResponse>> listMessages(@PathVariable Long id,
                                                          @RequestParam(required = false) Long beforeId,
                                                          @RequestParam(required = false) Integer limit) {
        return Result.success(conversationService.listMessages(id, beforeId, limit));
    }
}
