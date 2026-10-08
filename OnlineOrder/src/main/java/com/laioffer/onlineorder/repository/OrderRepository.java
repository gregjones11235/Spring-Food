package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.model.MerchantOrderDto;
import com.laioffer.onlineorder.model.OrderLineDto;
import com.laioffer.onlineorder.model.OrderSearchCriteria;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;


import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * orders / order_lines 的 SQL。商家搜索的条件是动态拼的，Spring Data 的 @Query 写不了，所以直接用 JdbcTemplate。
 *
 * 这里的查询都是"业务上最自然的第一版写法"，订单中心的表也刻意没有二级索引：
 * 按日期查对列做了函数运算、手机号前缀 / 尾号用 LIKE、菜名用 ILIKE '%...%'，
 * 都是 SQL_and_Redis_lab.md Q3 要逐个 EXPLAIN、再优化的对象。改写法或加索引前先留"优化前"的证据
 */
@Repository
public class OrderRepository {


    // 餐厅都在旧金山湾区，"某一天"按太平洋时间算
    public static final String BUSINESS_TIME_ZONE = "America/Los_Angeles";


    private static final String ORDER_COLUMNS = """
            SELECT o.id, o.status, o.total_price, o.created_at, c.first_name, c.last_name, c.phone
            FROM orders o
            JOIN customers c ON c.id = o.customer_id
            """;


    private final NamedParameterJdbcTemplate jdbc;


    public OrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }


    public long createOrder(long customerId, long restaurantId, BigDecimal totalPrice) {
        return jdbc.queryForObject("""
                        INSERT INTO orders (customer_id, restaurant_id, total_price)
                        VALUES (:customerId, :restaurantId, :totalPrice)
                        RETURNING id
                        """,
                new MapSqlParameterSource()
                        .addValue("customerId", customerId)
                        .addValue("restaurantId", restaurantId)
                        .addValue("totalPrice", totalPrice),
                Long.class);
    }


    public void addOrderLine(long orderId, long menuItemId, BigDecimal price, int quantity) {
        jdbc.update("""
                        INSERT INTO order_lines (order_id, menu_item_id, price, quantity)
                        VALUES (:orderId, :menuItemId, :price, :quantity)
                        """,
                new MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("menuItemId", menuItemId)
                        .addValue("price", price)
                        .addValue("quantity", quantity));
    }


    // 待处理的订单：待接单（PAID）和已接单未完成（ACCEPTED），先下单的排前面
    public List<MerchantOrderDto> findActive(long restaurantId, int limit) {
        String sql = ORDER_COLUMNS + """
                WHERE o.restaurant_id = :restaurantId AND o.status IN ('PAID', 'ACCEPTED')
                ORDER BY o.created_at, o.id
                LIMIT :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("restaurantId", restaurantId)
                .addValue("limit", limit);
        return withLines(jdbc.query(sql, params, ORDER_ROW));
    }


    public List<MerchantOrderDto> search(long restaurantId, OrderSearchCriteria criteria, int limit, long offset) {
        StringBuilder sql = new StringBuilder(ORDER_COLUMNS).append("WHERE o.restaurant_id = :restaurantId\n");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("restaurantId", restaurantId);

        if (criteria.date() != null) {
            sql.append("AND (o.created_at AT TIME ZONE '" + BUSINESS_TIME_ZONE + "')::date = :date\n");
            params.addValue("date", criteria.date());
        }
        if (criteria.phone() != null) {
            switch (criteria.phoneMatch()) {
                case "prefix" -> {
                    sql.append("AND c.phone LIKE :phone\n");
                    params.addValue("phone", criteria.phone() + "%");
                }
                case "suffix" -> {
                    sql.append("AND c.phone LIKE :phone\n");
                    params.addValue("phone", "%" + criteria.phone());
                }
                default -> {
                    sql.append("AND c.phone = :phone\n");
                    params.addValue("phone", criteria.phone());
                }
            }
        }
        if (criteria.dish() != null) {
            sql.append("""
                    AND EXISTS (SELECT 1
                                FROM order_lines l
                                JOIN menu_items m ON m.id = l.menu_item_id
                                WHERE l.order_id = o.id AND m.name ILIKE :dish)
                    """);
            params.addValue("dish", LikePattern.contains(criteria.dish()));
        }
        if (criteria.statuses() != null && !criteria.statuses().isEmpty()) {
            sql.append("AND o.status IN (:statuses)\n");
            params.addValue("statuses", criteria.statuses());
        }
        sql.append("ORDER BY o.created_at DESC, o.id DESC\nLIMIT :limit OFFSET :offset");
        params.addValue("limit", limit).addValue("offset", offset);
        return withLines(jdbc.query(sql.toString(), params, ORDER_ROW));
    }


    // 状态机的条件更新：只有当前状态是 from 时才改成 to，影响 0 行说明状态已经变了（比如被另一台设备先接了单）
    public int transition(long restaurantId, long orderId, String from, String to) {
        return jdbc.update("""
                        UPDATE orders SET status = :to, version = version + 1
                        WHERE id = :orderId AND restaurant_id = :restaurantId AND status = :from
                        """,
                new MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("restaurantId", restaurantId)
                        .addValue("from", from)
                        .addValue("to", to));
    }


    public String findStatus(long restaurantId, long orderId) {
        List<String> rows = jdbc.queryForList(
                "SELECT status FROM orders WHERE id = :orderId AND restaurant_id = :restaurantId",
                new MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("restaurantId", restaurantId),
                String.class);
        return rows.isEmpty() ? null : rows.get(0);
    }


    // 热销榜用到的销量都按菜品自己的 restaurant_id 归属：lab 结账允许一单混多家店，按 orders.restaurant_id 会算错店
    public record DishSales(long restaurantId, long menuItemId, long quantity) {
    }


    // 一个订单里每个菜卖了几份（取消订单时从热销榜扣回）
    public List<DishSales> findDishSales(long orderId) {
        return jdbc.query("""
                        SELECT m.restaurant_id, l.menu_item_id, SUM(l.quantity) AS quantity
                        FROM order_lines l
                        JOIN menu_items m ON m.id = l.menu_item_id
                        WHERE l.order_id = :orderId
                        GROUP BY m.restaurant_id, l.menu_item_id
                        """,
                new MapSqlParameterSource("orderId", orderId), DISH_SALES_ROW);
    }


    // 全部未取消订单的累计销量，热销榜校准用。200 万订单要聚合全表，只在手动校准时调用
    public List<DishSales> findAllDishSales() {
        return jdbc.query("""
                        SELECT m.restaurant_id, l.menu_item_id, SUM(l.quantity) AS quantity
                        FROM order_lines l
                        JOIN orders o ON o.id = l.order_id
                        JOIN menu_items m ON m.id = l.menu_item_id
                        WHERE o.status <> 'CANCELLED'
                        GROUP BY m.restaurant_id, l.menu_item_id
                        """,
                DISH_SALES_ROW);
    }


    private static final RowMapper<DishSales> DISH_SALES_ROW = (rs, rowNum) ->
            new DishSales(rs.getLong("restaurant_id"), rs.getLong("menu_item_id"), rs.getLong("quantity"));


    // 一次 IN 查询取回这一页所有订单的明细，再按订单分组（避免每个订单查一次的 N+1）
    private List<MerchantOrderDto> withLines(List<MerchantOrderDto> orders) {
        if (orders.isEmpty()) {
            return orders;
        }
        Map<Long, List<OrderLineDto>> lines = findLines(orders.stream().map(MerchantOrderDto::id).toList());
        List<MerchantOrderDto> result = new ArrayList<>();
        for (MerchantOrderDto o : orders) {
            result.add(new MerchantOrderDto(o.id(), o.status(), o.totalPrice(), o.createdAt(),
                    o.customerName(), o.customerPhone(), lines.getOrDefault(o.id(), List.of())));
        }
        return result;
    }


    private Map<Long, List<OrderLineDto>> findLines(Collection<Long> orderIds) {
        Map<Long, List<OrderLineDto>> lines = new HashMap<>();
        jdbc.query("""
                        SELECT l.order_id, m.name, l.quantity, l.price
                        FROM order_lines l
                        JOIN menu_items m ON m.id = l.menu_item_id
                        WHERE l.order_id IN (:orderIds)
                        ORDER BY l.id
                        """,
                new MapSqlParameterSource("orderIds", orderIds),
                rs -> {
                    lines.computeIfAbsent(rs.getLong("order_id"), k -> new ArrayList<>())
                            .add(new OrderLineDto(rs.getString("name"), rs.getInt("quantity"), rs.getBigDecimal("price")));
                });
        return lines;
    }


    private static final RowMapper<MerchantOrderDto> ORDER_ROW = (rs, rowNum) -> {
        String firstName = rs.getString("first_name");
        String lastName = rs.getString("last_name");
        String name = ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
        return new MerchantOrderDto(
                rs.getLong("id"),
                rs.getString("status"),
                rs.getBigDecimal("total_price"),
                rs.getObject("created_at", OffsetDateTime.class),
                name,
                rs.getString("phone"),
                List.of());
    };
}
