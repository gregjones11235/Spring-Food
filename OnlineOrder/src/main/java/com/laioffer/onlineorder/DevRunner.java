package com.laioffer.onlineorder;


import com.laioffer.onlineorder.service.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;


@Component
public class DevRunner implements ApplicationRunner {


    private static final Logger logger = LoggerFactory.getLogger(DevRunner.class);


    private final CustomerService customerService;


    public DevRunner(
            CustomerService customerService) {
        this.customerService = customerService;
    }


    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 多实例部署或 INIT_DB=never 时表里已经有这个用户，重复插入会撞唯一约束导致启动失败
        if (customerService.getCustomerByEmail("foo@mail.com") != null) {
            return;
        }
        customerService.signUp("foo@mail.com", "123456", "Foo", "Bar");
    }
}
