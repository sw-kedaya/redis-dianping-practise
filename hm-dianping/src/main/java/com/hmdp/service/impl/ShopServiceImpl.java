package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.sql.Struct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 穿透处理
//        Result result = queryWithPassThrough(id);
//        Shop shop = cacheClient.queryWithPassThrough(
//                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        // 用互斥锁解决击穿问题
//        Result result = queryWithMutex(id);

        // 用逻辑删除解决击穿问题
//        Result result = queryWithLogicDelete(id);
        Shop shop = cacheClient.queryWithLogicDelete(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, 10L, TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private Result queryWithLogicDelete(Long id) {
        String key = CACHE_SHOP_KEY + id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(shopJson)) {
            return Result.fail("店铺不存在！");
        }

        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        if (LocalDateTime.now().isBefore(expireTime)) {
            return Result.ok(shop);
        }

        String lockKey = LOCK_SHOP_KEY + id;
        Boolean flag = this.lock(lockKey);
        if (flag) {
            // 如果拿到锁，再查一下redis数据是否过期
            String shopJsonAgain = stringRedisTemplate.opsForValue().get(key);
            RedisData redisDataAgain = JSONUtil.toBean(shopJsonAgain, RedisData.class);
            Shop shopAgain = JSONUtil.toBean((JSONObject) redisDataAgain.getData(), Shop.class);
            LocalDateTime expireTimeAgain = redisDataAgain.getExpireTime();
            if (LocalDateTime.now().isBefore(expireTimeAgain)) {
                return Result.ok(shopAgain);
            }

            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException();
                } finally {
                    this.unlock(lockKey);
                }
            });
        }

        return Result.ok(shop);
    }

    public void saveShop2Redis(Long id, Long expireSecond) throws InterruptedException {
        Shop shop = this.getById(id);

        Thread.sleep(200);

        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSecond));

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.如果不需要坐标查询，就直接查数据库
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis，默认升序，获取出shopId, distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );

        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        // 先把results的list列表拿出来
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        // 如果查出来的数据条数和分页的数字一样就返回空，因为后面要切割from前面的数据，避免空指针
        if (list.size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        ArrayList<Long> ids = new ArrayList<>();
        HashMap<String, Double> distanceMap = new HashMap<>();
        // 自己手动分页：逻辑分页
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));

            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance.getValue());
        });

        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids)
                .last("ORDER BY FIELD(id ," + idStr + ")").list();

        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()));
        }

        return Result.ok(shops);
    }

    private Boolean lock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",
                LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    private Result queryWithMutex(Long id) {

        String key = CACHE_SHOP_KEY + id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (!StrUtil.isBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        // 前面判断过来，到这里的话不是null就是空
        if (shopJson != null) {
            return Result.fail("店铺不存在!");
        }

        String lockKey = LOCK_SHOP_KEY + id;
        try {
            // 获取互斥锁
            Boolean flag = lock(lockKey);
            if (!flag) {
                // 如果没拿到锁，休眠一段时间继续查询
                Thread.sleep(50);
                queryWithMutex(id);
            }
            // 如果拿到了再次查询一次缓存看看是否有数据
            String shopJsonAgain = stringRedisTemplate.opsForValue().get(key);
            if (!StrUtil.isBlank(shopJsonAgain)) {
                Shop shop = JSONUtil.toBean(shopJson, Shop.class);
                return Result.ok(shop);
            }

            // 前面判断过来，到这里的话不是null就是空
            if (shopJsonAgain != null) {
                return Result.fail("店铺不存在!");
            }

            Shop shop = this.getById(id);
            // 假装重构时耗时很久
            Thread.sleep(200);
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return Result.fail("店铺不存在");
            }

            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL,
                    TimeUnit.MINUTES);

            return Result.ok(shop);
        } catch (InterruptedException e) {
            throw new RuntimeException();
        } finally {
            unlock(lockKey);
        }
    }

    private Result queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (!StrUtil.isBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        // 前面判断过来，到这里的话不是null就是空
        if (shopJson != null) {
            return Result.fail("店铺不存在!");
        }

        Shop shop = this.getById(id);
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在");
        }

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result updateRedis(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺不存在");
        }

        this.updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
