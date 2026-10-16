package com.campus.place.dto;

import com.campus.post.dto.PostListItemResponse;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class PlaceDetailResponse {

    private Long id;
    private String name;
    private String description;
    private Long categoryId;
    private String categoryName;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String address;
    private Long creatorId;
    private String creatorNickname;
    private long postCount;
    private long memberCount;
    private long likeCount;
    private LocalDateTime createdAt;
    private List<PostListItemResponse> latestPosts = new ArrayList<>();
}
