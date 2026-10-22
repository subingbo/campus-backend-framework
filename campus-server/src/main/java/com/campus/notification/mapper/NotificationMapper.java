package com.campus.notification.mapper;

import com.campus.notification.entity.Notification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface NotificationMapper {

    int insert(Notification notification);

    Notification selectById(@Param("id") Long id);

    Notification selectByDedupe(@Param("userId") Long userId,
                                @Param("dedupeKey") String dedupeKey);

    List<Notification> selectByUser(@Param("userId") Long userId,
                                    @Param("unreadOnly") boolean unreadOnly,
                                    @Param("offset") int offset,
                                    @Param("size") int size);

    long countByUser(@Param("userId") Long userId,
                     @Param("unreadOnly") boolean unreadOnly);

    long countUnread(@Param("userId") Long userId);

    int markRead(@Param("id") Long id,
                 @Param("userId") Long userId,
                 @Param("time") LocalDateTime time);

    int markAllRead(@Param("userId") Long userId,
                    @Param("time") LocalDateTime time);
}
