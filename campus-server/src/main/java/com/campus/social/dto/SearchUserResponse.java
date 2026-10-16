package com.campus.social.dto;

import lombok.Data;

@Data
public class SearchUserResponse {

    private Long userId;
    private String username;
    private String nickname;
    private String avatar;
    private boolean friend;
    private boolean requested;
}
