package com.laioffer.onlineorder.model;


import java.math.BigDecimal;


public record OrderLineDto(
        String name,
        int quantity,
        BigDecimal price
) {
}
