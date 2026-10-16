package com.campus.place.entity;

import lombok.Data;

@Data
public class PlaceCategory {

    private Long id;
    private String name;
    private String icon;
    private Integer sort;
    private Integer status;
}
