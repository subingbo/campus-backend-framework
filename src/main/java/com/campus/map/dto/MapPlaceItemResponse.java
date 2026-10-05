package com.campus.map.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class MapPlaceItemResponse {

    private Long id;
    private String name;
    private String description;
    private Long categoryId;
    private String categoryName;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private String address;
    private Long creatorId;
}
