package com.campus.ai.planner;

import java.time.Duration;
import java.time.LocalDateTime;

/** 一个可安排的连续空闲窗口 [start,end) */
public record FreeWindow(LocalDateTime start, LocalDateTime end) {

    public long durationMinutes() {
        return Duration.between(start, end).toMinutes();
    }

    @Override
    public String toString() {
        return start + " ~ " + end + " (" + durationMinutes() + " 分钟)";
    }
}
