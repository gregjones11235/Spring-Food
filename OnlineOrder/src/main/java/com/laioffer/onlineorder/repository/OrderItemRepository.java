package com.laioffer.onlineorder.repository;


import com.laioffer.onlineorder.entity.OrderItemEntity;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.util.List;


public interface OrderItemRepository extends ListCrudRepository<OrderItemEntity, Long> {


    List<OrderItemEntity> getAllByCartId(Long cartId);


    @Modifying
    @Query("""
            INSERT INTO order_items (menu_item_id, cart_id, price, quantity)
            VALUES (:menuItemId, :cartId, :price, 1)
            ON CONFLICT (cart_id, menu_item_id) DO UPDATE SET quantity = order_items.quantity + 1
            """)
    void addOne(Long cartId, Long menuItemId, Double price);


    @Modifying
    @Query("DELETE FROM order_items WHERE cart_id = :cartId")
    void deleteByCartId(Long cartId);
}
