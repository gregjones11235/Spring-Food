package com.laioffer.onlineorder.model;


import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;


// 商家后台看到的一个订单：订单本身 + 下单顾客 + 菜品明细
public record MerchantOrderDto(
        Long id,
        String status,
        BigDecimal totalPrice,
        OffsetDateTime createdAt,
        String customerName,
        String customerPhone,
        List<OrderLineDto> lines
) {
}
