package com.laioffer.onlineorder.redislab;


public record LabMenuItem(
        Long id,
        Long restaurantId,
        String name,
        Double price
) {
}
