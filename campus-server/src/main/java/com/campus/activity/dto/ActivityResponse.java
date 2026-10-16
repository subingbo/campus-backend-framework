package com.campus.activity.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ActivityResponse {

    private Long id;
    private Long placeId;
    /** 关联地点名（列表/日程展示用） */
    private String placeName;
    private Long creatorId;
    private String creatorNickname;
    private String title;
    private String description;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer maxMembers;
    /** 活动类型：MEETING 会议 COMPETITION 比赛 OTHER 其他 */
    private String category;
    /** 数字签到码：只有创建者视角能拿到，成员/旁观者恒为 null */
    private String checkinCode;
    /** 签到窗口截止时间 */
    private LocalDateTime checkinOpenUntil;
    /** 当前视角用户是否已签到 */
    private boolean checkedIn;
    private long joinedCount;
    private boolean joined;
    private String phase;

    /** 多站行程（单站活动为空列表） */
    private List<ActivityStopItem> stops = List.of();

    @JsonIgnore
    private Integer status;
}
