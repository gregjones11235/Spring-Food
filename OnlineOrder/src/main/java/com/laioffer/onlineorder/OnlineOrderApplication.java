package com.laioffer.onlineorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

@SpringBootApplication
@EnableCaching
public class OnlineOrderApplication {

    private static final Logger logger = LoggerFactory.getLogger(OnlineOrderApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(OnlineOrderApplication.class, args);
    }

    // 启动完成（包括 DevRunner 执行完）后打印前端地址；前端打包在 resources/public 里，由后端直接提供
    @EventListener(ApplicationReadyEvent.class)
    public void printFrontendUrl(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        String port = env.getProperty("local.server.port", "8080");
        String contextPath = env.getProperty("server.servlet.context-path", "");
        logger.info("前端访问地址: http://localhost:{}{}/", port, contextPath);
    }

}
