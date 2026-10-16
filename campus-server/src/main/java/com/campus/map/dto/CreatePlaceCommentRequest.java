package com.campus.map.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "发表地点评论（地图维度）")
public class CreatePlaceCommentRequest {

    @NotBlank
    @Size(max = 1000)
    private String content;
}
