package com.campus.activity.reminder;

import com.campus.activity.constant.ActivityRedisKey;
import com.campus.activity.entity.Activity;
import com.campus.activity.mapper.ActivityMapper;
import com.campus.activity.mapper.ActivityMemberMapper;
import com.campus.activity.ws.ActivityEventNotifier;
import com.campus.notification.constant.NotificationType;
import com.campus.notification.service.NotificationService;
import com.campus.place.entity.Place;
import com.campus.place.mapper.PlaceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动开始提醒：每分钟扫描未来 30 分钟内开始的活动，
 * 在 T-15 / T-5 时间点各推一次 WS 提醒（Redis 标记防重）。
 * 单实例语义；多实例需配分布式锁（见需求文档「还差什么」）。
 */
@Slf4j
@Component
public class ActivityReminderScheduler {

    static final int LOOKAHEAD_MINUTES = 30;
    /** 想提醒的提前量；60s 扫描频率下按 within 判定，避免错过 */
    static final long[] REMINDER_OFFSETS = {15, 5};

    private final ActivityMapper activityMapper;
    private final ActivityMemberMapper activityMemberMapper;
    private final PlaceMapper placeMapper;
    private final ActivityEventNotifier activityEventNotifier;
    private final NotificationService notificationService;
    private final StringRedisTemplate stringRedisTemplate;

    public ActivityReminderScheduler(ActivityMapper activityMapper,
                                     ActivityMemberMapper activityMemberMapper,
                                     PlaceMapper placeMapper,
                                     ActivityEventNotifier activityEventNotifier,
                                     NotificationService notificationService,
                                     StringRedisTemplate stringRedisTemplate) {
        this.activityMapper = activityMapper;
        this.activityMemberMapper = activityMemberMapper;
        this.placeMapper = placeMapper;
        this.activityEventNotifier = activityEventNotifier;
        this.notificationService = notificationService;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 每分钟：找 [now, now+30min] 内开始、未取消的活动，对即将到的提前量推提醒 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void remindUpcoming() {
        LocalDateTime now = LocalDateTime.now();
        List<Activity> upcoming = activityMapper.selectStartingBetween(
                now.minusMinutes(1), now.plusMinutes(LOOKAHEAD_MINUTES));
        for (Activity activity : upcoming) {
            long minutesLeft = Math.max(0, java.time.Duration.between(now, activity.getStartTime()).toMinutes());
            for (long offset : REMINDER_OFFSETS) {
                if (minutesLeft > offset || isReminded(activity.getId(), offset)) {
                    continue;
                }
                // 先落持久通知（唯一键幂等），再标记 Redis 并推送；推送是尽力投递，收件箱是可靠兜底
                List<Long> memberIds = activityMemberMapper.selectUserIdsByActivityId(activity.getId());
                int created = notificationService.createForUsers(memberIds,
                        NotificationType.ACTIVITY_REMINDER, "活动即将开始",
                        "「" + activity.getTitle() + "」将在 " + minutesLeft + " 分钟后开始",
                        NotificationType.TARGET_ACTIVITY, activity.getId(), null,
                        "activity_reminder:" + activity.getId() + ":" + offset);
                markReminded(activity.getId(), offset);
                if (created > 0) {
                    log.info("发送活动提醒 activityId={} offset={} members={}",
                            activity.getId(), offset, created);
                    activityEventNotifier.notifyReminder(activity.getId(), activity.getTitle(),
                            placeName(activity.getPlaceId()), minutesLeft);
                }
            }
        }
    }

    private String placeName(Long placeId) {
        try {
            Place place = placeMapper.selectById(placeId);
            return place == null ? "地点已删除" : place.getName();
        } catch (Exception ex) {
            log.warn("查询活动地点失败 placeId={}：{}", placeId, ex.getMessage());
            return "";
        }
    }

    private boolean isReminded(Long activityId, long offset) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(
                ActivityRedisKey.reminded(activityId, offset)));
    }

    /** SETNX 抢标记（带 TTL）：DB 通知已落库后设置，避免重启/多份扫描重复推送 */
    private boolean markReminded(Long activityId, long offset) {
        String key = ActivityRedisKey.reminded(activityId, offset);
        Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofHours(12));
        return Boolean.TRUE.equals(first);
    }
}
