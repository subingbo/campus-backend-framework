package com.campus.post.dto;

import com.campus.activity.dto.ActivityResponse;
import com.campus.place.dto.PlaceDetailResponse;
import com.campus.common.result.PageResult;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PlaceFeedResponse {

    private PlaceDetailResponse place;
    private PageResult<PostListItemResponse> posts;
    private List<MemberPreview> members = new ArrayList<>();
    private List<ActivityResponse> activities = new ArrayList<>();

    @Data
    public static class MemberPreview {
        private Long userId;
        private String nickname;
        private String avatar;
    }
}
