package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.RestaurantEntity;
import com.laioffer.onlineorder.model.MerchantOrderDto;
import com.laioffer.onlineorder.model.MerchantOrderPage;
import com.laioffer.onlineorder.model.OrderSearchCriteria;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.repository.RestaurantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;


import java.util.List;
import java.util.Set;


@Service
public class MerchantService {


    private static final int ACTIVE_LIMIT = 100;
    private static final int MAX_PAGE_SIZE = 50;
    private static final Set<String> STATUSES = Set.of("PAID", "ACCEPTED", "DONE", "CANCELLED");
    private static final Set<String> PHONE_MATCHES = Set.of("exact", "prefix", "suffix");


    private final HotItemService hotItemService;
    private final OrderRepository orderRepository;
    private final RestaurantRepository restaurantRepository;


    public MerchantService(
            HotItemService hotItemService,
            OrderRepository orderRepository,
            RestaurantRepository restaurantRepository) {
        this.hotItemService = hotItemService;
        this.orderRepository = orderRepository;
        this.restaurantRepository = restaurantRepository;
    }


    // 商家只能操作自己的餐厅：餐厅 id 从登录身份查出来，不信任前端传的 id
    public long getRestaurantId(String email) {
        RestaurantEntity restaurant = restaurantRepository.findByStaffEmail(email);
        if (restaurant == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not bound to any restaurant");
        }
        return restaurant.id();
    }


    public List<MerchantOrderDto> getActiveOrders(long restaurantId) {
        return orderRepository.findActive(restaurantId, ACTIVE_LIMIT);
    }


    public MerchantOrderPage searchOrders(long restaurantId, OrderSearchCriteria criteria, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // 多查一条，用来判断还有没有下一页
        List<MerchantOrderDto> rows = orderRepository.search(
                restaurantId, normalize(criteria), safeSize + 1, (long) (safePage - 1) * safeSize);
        boolean hasMore = rows.size() > safeSize;
        return new MerchantOrderPage(hasMore ? rows.subList(0, safeSize) : rows, hasMore);
    }


    public void accept(long restaurantId, long orderId) {
        transition(restaurantId, orderId, "PAID", "ACCEPTED");
    }


    // 条件更新保证同一个订单只会成功取消一次，所以销量也只会扣一次
    public void reject(long restaurantId, long orderId) {
        transition(restaurantId, orderId, "PAID", "CANCELLED");
        hotItemService.recordCancelled(orderId);
    }


    public void complete(long restaurantId, long orderId) {
        transition(restaurantId, orderId, "ACCEPTED", "DONE");
    }


    private void transition(long restaurantId, long orderId, String from, String to) {
        if (orderRepository.transition(restaurantId, orderId, from, to) == 1) {
            return;
        }
        // 别家店的订单也当作不存在，不暴露它是否存在
        String current = orderRepository.findStatus(restaurantId, orderId);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "order not found");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "order is " + current + ", expected " + from);
    }


    // 空字符串当作没填；手机号只保留数字（顺带杜绝了 LIKE 通配符）；非法的状态和匹配方式直接拒绝
    private static OrderSearchCriteria normalize(OrderSearchCriteria c) {
        String rawPhone = c.phone() == null ? "" : c.phone().trim();
        String phone = rawPhone.replaceAll("\\D", "");
        if (!rawPhone.isEmpty() && phone.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phone must contain digits");
        }
        String phoneMatch = c.phoneMatch() == null || c.phoneMatch().isBlank() ? "exact" : c.phoneMatch();
        if (!PHONE_MATCHES.contains(phoneMatch)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid phone_match: " + phoneMatch);
        }
        String dish = c.dish() == null ? "" : c.dish().trim();
        List<String> statuses = c.statuses() == null ? List.of() : c.statuses();
        for (String s : statuses) {
            if (!STATUSES.contains(s)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid status: " + s);
            }
        }
        return new OrderSearchCriteria(
                c.date(),
                phone.isEmpty() ? null : phone,
                phoneMatch,
                dish.isEmpty() ? null : dish,
                statuses);
    }
}
