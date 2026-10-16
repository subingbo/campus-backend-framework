package com.campus.activity.ws;

import com.campus.activity.dto.ActivityInviteItem;
import com.campus.activity.mapper.ActivityMemberMapper;
import com.campus.ws.WsSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 活动事件 → 实时通知：活动提醒/取消/召集邀请/成员加入，
 * 推送给对应成员或邀请对象的在线会话。
 * 只依赖会话注册表与 activity mapper，保持与业务 service 的依赖单向（参照 MapEventNotifier 的边界）。
 */
@Component
@RequiredArgsConstructor
public class ActivityEventNotifier {

    public static final String TYPE_REMINDER = "ACTIVITY_REMINDER";
    public static final String TYPE_CANCELLED = "ACTIVITY_CANCELLED";
    public static final String TYPE_INVITED = "ACTIVITY_INVITED";
    public static final String TYPE_MEMBER_JOINED = "ACTIVITY_MEMBER_JOINED";

    private final WsSessionRegistry wsSessionRegistry;
    private final ActivityMemberMapper activityMemberMapper;

    /** 活动开始前提醒：推给活动所有在线成员 */
    public void notifyReminder(Long activityId, String title, String placeName, long minutesLeft) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", TYPE_REMINDER);
        frame.put("activityId", activityId);
        frame.put("title", nullSafe(title));
        frame.put("placeName", nullSafe(placeName));
        frame.put("minutesLeft", minutesLeft);
        frame.put("summary", "活动「" + nullSafe(title) + "」将在 " + minutesLeft + " 分钟后开始");
        wsSessionRegistry.sendToUsers(activityMemberMapper.selectUserIdsByActivityId(activityId), frame);
    }

    /** 活动被创建者取消：推给所有成员（排除创建者本人） */
    public void notifyCancelled(Long activityId, String title, Long exceptCreatorId) {
        List<Long> recipients = activityMemberMapper.selectUserIdsByActivityId(activityId).stream()
                .filter(id -> !id.equals(exceptCreatorId))
                .distinct()
                .toList();
        if (recipients.isEmpty()) {
            return;
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", TYPE_CANCELLED);
        frame.put("activityId", activityId);
        frame.put("title", nullSafe(title));
        frame.put("summary", "活动「" + nullSafe(title) + "」已被创建者取消");
        wsSessionRegistry.sendToUsers(recipients, frame);
    }

    /** 比赛召集邀请：推给被邀请人 */
    public void notifyInvited(ActivityInviteItem invite) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", TYPE_INVITED);
        frame.put("inviteId", invite.getId());
        frame.put("activityId", invite.getActivityId());
        frame.put("title", nullSafe(invite.getActivityTitle()));
        frame.put("placeName", nullSafe(invite.getPlaceName()));
        frame.put("startTime", invite.getActivityStartTime() == null ? "" : invite.getActivityStartTime().toString());
        frame.put("summary", nullSafe(invite.getInviterNickname()) + " 邀请你参加召集「" + nullSafe(invite.getActivityTitle()) + "」");
        wsSessionRegistry.sendToUser(invite.getInviteeId(), frame);
    }

    /** 被邀请人接受：推给活动成员（创建者必在其中），知道召集已有人确认 */
    public void notifyInviteAccepted(Long activityId, String title, Long acceptedByUserId) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", TYPE_MEMBER_JOINED);
        frame.put("activityId", activityId);
        frame.put("title", nullSafe(title));
        frame.put("userId", acceptedByUserId);
        frame.put("summary", "有成员接受了召集「" + nullSafe(title) + "」的邀请");
        wsSessionRegistry.sendToUsers(activityMemberMapper.selectUserIdsByActivityId(activityId), frame);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
