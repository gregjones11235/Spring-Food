package com.laioffer.onlineorder.model;


import java.util.List;


// 订单搜索的一页。不返回总数：头部商家有几万单，每次都 count 一遍代价太高，前端用"加载更多"
public record MerchantOrderPage(
        List<MerchantOrderDto> items,
        boolean hasMore
) {
}
