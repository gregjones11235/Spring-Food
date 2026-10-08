package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.RestaurantEntity;
import com.laioffer.onlineorder.model.MenuItemDto;
import com.laioffer.onlineorder.model.RestaurantDto;
import com.laioffer.onlineorder.model.RestaurantPage;
import com.laioffer.onlineorder.repository.LikePattern;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.RestaurantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Service
public class RestaurantService {


    private static final int MAX_PAGE_SIZE = 50;


    private final MenuItemRepository menuItemRepository;
    private final RestaurantRepository restaurantRepository;
    private final int hotRestaurants;


    public RestaurantService(
            RestaurantRepository restaurantRepository,
            MenuItemRepository menuItemRepository,
            @Value("${app.home.hot-restaurants}") int hotRestaurants) {
        this.restaurantRepository = restaurantRepository;
        this.menuItemRepository = menuItemRepository;
        this.hotRestaurants = hotRestaurants;
    }

    // 餐厅有几千家、菜品十几万个，不能再 findAll 全量返回：只取热门的前 N 家及其菜品，结果进两级缓存
    @Cacheable("restaurants")
    public List<RestaurantDto> getRestaurants() {
        List<RestaurantEntity> restaurantEntities = restaurantRepository.findHot(hotRestaurants);
        List<MenuItemEntity> menuItemEntities = menuItemRepository.getByRestaurantIdIn(
                restaurantEntities.stream().map(RestaurantEntity::id).toList());
        Map<Long, List<MenuItemDto>> groupedMenuItems = new HashMap<>();
        for (MenuItemEntity menuItemEntity : menuItemEntities) {
            List<MenuItemDto> group = groupedMenuItems.computeIfAbsent(menuItemEntity.restaurantId(), k -> new ArrayList<>());
            MenuItemDto menuItemDto = new MenuItemDto(menuItemEntity);
            group.add(menuItemDto);
        }
        List<RestaurantDto> results = new ArrayList<>();
        for (RestaurantEntity restaurantEntity : restaurantEntities) {
            RestaurantDto restaurantDto = new RestaurantDto(restaurantEntity, groupedMenuItems.get(restaurantEntity.id()));
            results.add(restaurantDto);
        }
        return results;
    }


    public RestaurantPage searchRestaurants(String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        String pattern = LikePattern.contains(keyword == null ? "" : keyword.trim());
        List<RestaurantEntity> items = restaurantRepository.search(pattern, safeSize, (long) (safePage - 1) * safeSize);
        long total = restaurantRepository.countSearch(pattern);
        return new RestaurantPage(items, total, safePage, safeSize);
    }
}
