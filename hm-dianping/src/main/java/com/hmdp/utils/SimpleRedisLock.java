package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String PREFIX_KEY = "lock:";
    private static final String PREFIX_ID = UUID.randomUUID().toString(true) + "-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = PREFIX_ID + Thread.currentThread().getId();

        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(PREFIX_KEY + name, threadId, timeoutSec, TimeUnit.SECONDS);
        // 这里是防止自动拆箱时的空指针
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(PREFIX_KEY + name),
                PREFIX_ID + Thread.currentThread().getId()
                );
    }
//    @Override
//    public void unlock() {
//        String threadId = PREFIX_ID + Thread.currentThread().getId();
//
//        String id = stringRedisTemplate.opsForValue().get(PREFIX_KEY + name);
//
//        if (threadId.equals(id)){
//            stringRedisTemplate.delete(PREFIX_KEY + name);
//        }
//    }
}
