package com.laioffer.onlineorder.model;


import java.time.LocalDate;
import java.util.List;


/**
 * 商家订单搜索条件，除 restaurantId 外都可以为空（为空表示不按这一项筛选）。
 * date 按餐厅所在的太平洋时间算；phoneMatch 是 exact / prefix / suffix；dish 按菜名模糊匹配
 */
public record OrderSearchCriteria(
        LocalDate date,
        String phone,
        String phoneMatch,
        String dish,
        List<String> statuses
) {
}
