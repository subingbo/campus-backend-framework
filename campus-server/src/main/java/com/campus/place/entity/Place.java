package com.campus.place.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class Place {

    private Long id;
    private String name;
    private String description;
    private Long categoryId;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String address;
    private Long creatorId;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
