package com.laioffer.onlineorder.service;


import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.model.HotItemDto;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.OrderRepository;
import com.laioffer.onlineorder.repository.OrderRepository.DishSales;
import com.laioffer.onlineorder.repository.RestaurantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * 店铺热销榜：每家店一个 ZSet，member 是菜品 id，score 是累计销量（不含已取消的订单）。
 *
 * 数据库是准确的源数据，ZSet 只是派生出来的实时视图：结账时 ZINCRBY 加、拒单时 ZINCRBY 扣，
 * Redis 出错只记日志、不影响结账和拒单，漏掉的部分由 rebuild()（手动校准）按数据库重算。
 */
@Service
public class HotItemService {


    private static final Logger logger = LoggerFactory.getLogger(HotItemService.class);
    private static final int MAX_TOP = 20;


    private final StringRedisTemplate redis;
    private final OrderRepository orderRepository;
    private final MenuItemRepository menuItemRepository;
    private final RestaurantRepository restaurantRepository;


    public HotItemService(
            StringRedisTemplate redis,
            OrderRepository orderRepository,
            MenuItemRepository menuItemRepository,
            RestaurantRepository restaurantRepository) {
        this.redis = redis;
        this.orderRepository = orderRepository;
        this.menuItemRepository = menuItemRepository;
        this.restaurantRepository = restaurantRepository;
    }


    // {} 是集群的 hash tag：只按花括号里的部分算 slot，校准用的临时 key 和正式 key 才能落在同一个 slot 里 RENAME
    static String key(long restaurantId) {
        return "hot:restaurant:{" + restaurantId + "}";
    }


    // 结账时调用。在事务里时等提交成功后再加，事务回滚就不会多算
    public void recordSold(long restaurantId, Map<Long, Integer> quantities) {
        List<DishSales> sales = new ArrayList<>();
        quantities.forEach((menuItemId, qty) -> sales.add(new DishSales(restaurantId, menuItemId, qty)));
        afterCommit(() -> increment(sales, 1));
    }


    // 订单被取消（商家拒单）时调用：把这一单的销量扣回来
    public void recordCancelled(long orderId) {
        List<DishSales> sales = orderRepository.findDishSales(orderId);
        afterCommit(() -> increment(sales, -1));
    }


    // ZREVRANGE key 0 n-1 WITHSCORES，再按 id 批量查菜品信息。Redis 不可用时返回空列表，前端就不显示这一栏
    public List<HotItemDto> top(long restaurantId, int n) {
        int safeN = Math.min(Math.max(n, 1), MAX_TOP);
        Set<TypedTuple<String>> tuples;
        try {
            tuples = redis.opsForZSet().reverseRangeWithScores(key(restaurantId), 0, safeN - 1);
        } catch (RuntimeException e) {
            logger.warn("读取热销榜失败，restaurant={}。原因: {}", restaurantId, e.getMessage());
            return List.of();
        }
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        List<Long> ids = tuples.stream().map(t -> Long.valueOf(t.getValue())).toList();
        Map<Long, MenuItemEntity> menuItems = menuItemRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(MenuItemEntity::id, Function.identity()));
        List<HotItemDto> result = new ArrayList<>();
        for (TypedTuple<String> tuple : tuples) {
            MenuItemEntity menuItem = menuItems.get(Long.valueOf(tuple.getValue()));
            // 菜品已被删除时跳过，下次校准会把它从 ZSet 里清掉
            if (menuItem != null && menuItem.restaurantId() == restaurantId) {
                result.add(new HotItemDto(menuItem, Math.round(tuple.getScore())));
            }
        }
        return result;
    }


    /**
     * 手动校准：用数据库里所有未取消订单重算每家店的 ZSet。
     * 每家店先写临时 key，再 RENAME 覆盖正式 key，读的人不会看到半成品或空榜。
     * 从查数据库到 RENAME 之间新下的单会被覆盖掉（漏算），所以放在低峰期跑，下次校准会补回来。
     */
    public Map<String, Object> rebuild() {
        long start = System.currentTimeMillis();
        Map<Long, Set<TypedTuple<String>>> byRestaurant = new HashMap<>();
        List<DishSales> allSales = orderRepository.findAllDishSales();
        for (DishSales s : allSales) {
            byRestaurant.computeIfAbsent(s.restaurantId(), k -> new HashSet<>())
                    .add(new DefaultTypedTuple<>(String.valueOf(s.menuItemId()), (double) s.quantity()));
        }
        long queryMillis = System.currentTimeMillis() - start;

        int rebuilt = 0;
        int cleared = 0;
        for (long restaurantId : restaurantRepository.findAllIds()) {
            String key = key(restaurantId);
            Set<TypedTuple<String>> tuples = byRestaurant.get(restaurantId);
            if (tuples == null) {
                // 没有任何有效销量的店，清掉可能残留的旧榜
                redis.delete(key);
                cleared++;
                continue;
            }
            String tmpKey = key + ":rebuild";
            redis.delete(tmpKey);
            redis.opsForZSet().add(tmpKey, tuples);
            redis.rename(tmpKey, key);
            rebuilt++;
        }
        return Map.of(
                "restaurants_rebuilt", rebuilt,
                "restaurants_cleared", cleared,
                "dishes", allSales.size(),
                "query_ms", queryMillis,
                "total_ms", System.currentTimeMillis() - start);
    }


    // sign = 1 加销量，-1 扣销量。扣到 0 及以下的菜直接移出榜单（比如加的时候 Redis 正好挂了，扣成了负数）
    private void increment(List<DishSales> sales, int sign) {
        try {
            Set<String> touchedKeys = new HashSet<>();
            for (DishSales s : sales) {
                String key = key(s.restaurantId());
                redis.opsForZSet().incrementScore(key, String.valueOf(s.menuItemId()), sign * s.quantity());
                touchedKeys.add(key);
            }
            if (sign < 0) {
                for (String key : touchedKeys) {
                    redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, 0);
                }
            }
        } catch (RuntimeException e) {
            // 第一次失败就放弃这一批，避免 Redis 故障时每个菜都等一次超时
            logger.warn("更新热销榜失败，等下次校准修正。原因: {}", e.getMessage());
        }
    }


    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
