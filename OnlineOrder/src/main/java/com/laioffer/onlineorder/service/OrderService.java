package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.OrderItemEntity;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.OrderItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;


@Service
public class OrderService {


    private final CartRepository cartRepository;
    private final CartService cartService;
    private final HotItemService hotItemService;
    private final MenuItemRepository menuItemRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;


    public OrderService(
            CartRepository cartRepository,
            CartService cartService,
            HotItemService hotItemService,
            MenuItemRepository menuItemRepository,
            OrderItemRepository orderItemRepository,
            OrderRepository orderRepository) {
        this.cartRepository = cartRepository;
        this.cartService = cartService;
        this.hotItemService = hotItemService;
        this.menuItemRepository = menuItemRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderRepository = orderRepository;
    }


    /**
     * 结账：把购物车变成正式订单（status 默认 PAID，出现在商家的待接单列表里），再清空购物车，全部在一个事务里。
     * 购物车里有多家餐厅的菜时，按餐厅拆成多个订单，每家店只看到自己的那一单。
     * 价格用加入购物车时记下的价格，和用户在购物车里看到的一致
     */
    @Transactional
    public List<Long> checkout(long customerId) {
        CartEntity cart = cartRepository.getByCustomerId(customerId);
        List<OrderItemEntity> cartItems = orderItemRepository.getAllByCartId(cart.id());
        if (cartItems.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cart is empty");
        }
        Map<Long, MenuItemEntity> menuItems = menuItemRepository
                .findAllById(cartItems.stream().map(OrderItemEntity::menuItemId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(MenuItemEntity::id, Function.identity()));

        Map<Long, List<OrderItemEntity>> byRestaurant = new LinkedHashMap<>();
        for (OrderItemEntity item : cartItems) {
            long restaurantId = menuItems.get(item.menuItemId()).restaurantId();
            byRestaurant.computeIfAbsent(restaurantId, k -> new ArrayList<>()).add(item);
        }

        List<Long> orderIds = new ArrayList<>();
        for (Map.Entry<Long, List<OrderItemEntity>> entry : byRestaurant.entrySet()) {
            BigDecimal total = BigDecimal.ZERO;
            Map<Long, Integer> quantities = new LinkedHashMap<>();
            for (OrderItemEntity item : entry.getValue()) {
                total = total.add(BigDecimal.valueOf(item.price()).multiply(BigDecimal.valueOf(item.quantity())));
                quantities.merge(item.menuItemId(), item.quantity(), Integer::sum);
            }
            long orderId = orderRepository.createOrder(customerId, entry.getKey(), total);
            for (OrderItemEntity item : entry.getValue()) {
                orderRepository.addOrderLine(orderId, item.menuItemId(), BigDecimal.valueOf(item.price()), item.quantity());
            }
            // 事务提交后才写 Redis（见 HotItemService.recordSold）
            hotItemService.recordSold(entry.getKey(), quantities);
            orderIds.add(orderId);
        }
        cartService.clearCart(customerId);
        return orderIds;
    }
}
