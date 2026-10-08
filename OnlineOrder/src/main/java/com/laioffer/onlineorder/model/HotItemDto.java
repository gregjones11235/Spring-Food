package com.laioffer.onlineorder.model;


import com.laioffer.onlineorder.entity.MenuItemEntity;


// 店铺页 "Most Ordered" 里的一个菜：菜品信息 + 累计销量
public record HotItemDto(
        Long id,
        String name,
        String description,
        Double price,
        String imageUrl,
        long sold
) {


    public HotItemDto(MenuItemEntity entity, long sold) {
        this(entity.id(), entity.name(), entity.description(), entity.price(), entity.imageUrl(), sold);
    }
}
