package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.model.MerchantOrderDto;
import com.laioffer.onlineorder.model.MerchantOrderPage;
import com.laioffer.onlineorder.model.OrderSearchCriteria;
import com.laioffer.onlineorder.service.MerchantService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import java.time.LocalDate;
import java.util.List;


// 商家后台。AppConfig 里 /merchant/** 要求 ROLE_MERCHANT；路径里不带餐厅 id，一律按登录身份取，防止越权看别家店
@RestController
@RequestMapping("/merchant")
public class MerchantController {


    private final MerchantService merchantService;


    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }


    @GetMapping("/orders/active")
    public List<MerchantOrderDto> getActiveOrders(@AuthenticationPrincipal User user) {
        return merchantService.getActiveOrders(merchantService.getRestaurantId(user.getUsername()));
    }


    // 例：/merchant/orders/search?date=2026-09-01&phone=415&phone_match=prefix&dish=whopper&status=DONE&status=CANCELLED&page=1
    @GetMapping("/orders/search")
    public MerchantOrderPage searchOrders(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String phone,
            @RequestParam(name = "phone_match", required = false) String phoneMatch,
            @RequestParam(required = false) String dish,
            @RequestParam(name = "status", required = false) List<String> statuses,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        OrderSearchCriteria criteria = new OrderSearchCriteria(date, phone, phoneMatch, dish, statuses);
        return merchantService.searchOrders(merchantService.getRestaurantId(user.getUsername()), criteria, page, size);
    }


    @PostMapping("/orders/{orderId}/accept")
    public void accept(@AuthenticationPrincipal User user, @PathVariable long orderId) {
        merchantService.accept(merchantService.getRestaurantId(user.getUsername()), orderId);
    }


    @PostMapping("/orders/{orderId}/reject")
    public void reject(@AuthenticationPrincipal User user, @PathVariable long orderId) {
        merchantService.reject(merchantService.getRestaurantId(user.getUsername()), orderId);
    }


    @PostMapping("/orders/{orderId}/complete")
    public void complete(@AuthenticationPrincipal User user, @PathVariable long orderId) {
        merchantService.complete(merchantService.getRestaurantId(user.getUsername()), orderId);
    }
}
