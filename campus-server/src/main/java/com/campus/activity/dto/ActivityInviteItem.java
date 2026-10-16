package com.campus.activity.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ActivityInviteItem {

    private Long id;
    private Long activityId;
    private Long activityCreatorId;
    private String activityTitle;
    private String activityCategory;
    private LocalDateTime activityStartTime;
    private LocalDateTime activityEndTime;
    private Long placeId;
    private String placeName;
    private Long inviterId;
    private String inviterNickname;
    private Long inviteeId;
    /** 0 待响应 1 已接受 2 已拒绝 */
    private Integer status;
    private LocalDateTime createdAt;
}
