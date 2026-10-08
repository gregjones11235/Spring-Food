package com.laioffer.onlineorder.model;


import java.util.List;


// 当前登录用户：前端据此决定进入顾客页面还是商家后台。restaurantId / restaurantName 只有商家账号才有
public record MeDto(
        String email,
        List<String> roles,
        Long restaurantId,
        String restaurantName
) {
}
