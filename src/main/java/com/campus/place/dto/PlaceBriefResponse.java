package com.campus.place.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class PlaceBriefResponse {

    private Long id;
    private String name;
    private Long categoryId;
    private String categoryName;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Double distanceMeters;
}
