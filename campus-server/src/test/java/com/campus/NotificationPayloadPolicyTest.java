package com.campus;

import com.campus.notification.service.NotificationPayloadPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPayloadPolicyTest {

    @Test
    void keepsOnlyRoutingScalars() {
        Map<String, Object> payload = NotificationPayloadPolicy.sanitize(Map.of(
                "activityId", 12L,
                "inviteId", 34L,
                "fromUserId", 56L,
                "checkinCode", "123456",
                "scheduleTitle", "私人日程",
                "longitude", 113.38,
                "nested", Map.of("note", "备注"),
                "list", List.of(1, 2)
        ));

        assertThat(payload).containsOnlyKeys("activityId", "inviteId", "fromUserId");
        assertThat(payload).doesNotContainKeys("checkinCode", "scheduleTitle", "longitude", "nested", "list");
    }

    @Test
    void dropsOverlongStringsAndEmptyResult() {
        assertThat(NotificationPayloadPolicy.sanitize(Map.of(
                "activityId", 1L,
                "peerUserId", "x".repeat(65)
        ))).containsOnlyKeys("activityId");

        assertThat(NotificationPayloadPolicy.sanitize(Map.of("checkinCode", "123456"))).isNull();
        assertThat(NotificationPayloadPolicy.sanitize(null)).isNull();
    }
}
