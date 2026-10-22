package com.campus.ai.controller;

import com.campus.ai.dto.GroupPlanHandoff;
import com.campus.ai.dto.GroupPlanPublicView;
import com.campus.ai.service.GroupPlanQueryService;
import com.campus.common.exception.BusinessException;
import com.campus.common.result.Result;
import com.campus.common.result.ResultCode;
import com.campus.security.user.SecurityUtils;
import com.campus.social.constant.ConversationType;
import com.campus.social.entity.Conversation;
import com.campus.social.mapper.ConversationMapper;
import com.campus.social.service.ChatMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 群规划卡公开摘要与发起人深链。
 * 成员只能看白名单摘要；完整 AI 会话/消息仍走 owner-only 的 /api/assistant/chats/{id}。
 */
@Tag(name = "群规划卡")
@RestController
@RequestMapping("/api/conversations/{conversationId}/agent-plans")
@RequiredArgsConstructor
public class GroupPlanController {

    private static final int LIST_LIMIT = 100;

    private final ConversationMapper conversationMapper;
    private final ChatMessageService chatMessageService;
    private final GroupPlanQueryService planQueryService;

    @Operation(summary = "群规划卡摘要列表（仅当前群成员）")
    @GetMapping
    public Result<List<GroupPlanPublicView>> list(@PathVariable Long conversationId) {
        Long userId = SecurityUtils.getUserId();
        requireGroupMember(conversationId, userId);
        return Result.success(planQueryService.list(conversationId, LIST_LIMIT));
    }

    @Operation(summary = "单个群规划卡摘要（仅当前群成员）")
    @GetMapping("/{planId}")
    public Result<GroupPlanPublicView> get(@PathVariable Long conversationId,
                                           @PathVariable Long planId) {
        Long userId = SecurityUtils.getUserId();
        requireGroupMember(conversationId, userId);
        GroupPlanPublicView view = planQueryService.get(conversationId, planId);
        if (view == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "规划不存在");
        }
        return Result.success(view);
    }

    @Operation(summary = "发起人继续规划（owner-only，返回服务端持久化的群语境）")
    @GetMapping("/{planId}/handoff")
    public Result<GroupPlanHandoff> handoff(@PathVariable Long conversationId,
                                            @PathVariable Long planId) {
        Long userId = SecurityUtils.getUserId();
        requireGroupMember(conversationId, userId);
        GroupPlanHandoff handoff = planQueryService.handoff(conversationId, planId, userId);
        if (handoff == null) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(),
                    "仅发起人可继续规划，或该规划缺少群语境");
        }
        return Result.success(handoff);
    }

    private void requireGroupMember(Long conversationId, Long userId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !ConversationType.GROUP.equals(conversation.getType())) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "群会话不存在");
        }
        // GROUP：当前 map_member 校验；被移出群后即使知道 planId 也会 403
        chatMessageService.assertCanAccess(conversation, userId);
    }
}
