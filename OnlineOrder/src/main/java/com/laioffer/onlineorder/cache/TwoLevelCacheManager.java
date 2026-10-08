package com.laioffer.onlineorder.cache;


import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;


import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;


/**
 * 管理所有 {@link TwoLevelCache}，并负责跨实例的 L1 失效广播：
 * 某个实例修改或删除缓存后，通过 Redis Pub/Sub 发一条消息，其他实例收到后删掉自己 L1 里的同一个 key。
 * <p>
 * 广播只影响 L1。广播期间 Redis 不可用时，其他实例的 L1 最多保留旧值 l1-ttl（默认 60 秒）。
 */
public class TwoLevelCacheManager implements CacheManager, MessageListener {


    public static final String CHANNEL = "cache:invalidation";
    static final String ALL_KEYS = "*";


    private static final Logger logger = LoggerFactory.getLogger(TwoLevelCacheManager.class);


    // 区分消息是不是自己发的：自己发的已经在本地处理过了
    private final String instanceId = UUID.randomUUID().toString();
    private final Map<String, TwoLevelCache> caches = new LinkedHashMap<>();
    private final StringRedisTemplate redis;
    private final L2Guard guard;


    /**
     * @param cacheTypes 每个缓存名对应的值类型。L2 用这个类型做 JSON 序列化，JSON 里不需要存类名
     */
    public TwoLevelCacheManager(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            Map<String, JavaType> cacheTypes,
            Settings settings) {
        this.redis = redis;
        this.guard = new L2Guard(settings.l2CircuitOpen());

        // 清空缓存时用 SCAN 而不是 KEYS（ElastiCache Serverless 禁用了 KEYS）
        RedisCacheWriter writer = RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory, BatchStrategies.scan(1000));
        Map<String, RedisCacheConfiguration> configurations = new LinkedHashMap<>();
        cacheTypes.forEach((name, type) -> configurations.put(name, RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(settings.l2Ttl())
                .disableCachingNullValues()
                .computePrefixWith(cacheName -> "cache:" + cacheName + ":")
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(objectMapper, type)))));
        RedisCacheManager redisCacheManager = RedisCacheManager.builder(writer)
                .withInitialCacheConfigurations(configurations)
                .disableCreateOnMissingCache()
                .build();
        redisCacheManager.afterPropertiesSet();

        for (String name : cacheTypes.keySet()) {
            CaffeineCache l1 = new CaffeineCache(name, Caffeine.newBuilder()
                    .expireAfterWrite(settings.l1Ttl())
                    .maximumSize(settings.l1MaxSize())
                    .build());
            RedisCache l2 = (RedisCache) redisCacheManager.getCache(name);
            caches.put(name, new TwoLevelCache(name, l1, l2, guard, this));
        }
    }


    @Override
    public Cache getCache(String name) {
        return caches.get(name);
    }


    @Override
    public Collection<String> getCacheNames() {
        return caches.keySet();
    }


    // 消息格式：实例ID|缓存名|key
    void publishInvalidation(String cacheName, Object key) {
        if (!guard.allowRequest()) {
            return;
        }
        try {
            redis.convertAndSend(CHANNEL, instanceId + "|" + cacheName + "|" + key);
        } catch (RuntimeException e) {
            guard.recordFailure("PUBLISH", e);
        }
    }


    @Override
    public void onMessage(Message message, byte[] pattern) {
        String[] parts = new String(message.getBody(), StandardCharsets.UTF_8).split("\\|", 3);
        if (parts.length != 3 || instanceId.equals(parts[0])) {
            return;
        }
        TwoLevelCache cache = caches.get(parts[1]);
        if (cache != null) {
            cache.evictLocal(parts[2]);
            logger.debug("收到实例 {} 的失效广播：缓存 {} key {}", parts[0], parts[1], parts[2]);
        }
    }


    public record Settings(Duration l1Ttl, long l1MaxSize, Duration l2Ttl, Duration l2CircuitOpen) {
    }
}
