package com.campus.admin.service;

import com.campus.admin.dto.AdminPlaceItem;
import com.campus.admin.dto.AdminPostItem;
import com.campus.admin.dto.AdminUserItem;
import com.campus.common.result.PageResult;
import com.campus.common.util.PageUtils;
import com.campus.place.mapper.PlaceMapper;
import com.campus.place.service.PlaceService;
import com.campus.post.mapper.PostMapper;
import com.campus.post.service.PostService;
import com.campus.user.entity.User;
import com.campus.user.mapper.UserMapper;
import com.campus.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminManageService {

    private final UserMapper userMapper;
    private final UserService userService;
    private final PlaceMapper placeMapper;
    private final PlaceService placeService;
    private final PostMapper postMapper;
    private final PostService postService;

    public PageResult<AdminUserItem> listUsers(Integer page, Integer size) {
        int p = PageUtils.normalizePage(page);
        int s = PageUtils.normalizeSize(size);
        List<AdminUserItem> list = userMapper.selectPage(PageUtils.offset(p, s), s).stream()
                .map(this::toUser)
                .toList();
        return PageResult.of(list, p, s, userMapper.countAll());
    }

    public void updateUserStatus(Long userId, int status) {
        userService.updateStatus(userId, status);
    }

    public PageResult<AdminPlaceItem> listPlaces(Integer page, Integer size) {
        int p = PageUtils.normalizePage(page);
        int s = PageUtils.normalizeSize(size);
        List<AdminPlaceItem> list = placeMapper.selectAdminPage(PageUtils.offset(p, s), s);
        return PageResult.of(list, p, s, placeMapper.countAll());
    }

    public void hidePlace(Long placeId) {
        placeService.hidePlace(placeId);
    }

    public PageResult<AdminPostItem> listPosts(Integer page, Integer size) {
        int p = PageUtils.normalizePage(page);
        int s = PageUtils.normalizeSize(size);
        List<AdminPostItem> list = postMapper.selectAdminPage(PageUtils.offset(p, s), s);
        return PageResult.of(list, p, s, postMapper.countAdmin());
    }

    public void hidePost(Long postId) {
        postService.hidePost(postId);
    }

    private AdminUserItem toUser(User user) {
        AdminUserItem item = new AdminUserItem();
        item.setId(user.getId());
        item.setUsername(user.getUsername());
        item.setNickname(user.getNickname());
        item.setAvatar(user.getAvatar());
        item.setStatus(user.getStatus());
        item.setCreatedAt(user.getCreatedAt());
        return item;
    }
}
