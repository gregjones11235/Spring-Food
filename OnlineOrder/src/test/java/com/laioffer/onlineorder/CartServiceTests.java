package com.laioffer.onlineorder;


import com.laioffer.onlineorder.entity.CartEntity;
import com.laioffer.onlineorder.entity.MenuItemEntity;
import com.laioffer.onlineorder.entity.OrderItemEntity;
import com.laioffer.onlineorder.model.CartDto;
import com.laioffer.onlineorder.repository.CartRepository;
import com.laioffer.onlineorder.repository.MenuItemRepository;
import com.laioffer.onlineorder.repository.OrderItemRepository;
import com.laioffer.onlineorder.service.CartService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;


import java.util.List;
import java.util.Optional;
import java.util.Set;


@ExtendWith(MockitoExtension.class)
public class CartServiceTests {


    @Mock
    private CartRepository cartRepository;


    @Mock
    private MenuItemRepository menuItemRepository;


    @Mock
    private OrderItemRepository orderItemRepository;


    private CartService cartService;


    @BeforeEach
    void setup() {
        cartService = new CartService(cartRepository, menuItemRepository, orderItemRepository);
    }


    // 加菜改成了原子写法（修并发下的丢失更新和重复插入，见 SQL_and_Redis_lab.md Q8）：
    // 不再先查后写，菜品已在购物车里时数量 +1 由 INSERT ... ON CONFLICT 在数据库里完成，总价用 total_price + delta 累加。
    // 所以不管菜品是否已经在购物车里，service 层的调用都一样
    @Test
    void addMenuItemToCart_shouldUpsertOrderItemAndAddPrice() {
        // Mock data
        long customerId = 1L;
        long menuItemId = 2L;
        long cartId = 3L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, 0.0);
        MenuItemEntity menuItem = new MenuItemEntity(menuItemId, 1L, "Name", "", 10.0, "");


        // Mock repository method calls
        Mockito.when(cartRepository.getByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(menuItemRepository.findById(menuItemId)).thenReturn(Optional.of(menuItem));


        // Perform the method under test
        cartService.addMenuItemToCart(customerId, menuItemId);


        // Verify the repository method calls
        Mockito.verify(orderItemRepository).addOne(cartId, menuItemId, 10.0);
        Mockito.verify(cartRepository).addTotalPrice(cartId, 10.0);
        Mockito.verify(cartRepository, Mockito.never()).updateTotalPrice(Mockito.anyLong(), Mockito.anyDouble());
    }


    @Test
    void getCart_shouldReturnCartDto() {
        // Mock data
        long customerId = 1L;
        long cartId = 3L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, 21.0);
        List<OrderItemEntity> orderItems = List.of(
                new OrderItemEntity(1L, 1L, cartId, 10.0, 1),
                new OrderItemEntity(2L, 2L, cartId, 10.0, 2)
        );
        List<MenuItemEntity> menuItems = List.of(
                new MenuItemEntity(1L, 1L, "Name1", "", 1.0, ""),
                new MenuItemEntity(2L, 1L, "Name2", "", 10.0, "")
        );


        // Mock repository method calls
        Mockito.when(cartRepository.getByCustomerId(customerId)).thenReturn(cartEntity);
        Mockito.when(orderItemRepository.getAllByCartId(cartEntity.id())).thenReturn(orderItems);
        // 菜品用一次 IN 查询批量取回（修 N+1），返回顺序故意和购物车里的顺序相反
        Mockito.when(menuItemRepository.findAllById(List.of(1L, 2L))).thenReturn(List.of(menuItems.get(1), menuItems.get(0)));


        // Perform the method under test
        CartDto cartDto = cartService.getCart(customerId);


        // Verify the expected CartDto properties
        Assertions.assertEquals(cartId, cartDto.id());
        Assertions.assertEquals(cartEntity.totalPrice(), cartDto.totalPrice());
        Assertions.assertEquals(orderItems.size(), cartDto.orderItems().size());
        // 每个购物车条目要对上自己的菜品，不能受 IN 查询返回顺序影响
        Assertions.assertEquals("Name1", cartDto.orderItems().get(0).menuItemName());
        Assertions.assertEquals("Name2", cartDto.orderItems().get(1).menuItemName());
        Mockito.verify(menuItemRepository, Mockito.never()).findById(Mockito.anyLong());
    }


    @Test
    void clearCart_shouldRemoveAllItemsAndResetTotalPrice() {
        // Mock data
        long customerId = 1L;
        long cartId = 2L;
        CartEntity cartEntity = new CartEntity(cartId, customerId, 21.0);


        // Mock repository method calls
        Mockito.when(cartRepository.getByCustomerId(customerId)).thenReturn(cartEntity);


        // Perform the method under test
        cartService.clearCart(customerId);


        // Verify the repository method calls
        Mockito.verify(orderItemRepository).deleteByCartId(cartId);
        Mockito.verify(cartRepository).updateTotalPrice(cartId, 0.0);
    }
}
