package com.laioffer.onlineorder.redislab;


import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * Redis 实验和订单中心用到的 SQL。和主业务共用同一个数据库（onlineorder）和连接池，
 * 表结构统一在 database-init.sql 里。
 */
@Component
public class LabDatabase {


    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;


    public LabDatabase(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(transactionManager);
    }


    public Optional<LabMenuItem> findMenuItem(long id) {
        List<LabMenuItem> rows = jdbc.query(
                "SELECT id, restaurant_id, name, price FROM menu_items WHERE id = ?",
                (rs, rowNum) -> new LabMenuItem(
                        rs.getLong("id"), rs.getLong("restaurant_id"), rs.getString("name"), rs.getDouble("price")),
                id);
        return rows.stream().findFirst();
    }


    public int updatePrice(long id, double price) {
        return jdbc.update("UPDATE menu_items SET price = ? WHERE id = ?", price, id);
    }


    public void addViews(long menuItemId, long delta) {
        jdbc.update("""
                INSERT INTO menu_item_views (menu_item_id, views) VALUES (?, ?)
                ON CONFLICT (menu_item_id) DO UPDATE SET views = menu_item_views.views + EXCLUDED.views
                """, menuItemId, delta);
    }


    public List<Map<String, Object>> findViews() {
        return jdbc.queryForList("""
                SELECT v.menu_item_id, m.name, v.views
                FROM menu_item_views v JOIN menu_items m ON m.id = v.menu_item_id
                ORDER BY v.views DESC
                """);
    }


    // 在一个事务里写 orders + order_lines，价格以数据库为准
    public long createOrder(long customerId, Map<Long, Integer> quantities) {
        return transaction.execute(status -> {
            double total = 0;
            Long restaurantId = null;
            for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
                LabMenuItem menuItem = findMenuItem(entry.getKey())
                        .orElseThrow(() -> new IllegalArgumentException("menu item not found: " + entry.getKey()));
                total += menuItem.price() * entry.getValue();
                // 一个订单归属一家餐厅；购物车里混了多家时取第一家
                if (restaurantId == null) {
                    restaurantId = menuItem.restaurantId();
                }
            }
            Long orderId = jdbc.queryForObject(
                    "INSERT INTO orders (customer_id, restaurant_id, total_price) VALUES (?, ?, ?) RETURNING id",
                    Long.class, customerId, restaurantId, Math.round(total * 100) / 100.0);
            for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
                jdbc.update("""
                        INSERT INTO order_lines (order_id, menu_item_id, price, quantity)
                        SELECT ?, id, price, ? FROM menu_items WHERE id = ?
                        """, orderId, entry.getValue(), entry.getKey());
            }
            return orderId;
        });
    }


    public List<Map<String, Object>> findOrders(long customerId) {
        return jdbc.queryForList("""
                SELECT o.id AS order_id, o.total_price, to_char(o.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at,
                       l.menu_item_id, m.name, l.price, l.quantity
                FROM orders o
                JOIN order_lines l ON l.order_id = o.id
                JOIN menu_items m ON m.id = l.menu_item_id
                WHERE o.customer_id = ?
                ORDER BY o.id DESC, l.id
                """, customerId);
    }


    // 只清订单和浏览量；菜品价格如果改过需要自己改回去
    public void resetOrdersAndViews() {
        jdbc.execute("TRUNCATE order_lines, orders, menu_item_views RESTART IDENTITY");
    }
}
