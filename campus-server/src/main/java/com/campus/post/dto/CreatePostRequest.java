package com.campus.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreatePostRequest {

    @NotNull
    private Long placeId;

    @NotBlank
    @Size(min = 1, max = 2000)
    private String content;
}
