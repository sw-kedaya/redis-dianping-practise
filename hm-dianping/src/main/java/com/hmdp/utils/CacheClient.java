package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicDelete(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String prefixKey, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit) {
        String key = prefixKey + id;

        String json = stringRedisTemplate.opsForValue().get(key);
        if (!StrUtil.isBlank(json)) {
            return JSONUtil.toBean(json, type);
        }

        // 前面判断过来，到这里的话不是null就是空
        if (json != null) {
            return null;
        }

        R r = dbFallBack.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        this.set(key, r, time, unit);

        return r;
    }

    public <R, ID> R queryWithLogicDelete(
            String prefixKey, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit unit) {
        String key = prefixKey + id;

        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        if (LocalDateTime.now().isBefore(expireTime)) {
            return r;
        }

        String lockKey = LOCK_SHOP_KEY + id;
        Boolean flag = this.lock(lockKey);
        if (flag) {
            // 如果拿到锁，再查一下redis数据是否过期
            String jsonAgain = stringRedisTemplate.opsForValue().get(key);
            RedisData redisDataAgain = JSONUtil.toBean(jsonAgain, RedisData.class);
            R rAgain = JSONUtil.toBean((JSONObject) redisDataAgain.getData(), type);
            LocalDateTime expireTimeAgain = redisDataAgain.getExpireTime();
            if (LocalDateTime.now().isBefore(expireTimeAgain)) {
                return rAgain;
            }

            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallBack.apply(id);
                    this.setWithLogicDelete(key, r1, time,unit);
                } catch (Exception e) {
                    throw new RuntimeException();
                } finally {
                    this.unlock(lockKey);
                }
            });
        }

        return r;
    }

    private Boolean lock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",
                LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
