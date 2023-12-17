package com.hmdp.service.impl;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
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
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
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
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    private final static ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            2,
            5,
            20,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(5),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    @Override
    public Result queryShopById(Long id) {
        //解决缓存穿透 原方案
//        Shop shop = queryShopByIdWithPassThrough(id);
        //使用缓存工具类中的方法
        Shop shop = cacheClient.queryShopByIdWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, id2 -> getById(id2), CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //解决缓存击穿(互斥锁方式)
//        Shop shop = queryShopByIdWithMutex(id);
        //解决缓存击穿(逻辑过期方式)
//        Shop shop = queryShopByIdWithLogicalExpire(id);
        //使用缓存工具类中的方法
//        Shop shop = cacheClient.queryShopByIdWithLogicalExpire(
//                CACHE_SHOP_KEY, id, Shop.class, this::getById,
//                20L, TimeUnit.SECONDS, LOCK_SHOP_KEY);
        if (shop == null) {
            return Result.fail("商品信息不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 采用互斥锁方式解决缓存击穿问题
     * @param id
     * @return
     */

    public Shop queryShopByIdWithMutex(Long id) {
        //1.查询缓存
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断缓存中是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {
            //针对于缓存穿透现象，查看缓存中是否缓存了控制，如果缓存了，直接返回，避免到数据库中查询
            return null;
        }
        //4.进行缓存重建
        //4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean lock = getLock(lockKey);
            //4.2 判断是否获取成功
            if (!lock) {
                //4.3 失败，进行等待
                Thread.sleep(50);
                //递归尝试
                return queryShopByIdWithMutex(id);
            }
            //再次检索数据是否存在缓存中
            shopJson = stringRedisTemplate.opsForValue().get(shopKey);
            //判断缓存中是否存在
            if (StrUtil.isNotBlank(shopJson)) {
                //存在，直接返回
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            if (shopJson != null) {
                //针对于缓存穿透现象，查看缓存中是否缓存了空值，如果缓存了，直接返回，避免到数据库中查询
                return null;
            }
            //4.4 成功，查询数据库
            shop = getById(id);
            //模拟重建产生的延迟
            Thread.sleep(200);
            //5.如果数据库中也不存在，返回数据不存在
            if (shop == null) {
                //缓存"" 值 ，解决缓存穿透问题
                stringRedisTemplate.opsForValue().set(shopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6.存在，将数据保存到缓存中,同时设置过期时间,并且设置过期时间的随机性，解决缓存雪崩的问题
            stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL + RandomUtil.randomInt(30), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }
        //7.返回商品信息
        return shop;
    }

    /**
     * 采用逻辑过期时间解决缓存击穿问题
     * @param id
     * @return
     */

    public Shop queryShopByIdWithLogicalExpire(Long id) {
        //1.查询缓存
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断缓存中是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.不存在，说明不是热点key，直接返回
            return null;
        }
        //反序列化json
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //4.判断是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            //5.未过期，直接返回
            return shop;
        }
        //6.过期，开始重建
        //6.1.获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean lock = getLock(LOCK_SHOP_KEY + id);
        if (lock) {
            //6.2.成功，开启一个线程去更新redis中的数据
            EXECUTOR.execute(() -> {
                try {
                    //重建
                    Thread.sleep(100);
                    saveDataToRedis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //6.3.返回旧数据
        return shop;
    }

    private boolean getLock(String lockKey) {
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    private void unLock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

    /**
     * 采用缓存空值解决缓存穿透问题
     * @param id
     * @return
     */

    public Shop queryShopByIdWithPassThrough(Long id) {
        //1.查询缓存
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.判断缓存中是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {
            //针对于缓存穿透现象，查看缓存中是否缓存了空值，如果缓存了，直接返回，避免到数据库中查询
            return null;
        }
        //4.不存在，查询数据库
        Shop shop = getById(id);
        //5.如果数据库中也不存在，返回数据不存在
        if (shop == null) {
            //缓存"" 值 ，解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(shopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6.存在，将数据保存到缓存中,同时设置过期时间,并且设置过期时间的随机性，解决缓存雪崩的问题
        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL + RandomUtil.randomInt(30), TimeUnit.MINUTES);
        //7.返回商品信息
        return shop;
    }

    public void saveDataToRedis(Long id, Long expireSeconds) {
        //查询数据
        Shop shop = getById(id);
        //封装成RedisData
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入缓存
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if (shop.getId() == null) {
            return Result.fail("商品id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据地理坐标查询
        if(x == null || y == null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        int start = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //2.根据地理坐标进行分页查询 geosearch key fromlonlat 116.397904 39.909005 byradius 10 km withdist
        // 这里只能从第一条开始查询，故后面需要进行逻辑分页
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = stringRedisTemplate.opsForGeo().search(
                SHOP_GEO_KEY + typeId,
                GeoReference.fromCoordinate(x, y),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
        );
        if(geoResults == null){
            return Result.ok(Collections.emptyList());
        }
        //3.解析出shopId
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = geoResults.getContent();
        // 进行逻辑分页
        if(list.size() <= start){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size() - start);
        Map<String,Distance> map = new HashMap<>(list.size() - start);
        list.stream().skip(start).forEach(result -> {
            String id = result.getContent().getName();
            ids.add(Long.valueOf(id));
            Distance distance = result.getDistance();
            map.put(id,distance);
        });
        //4.按顺序根据shopId查询出所有商品
        String idStr = StrUtil.join(",", ids);
        List<Shop> shopList = query().inSql("id", idStr).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shopList) {
            shop.setDistance(map.get(shop.getId().toString()).getValue());
        }
        //5.返回数据 商品（带有距离的）
        return Result.ok(shopList);
    }
}
