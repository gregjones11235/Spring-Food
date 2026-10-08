package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.model.HotItemDto;
import com.laioffer.onlineorder.service.HotItemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.util.List;
import java.util.Map;


@RestController
public class HotItemController {


    private final HotItemService hotItemService;


    public HotItemController(HotItemService hotItemService) {
        this.hotItemService = hotItemService;
    }


    // 店铺页的 "Most Ordered"：/restaurant/1/hot-items?n=5
    @GetMapping("/restaurant/{restaurantId}/hot-items")
    public List<HotItemDto> getHotItems(@PathVariable long restaurantId, @RequestParam(defaultValue = "5") int n) {
        return hotItemService.top(restaurantId, n);
    }


    // 手动校准热销榜（AppConfig 里 /ops/** 只允许本机调用）：curl.exe -X POST localhost:8080/ops/hot-items/rebuild
    @PostMapping("/ops/hot-items/rebuild")
    public Map<String, Object> rebuild() {
        return hotItemService.rebuild();
    }
}
