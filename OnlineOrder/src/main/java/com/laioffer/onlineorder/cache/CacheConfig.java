package com.laioffer.onlineorder.cache;


import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.laioffer.onlineorder.model.CartDto;
import com.laioffer.onlineorder.model.RestaurantDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;


import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Configuration
public class CacheConfig {


    private static final Logger logger = LoggerFactory.getLogger(CacheConfig.class);


    // 定义了 CacheManager Bean 之后，Spring Boot 自带的缓存自动配置（spring.cache.*）不再生效
    @Bean
    TwoLevelCacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redis,
            @Value("${app.cache.l1-ttl}") Duration l1Ttl,
            @Value("${app.cache.l1-max-size}") long l1MaxSize,
            @Value("${app.cache.l2-ttl}") Duration l2Ttl,
            @Value("${app.cache.l2-circuit-open}") Duration l2CircuitOpen) {
        // 独立的 ObjectMapper：缓存里的 JSON 不受 Web 层 SNAKE_CASE 等配置影响
        ObjectMapper objectMapper = new ObjectMapper();
        TypeFactory types = objectMapper.getTypeFactory();
        Map<String, JavaType> cacheTypes = new LinkedHashMap<>();
        cacheTypes.put("restaurants", types.constructCollectionType(List.class, RestaurantDto.class));
        cacheTypes.put("cart", types.constructType(CartDto.class));
        return new TwoLevelCacheManager(connectionFactory, redis, objectMapper, cacheTypes,
                new TwoLevelCacheManager.Settings(l1Ttl, l1MaxSize, l2Ttl, l2CircuitOpen));
    }


    // 订阅 L1 失效广播。不随 Spring 容器自动启动：自动启动时如果 Redis 连不上，整个应用都会启动失败
    @Bean
    RedisMessageListenerContainer cacheInvalidationListener(
            RedisConnectionFactory connectionFactory, TwoLevelCacheManager cacheManager) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer() {
            @Override
            public boolean isAutoStartup() {
                return false;
            }
        };
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(cacheManager, new ChannelTopic(TwoLevelCacheManager.CHANNEL));
        // 订阅成功之后连接再断开，由容器自己按这个间隔重连
        container.setRecoveryInterval(5000);
        return container;
    }


    // 应用启动完成后在后台订阅，失败就每 10 秒重试。
    // 订阅成功之前收不到其他实例的广播，本地 L1 最多保留旧值 l1-ttl（60 秒）
    @Bean
    ApplicationListener<ApplicationReadyEvent> startCacheInvalidationListener(RedisMessageListenerContainer container) {
        return event -> Thread.ofVirtual().name("cache-invalidation-subscriber").start(() -> {
            while (!container.isListening()) {
                try {
                    container.start();
                    logger.info("已订阅缓存失效广播频道 {}", TwoLevelCacheManager.CHANNEL);
                } catch (RuntimeException e) {
                    logger.warn("订阅缓存失效广播失败，10 秒后重试: {}", e.getMessage());
                    container.stop();
                    try {
                        Thread.sleep(Duration.ofSeconds(10));
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }
        });
    }
}
