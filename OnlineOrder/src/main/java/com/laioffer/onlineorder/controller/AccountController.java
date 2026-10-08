package com.laioffer.onlineorder.controller;


import com.laioffer.onlineorder.entity.RestaurantEntity;
import com.laioffer.onlineorder.model.MeDto;
import com.laioffer.onlineorder.repository.RestaurantRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


import java.util.List;


@RestController
public class AccountController {


    private final RestaurantRepository restaurantRepository;


    public AccountController(RestaurantRepository restaurantRepository) {
        this.restaurantRepository = restaurantRepository;
    }


    // 前端启动时和登录后调用：未登录返回 401；已登录返回角色，商家账号还带上所属餐厅
    @GetMapping("/me")
    public MeDto me(@AuthenticationPrincipal User user) {
        List<String> roles = user.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        RestaurantEntity restaurant = roles.contains("ROLE_MERCHANT")
                ? restaurantRepository.findByStaffEmail(user.getUsername())
                : null;
        return new MeDto(
                user.getUsername(),
                roles,
                restaurant == null ? null : restaurant.id(),
                restaurant == null ? null : restaurant.name());
    }
}
