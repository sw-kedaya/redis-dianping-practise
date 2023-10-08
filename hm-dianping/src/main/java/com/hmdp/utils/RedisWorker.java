package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisWorker {

    private final static long BEGIN_TIMESTAMP = 1640995200L;
    private final static long COUNT_BITS = 32L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String prefixKey) {
        // 计算从2022/1/1到当前的秒数timestamp
        LocalDateTime now = LocalDateTime.now();
        long timestamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;

        // 年月日用:分隔，有利于redis的统计
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        // 以每天的日期作为key记录当天订单量
        long count = stringRedisTemplate.opsForValue().increment("icr:" + prefixKey + date);

        // 根据上面的key图，需要把当前秒数向左移32位，然后右32位直接异或进来
        return timestamp << COUNT_BITS | count;
    }


//    public static void main(String[] args) {
//        // 计算出2022/1/1的秒数
//        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
//        long instant = time.toInstant(ZoneOffset.UTC).toEpochMilli();
//        System.out.println("先转成instant类再转成时间戳：" + instant);
//        long second = time.toEpochSecond(ZoneOffset.UTC);
//        System.out.println("直接转成秒数：" + second);
//    }

}
