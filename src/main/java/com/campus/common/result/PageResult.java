package com.campus.common.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> list;
    private int page;
    private int size;
    private long total;

    public static <T> PageResult<T> of(List<T> list, int page, int size, long total) {
        return new PageResult<>(list, page, size, total);
    }
}
