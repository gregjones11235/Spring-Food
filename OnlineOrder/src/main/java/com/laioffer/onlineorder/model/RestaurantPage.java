package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.entity.RestaurantEntity;


import java.util.List;


// 餐厅列表的一页：page 从 1 开始，和前端分页组件一致
public record RestaurantPage(
        List<RestaurantEntity> items,
        long total,
        int page,
        int size
) {
}
