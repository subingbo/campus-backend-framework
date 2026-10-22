package com.campus.social.service;

import com.campus.common.exception.BusinessException;
import com.campus.common.result.ResultCode;
import com.campus.common.tx.AfterCommit;
import com.campus.notification.constant.NotificationType;
import com.campus.notification.service.NotificationService;
import com.campus.social.dto.FriendRequestListResponse;
import com.campus.social.dto.FriendRequestResponse;
import com.campus.social.dto.FriendUserResponse;
import com.campus.social.dto.SearchUserResponse;
import com.campus.social.dto.SendFriendRequestRequest;
import com.campus.social.entity.Friend;
import com.campus.social.entity.FriendRequest;
import com.campus.social.mapper.FriendMapper;
import com.campus.social.mapper.FriendRequestMapper;
import com.campus.user.entity.User;
import com.campus.user.mapper.UserMapper;
import com.campus.security.user.SecurityUtils;
import com.campus.ws.WsSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {

    private final FriendMapper friendMapper;
    private final FriendRequestMapper friendRequestMapper;
    private final UserMapper userMapper;
    private final WsSessionRegistry wsSessionRegistry;
    private final NotificationService notificationService;

    @Override
    public List<SearchUserResponse> searchUsers(String keyword) {
        String kw = keyword == null ? "" : keyword.trim().replace("%", "").replace("_", "");
        if (!StringUtils.hasText(kw)) {
            return List.of();
        }
        return friendRequestMapper.searchUsers(SecurityUtils.getUserId(), "%" + kw + "%");
    }

    @Override
    @Transactional
    public FriendRequestResponse sendRequest(SendFriendRequestRequest request) {
        Long me = SecurityUtils.getUserId();
        if (request.getUserId().equals(me)) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "不能添加自己为好友");
        }
        User target = userMapper.selectById(request.getUserId());
        if (target == null || target.getStatus() == null || target.getStatus() != 1) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "用户不存在");
        }
        if (friendMapper.existsBetween(me, target.getId()) > 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "你们已经是好友");
        }
        if (friendRequestMapper.selectPendingBetween(me, target.getId()) != null) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "已存在待处理的好友申请");
        }

        User sender = userMapper.selectById(me);
        FriendRequest entity = new FriendRequest();
        entity.setFromUserId(me);
        entity.setToUserId(target.getId());
        entity.setMessage(StringUtils.hasText(request.getMessage()) ? request.getMessage().trim() : null);
        friendRequestMapper.insert(entity);
        notificationService.create(target.getId(), NotificationType.FRIEND_REQUEST, "新的好友申请",
                displayNickname(sender) + " 请求加你为好友",
                NotificationType.TARGET_FRIEND_REQUEST, entity.getId(),
                Map.of("fromUserId", me), "friend_request:" + entity.getId());

        AfterCommit.run(() -> pushTo(target.getId(), "FRIEND_REQUEST", Map.ofEntries(
                Map.entry("requestId", entity.getId()),
                Map.entry("fromUserId", me),
                Map.entry("fromNickname", displayNickname(sender)),
                Map.entry("message", entity.getMessage() == null ? "" : entity.getMessage()))));

        return toResponse(entity, sender, target);
    }

    @Override
    public FriendRequestListResponse listRequests() {
        Long me = SecurityUtils.getUserId();
        FriendRequestListResponse response = new FriendRequestListResponse();
        response.setReceived(friendRequestMapper.selectReceived(me));
        response.setSent(friendRequestMapper.selectSent(me));
        return response;
    }

    @Override
    @Transactional
    public void accept(Long requestId) {
        FriendRequest entity = requirePending(requestId);
        Long me = SecurityUtils.getUserId();
        friendRequestMapper.updateStatus(entity.getId(), 1);
        Friend mine = new Friend();
        mine.setUserId(me);
        mine.setFriendId(entity.getFromUserId());
        friendMapper.insert(mine);
        Friend reverse = new Friend();
        reverse.setUserId(entity.getFromUserId());
        reverse.setFriendId(me);
        friendMapper.insert(reverse);

        User meUser = userMapper.selectById(me);
        notificationService.create(entity.getFromUserId(), NotificationType.FRIEND_ACCEPTED,
                "好友申请通过", displayNickname(meUser) + " 已同意你的好友申请",
                NotificationType.TARGET_FRIEND, me, Map.of("userId", me),
                "friend_accepted:" + entity.getId());
        AfterCommit.run(() -> pushTo(entity.getFromUserId(), "FRIEND_ACCEPTED", Map.ofEntries(
                Map.entry("userId", me),
                Map.entry("nickname", displayNickname(meUser)))));
    }

    @Override
    @Transactional
    public void reject(Long requestId) {
        FriendRequest entity = requirePending(requestId);
        friendRequestMapper.updateStatus(entity.getId(), 2);
    }

    @Override
    public List<FriendUserResponse> listFriends() {
        Long me = SecurityUtils.getUserId();
        List<FriendUserResponse> friends = friendMapper.selectFriends(me);
        for (FriendUserResponse friend : friends) {
            friend.setOnline(wsSessionRegistry.isOnline(friend.getUserId()));
        }
        return friends;
    }

    @Override
    @Transactional
    public void removeFriend(Long friendUserId) {
        Long me = SecurityUtils.getUserId();
        if (friendMapper.existsBetween(me, friendUserId) == 0) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "好友不存在");
        }
        friendMapper.deleteBetween(me, friendUserId);
    }

    private FriendRequest requirePending(Long requestId) {
        Long me = SecurityUtils.getUserId();
        FriendRequest entity = friendRequestMapper.selectById(requestId);
        if (entity == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "好友申请不存在");
        }
        if (!entity.getToUserId().equals(me)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        if (entity.getStatus() != 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST.getCode(), "该申请已处理");
        }
        return entity;
    }

    private FriendRequestResponse toResponse(FriendRequest entity, User from, User to) {
        FriendRequestResponse response = new FriendRequestResponse();
        response.setId(entity.getId());
        response.setFromUserId(entity.getFromUserId());
        response.setFromUsername(from.getUsername());
        response.setFromNickname(displayNickname(from));
        response.setToUserId(entity.getToUserId());
        response.setToUsername(to.getUsername());
        response.setToNickname(displayNickname(to));
        response.setMessage(entity.getMessage());
        response.setStatus(entity.getStatus());
        response.setCreatedAt(entity.getCreatedAt());
        return response;
    }

    private void pushTo(Long userId, String type, Map<String, Object> payload) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.putAll(payload);
        wsSessionRegistry.sendToUser(userId, frame);
    }

    private String displayNickname(User user) {
        if (user == null) {
            return "";
        }
        return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
    }
}
