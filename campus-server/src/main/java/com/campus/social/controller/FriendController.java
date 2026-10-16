package com.campus.social.controller;

import com.campus.common.result.Result;
import com.campus.social.dto.FriendRequestListResponse;
import com.campus.social.dto.FriendRequestResponse;
import com.campus.social.dto.FriendUserResponse;
import com.campus.social.dto.SearchUserResponse;
import com.campus.social.dto.SendFriendRequestRequest;
import com.campus.social.service.FriendService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;

    @GetMapping
    public Result<List<FriendUserResponse>> listFriends() {
        return Result.success(friendService.listFriends());
    }

    @GetMapping("/requests")
    public Result<FriendRequestListResponse> listRequests() {
        return Result.success(friendService.listRequests());
    }

    @GetMapping("/search")
    public Result<List<SearchUserResponse>> search(@RequestParam String keyword) {
        return Result.success(friendService.searchUsers(keyword));
    }

    @PostMapping("/requests")
    public Result<FriendRequestResponse> sendRequest(@Valid @RequestBody SendFriendRequestRequest request) {
        return Result.success(friendService.sendRequest(request));
    }

    @PostMapping("/requests/{id}/accept")
    public Result<Void> accept(@PathVariable Long id) {
        friendService.accept(id);
        return Result.success();
    }

    @PostMapping("/requests/{id}/reject")
    public Result<Void> reject(@PathVariable Long id) {
        friendService.reject(id);
        return Result.success();
    }

    @DeleteMapping("/{userId}")
    public Result<Void> removeFriend(@PathVariable Long userId) {
        friendService.removeFriend(userId);
        return Result.success();
    }
}
