package com.campus.post.dto;

import lombok.Data;

@Data
public class LikeStatusResponse {

    private boolean liked;
    private long likeCount;
}
