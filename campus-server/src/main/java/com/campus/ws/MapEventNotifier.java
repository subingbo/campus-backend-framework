package com.campus.ws;

import com.campus.map.dto.MapMemberResponse;
import com.campus.map.mapper.MapMemberMapper;
import com.campus.user.entity.User;
import com.campus.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图事件 → 实时通知：地点被添加/分享/移除/评论/修改时，
 * 推送给该地图的在线成员（操作者本人除外）。
 * 只依赖会话注册表与 mapper，避免与业务 service 相互依赖。
 */
@Component
@RequiredArgsConstructor
public class MapEventNotifier {

    private final WsSessionRegistry wsSessionRegistry;
    private final MapMemberMapper mapMemberMapper;
    private final UserMapper userMapper;

    public void notifyPlaceChanged(Long mapId, Long placeId, String placeName, String summary, Long actorId) {
        List<Long> recipients = mapMemberMapper.selectByMapId(mapId).stream()
                .map(MapMemberResponse::getUserId)
                .filter(id -> !id.equals(actorId))
                .distinct()
                .toList();
        if (recipients.isEmpty()) {
            return;
        }
        User actor = userMapper.selectById(actorId);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "PLACE_CHANGED");
        frame.put("mapId", mapId);
        frame.put("placeId", placeId);
        frame.put("placeName", placeName == null ? "" : placeName);
        frame.put("summary", summary);
        frame.put("actorNickname", actor == null ? "" : displayNickname(actor));
        wsSessionRegistry.sendToUsers(recipients, frame);
    }

    private String displayNickname(User user) {
        if (user == null) {
            return "";
        }
        return user.getNickname() == null || user.getNickname().isBlank() ? user.getUsername() : user.getNickname();
    }
}
