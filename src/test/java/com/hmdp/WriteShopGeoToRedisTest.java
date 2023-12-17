package com.hmdp;/**
 * @author 罗蓉鑫
 * @version 1.0
 */

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * @Author: 罗蓉鑫
 * @Date: 2023/7/9 23:00
 * @Description: 将商品的地理信息写入redis中的geo数据类型中
 * @Version 1.0
 */
@SpringBootTest
public class WriteShopGeoToRedisTest {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IShopService shopService;
    @Test
    void input(){
        //查询所有商品数据
        List<Shop> shops = shopService.list();
        //根据shop的类型进行分组
        Map<Long, List<Shop>> map = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long type_id = entry.getKey();
            String key = SHOP_GEO_KEY + type_id;
            //将list（shop）集合数据转化成list（GeoLocation）一次性写入redis中
            List<Shop> list = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> geoLocationList = new ArrayList<>(list.size());
            for (Shop shop : list) {
                RedisGeoCommands.GeoLocation<String> geoLocation = new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(), new Point(shop.getX(), shop.getY()));
                geoLocationList.add(geoLocation);
            }
            //减少与redis的交互次数
            stringRedisTemplate.opsForGeo().add(key,geoLocationList);
        }
    }

    /**
     * 测试采用HypeLogLog统计100w数据
     */
    @Test
    void testHypeLogLog() {
        String[] strings = new String[1000];
        int j;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            strings[j] = "user_" + i;
            if (j == 999) {
                stringRedisTemplate.opsForHyperLogLog().add("hll", strings);
            }
        }
        Long size = stringRedisTemplate.opsForHyperLogLog().size("hll");
        System.out.println(size);
    }

}
