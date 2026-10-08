package com.laioffer.onlineorder.cache;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.time.Duration;


/**
 * 二级缓存（Redis）的简易熔断：一次连接失败或超时后，在 openDuration 内直接跳过 Redis，
 * 主业务只用本地缓存 + 数据库，避免 Redis 故障期间每个请求都要等一次超时。
 * 时间到了自动放行下一次请求去试探，成功就恢复正常。
 */
public class L2Guard {


    private static final Logger logger = LoggerFactory.getLogger(L2Guard.class);


    private final long openMillis;
    private volatile long openUntil = 0;


    public L2Guard(Duration openDuration) {
        this.openMillis = openDuration.toMillis();
    }


    public boolean allowRequest() {
        return System.currentTimeMillis() >= openUntil;
    }


    public void recordFailure(String operation, RuntimeException e) {
        boolean wasClosed = allowRequest();
        openUntil = System.currentTimeMillis() + openMillis;
        if (wasClosed) {
            logger.warn("Redis 二级缓存不可用（{}），{} 秒内跳过 Redis，只用本地缓存和数据库。原因: {}",
                    operation, openMillis / 1000, e.getMessage());
        }
    }
}
