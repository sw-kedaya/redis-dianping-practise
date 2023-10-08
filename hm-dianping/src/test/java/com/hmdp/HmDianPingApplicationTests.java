package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisWorker;
import jodd.template.StringTemplateParser;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private RedisWorker redisWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void name() throws InterruptedException {
//        shopService.saveShop2Redis(1L, 10L);
        Shop shop = shopService.getById(1L);

        cacheClient.setWithLogicDelete(
                CACHE_SHOP_KEY + shop.getId(), shop, 10L, TimeUnit.SECONDS);
    }

    @Test
    void testKey() throws InterruptedException {
        ExecutorService executorService = Executors.newFixedThreadPool(300);

        CountDownLatch countDownLatch = new CountDownLatch(300);

        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long order = redisWorker.nextId("order");
                System.out.println(order);
            }
            countDownLatch.countDown();
        };

        long begin = System.currentTimeMillis();

        for (int i = 0; i < 300; i++) {
            executorService.submit(task);
        }
        countDownLatch.await();

        long end = System.currentTimeMillis();
        System.out.println("30000个订单耗时为：" + (end - begin));
    }

    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];

        int j;

        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999){
                stringRedisTemplate.opsForHyperLogLog().add("hl", values);
            }
        }

        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl");
        System.out.println(count);
    }
}
