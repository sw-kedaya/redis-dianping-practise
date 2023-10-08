package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient() {
//        Config config = new Config().setLockWatchdogTimeout(300 * 1000L); //300秒
        Config config = new Config();

        config.useSingleServer()
                .setAddress("redis://192.168.233.110:6379")
                .setPassword("123456");

        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient2() {
        Config config = new Config();

        config.useSingleServer()
                .setAddress("redis://192.168.233.110:6380");

        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient3() {
        Config config = new Config();

        config.useSingleServer()
                .setAddress("redis://192.168.233.110:6381");

        return Redisson.create(config);
    }
}
