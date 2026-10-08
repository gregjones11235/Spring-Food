package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.RestaurantEntity;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.util.List;


public interface RestaurantRepository extends ListCrudRepository<RestaurantEntity, Long> {


    // 首页热门餐厅：按近 30 天订单量排序，没有订单的餐厅排在后面（按 id）。
    // 全新的库还没有订单时，返回的就是种子数据里的前几家餐厅
    @Query("""
            SELECT r.*
            FROM restaurants r
            LEFT JOIN (SELECT restaurant_id, count(*) AS order_count
                       FROM orders
                       WHERE created_at >= now() - INTERVAL '30 days'
                       GROUP BY restaurant_id) o ON o.restaurant_id = r.id
            ORDER BY COALESCE(o.order_count, 0) DESC, r.id
            LIMIT :limit
            """)
    List<RestaurantEntity> findHot(int limit);


    // 顾客端餐厅列表：按店名或品类模糊搜索 + OFFSET 分页。pattern 形如 %keyword%，空关键字时是 %（匹配全部）。
    // 餐厅只有几千家、最多翻几百页，OFFSET 够用；前导 % 用不上 B-tree 索引，表小时全表扫描也只要几毫秒
    @Query("""
            SELECT *
            FROM restaurants
            WHERE name ILIKE :pattern OR category ILIKE :pattern
            ORDER BY id
            LIMIT :limit OFFSET :offset
            """)
    List<RestaurantEntity> search(String pattern, int limit, long offset);


    @Query("SELECT count(*) FROM restaurants WHERE name ILIKE :pattern OR category ILIKE :pattern")
    long countSearch(String pattern);


    @Query("SELECT id FROM restaurants")
    List<Long> findAllIds();


    // 商家账号所属的餐厅；不是商家账号时返回 null
    @Query("SELECT r.* FROM restaurants r JOIN restaurant_staff s ON s.restaurant_id = r.id WHERE s.email = :email")
    RestaurantEntity findByStaffEmail(String email);
}
