package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest(classes = HmDianPingApplication.class)
public class LoadShopData {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopService shopService;

    @Test
    void load() {
        // 1.查询店铺信息
        List<Shop> shops = shopService.list();
        // 2.把店铺根据typeId分组
        Map<Long, List<Shop>> map = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.写入redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 4.获取typeId和店铺数据
            Long typeId = entry.getKey();
            List<Shop> value = entry.getValue();
            // 5.生成key
            String key = SHOP_GEO_KEY + typeId;
            // 6.一条一条把保存到redis，请求太多效率不好，所以先封装再一条请求存储到redis
            List<RedisGeoCommands.GeoLocation<String>> location = new ArrayList<>(value.size());
            for (Shop shop : value) {
                // 7.Geo底层就是zset，所以v是店铺id，分数是地理坐标转化的
                location.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(),shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, location);
        }
    }
}
