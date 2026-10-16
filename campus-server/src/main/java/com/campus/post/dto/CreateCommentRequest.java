package com.campus.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateCommentRequest {

    private Long parentId;

    @NotBlank
    @Size(min = 1, max = 1000)
    private String content;
}
