package com.laioffer.onlineorder.cache;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.serializer.SerializationException;


import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 两级缓存：L1 是本实例内存里的 Caffeine，L2 是所有实例共享的 Redis。
 * <ul>
 *   <li>读：L1 → L2（命中则回填 L1）→ 数据库（加载后写入 L2 和 L1）</li>
 *   <li>写 / 删：先 L2 再 L1，然后广播，让其他实例删掉各自 L1 里的旧值</li>
 *   <li>Redis 出错不抛异常：记日志、熔断（见 {@link L2Guard}），主业务降级为 L1 + 数据库。
 *       故障期间没能删掉的 L2 key 记下来，Redis 恢复后补删，避免恢复后读到故障期间的旧值</li>
 * </ul>
 */
public class TwoLevelCache implements Cache {


    private static final Logger logger = LoggerFactory.getLogger(TwoLevelCache.class);
    private static final Object CLEAR_ALL = new Object();


    private final String name;
    private final CaffeineCache l1;
    private final RedisCache l2;
    private final L2Guard guard;
    private final TwoLevelCacheManager manager;
    private final Set<Object> pendingL2Evictions = ConcurrentHashMap.newKeySet();


    TwoLevelCache(String name, CaffeineCache l1, RedisCache l2, L2Guard guard, TwoLevelCacheManager manager) {
        this.name = name;
        this.l1 = l1;
        this.l2 = l2;
        this.guard = guard;
        this.manager = manager;
    }


    @Override
    public String getName() {
        return name;
    }


    @Override
    public Object getNativeCache() {
        return this;
    }


    @Override
    public ValueWrapper get(Object key) {
        ValueWrapper local = l1.get(key);
        if (local != null) {
            return local;
        }
        ValueWrapper remote = l2Get(key);
        if (remote != null) {
            l1.put(key, remote.get());
        }
        return remote;
    }


    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        Object value = wrapper == null ? null : wrapper.get();
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException("Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }


    // @Cacheable(sync = true) 走这里：Caffeine 保证同一实例内同一个 key 只有一个线程去 L2 / 数据库加载
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        return l1.get(key, () -> {
            ValueWrapper remote = l2Get(key);
            if (remote != null) {
                return (T) remote.get();
            }
            T value = valueLoader.call();
            l2Put(key, value);
            return value;
        });
    }


    @Override
    public void put(Object key, Object value) {
        l2Put(key, value);
        l1.put(key, value);
        manager.publishInvalidation(name, key);
    }


    @Override
    public void evict(Object key) {
        l2Evict(key);
        l1.evict(key);
        manager.publishInvalidation(name, key);
    }


    @Override
    public void clear() {
        l2Evict(CLEAR_ALL);
        l1.clear();
        manager.publishInvalidation(name, TwoLevelCacheManager.ALL_KEYS);
    }


    // 收到其他实例的广播时调用：只删本地 L1。广播里的 key 是字符串，按 toString 比较
    void evictLocal(String key) {
        if (TwoLevelCacheManager.ALL_KEYS.equals(key)) {
            l1.clear();
        } else {
            l1.getNativeCache().asMap().keySet().removeIf(k -> String.valueOf(k).equals(key));
        }
    }


    private ValueWrapper l2Get(Object key) {
        if (!l2Available()) {
            return null;
        }
        try {
            return l2.get(key);
        } catch (RuntimeException e) {
            onL2Error("GET", key, e);
            return null;
        }
    }


    private void l2Put(Object key, Object value) {
        // L2 不缓存 null（序列化器是按具体类型配置的），只放进 L1
        if (value == null || !l2Available()) {
            return;
        }
        try {
            l2.put(key, value);
        } catch (RuntimeException e) {
            onL2Error("PUT", key, e);
        }
    }


    private void l2Evict(Object key) {
        if (!l2Available()) {
            pendingL2Evictions.add(key);
            return;
        }
        try {
            if (key == CLEAR_ALL) {
                l2.clear();
            } else {
                l2.evict(key);
            }
        } catch (RuntimeException e) {
            pendingL2Evictions.add(key);
            onL2Error("EVICT", key, e);
        }
    }


    // 熔断放行时，先把故障期间没删掉的 key 补删
    private boolean l2Available() {
        if (!guard.allowRequest()) {
            return false;
        }
        if (!pendingL2Evictions.isEmpty()) {
            for (Object key : Set.copyOf(pendingL2Evictions)) {
                try {
                    if (key == CLEAR_ALL) {
                        l2.clear();
                    } else {
                        l2.evict(key);
                    }
                    pendingL2Evictions.remove(key);
                } catch (RuntimeException e) {
                    onL2Error("EVICT(retry)", key, e);
                    return false;
                }
            }
            logger.info("Redis 已恢复，缓存 {} 补删了故障期间的 L2 key", name);
        }
        return true;
    }


    private void onL2Error(String operation, Object key, RuntimeException e) {
        if (e instanceof SerializationException) {
            // 数据结构变了（比如 DTO 加了字段）导致旧缓存反序列化失败：当作未命中，删掉这个坏值
            logger.warn("缓存 {} 的 key {} 反序列化失败，已删除: {}", name, key, e.getMessage());
            try {
                l2.evict(key);
            } catch (RuntimeException ignored) {
                // 删不掉就等它自然过期
            }
        } else if (e instanceof DataAccessException && !(e instanceof InvalidDataAccessApiUsageException)) {
            // 连接失败、超时等 Redis 故障：熔断
            guard.recordFailure(name + " " + operation, e);
        } else {
            logger.warn("缓存 {} 的 {} {} 出错: {}", name, operation, key, e.toString());
        }
    }
}
