package com.campus.activity.service;

import com.campus.activity.dto.ActivityInviteItem;
import com.campus.activity.dto.ActivityMemberItem;
import com.campus.activity.dto.ActivityResponse;
import com.campus.activity.dto.CreateActivityRequest;
import com.campus.activity.dto.UpdateActivityRequest;

import java.util.List;

public interface ActivityService {

    ActivityResponse create(CreateActivityRequest request);

    ActivityResponse get(Long id);

    List<ActivityResponse> listByPlace(Long placeId);

    List<ActivityResponse> upcomingByPlace(Long placeId, int limit);

    ActivityResponse join(Long id);

    ActivityResponse leave(Long id);

    /** 编辑活动：仅创建者、未开始才可改；未提供的字段保持不变 */
    ActivityResponse update(Long id, UpdateActivityRequest request);

    /** 取消活动：仅创建者；通知所有成员 */
    ActivityResponse cancel(Long id);

    /** 成员与签到记录列表（创建者与已报名成员可见） */
    List<ActivityMemberItem> members(Long id);

    /** 输码签到 */
    ActivityResponse checkin(Long id, String code);

    /** 我的日程：role=created 只看我创建的；joined 只看我报名的；all 两者并集 */
    List<ActivityResponse> mine(String role);

    /** 创建者向指定用户发出召集邀请（比赛为主） */
    ActivityInviteItem invite(Long activityId, Long inviteeId);

    /** 被邀请人接受/拒绝 */
    ActivityInviteItem respondInvite(Long inviteId, boolean accept);

    /** 我收到的待响应邀请 */
    List<ActivityInviteItem> pendingInvites();
}
