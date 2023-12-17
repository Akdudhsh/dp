package com.hmdp.utils;
/**
 * @author 罗蓉鑫
 * @version 1.0
 */

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * @Author: 罗蓉鑫
 * @Date: 2023/7/2 19:53
 * @Description: 缓存工具类，用于解决缓存击穿，缓存穿透问题
 * @Version 1.0
 */
@Component
public class CacheClient {
    private StringRedisTemplate stringRedisTemplate;
    private final static ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            2,
            5,
            20,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(5),
            Executors.defaultThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //将任意对象转换成json字符串存储在Redis中，并设置过期时间
    public void set(String key, Object object, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(object), time, timeUnit);
    }

    //将任意对象转换成json字符串存储在Redis中，并设置逻辑过期时间(解决缓存击穿问题)
    public void setWithLogicalExpire(String key, Object object, Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        //设置逻辑过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        //设置真正存入的数据
        redisData.setData(object);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 解决缓存穿透的方法
     * @param keyPrefix  key前缀
     * @param id         商品id
     * @param type       返回值类型
     * @param dbFallBack 数据库回调函数
     * @param time       缓存的ttl大小
     * @param timeUnit   缓存的ttl的单位
     * @param <R>
     * @param <ID>
     * @return
     */

    public <R, ID> R queryShopByIdWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack, Long time, TimeUnit timeUnit) {
        //1.查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存中是否存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            //针对于缓存穿透现象，查看缓存中是否缓存了空值，如果缓存了，直接返回，避免到数据库中查询
            return null;
        }
        //4.不存在，查询数据库
        R r = dbFallBack.apply(id);
        //5.如果数据库中也不存在，返回数据不存在
        if (r == null) {
            //缓存"" 值 ，解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(key, "", time, timeUnit);
            return null;
        }
        //6.存在，将数据保存到缓存中,同时设置过期时间,并且设置过期时间的随机性，解决缓存雪崩的问题
        set(key, r, time, timeUnit);
        //7.返回商品信息
        return r;
    }

    /**
     * 用逻辑过期时间来解决缓存击穿问题
     * @param keyPrefix 商品的key前缀
     * @param id 商品id
     * @param type 商品类型
     * @param dbFallBack 数据库查询回调函数
     * @param time 逻辑过期时间的大小
     * @param timeUnit 逻辑过期时间的单位
     * @param lockKeyPrefix 锁的逻辑前缀
     * @return 商品对象
     * @param <R>  商品的类型
     * @param <ID> 商品id的类型
     */


    public <R, ID> R queryShopByIdWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallBack,
            Long time, TimeUnit timeUnit,String lockKeyPrefix) {
        //1.查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断缓存中是否存在
        if (StrUtil.isBlank(json)) {
            //3.不存在，说明不是热点key，直接返回
            return null;
        }
        //反序列化json
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //4.判断是否过期
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            //5.未过期，直接返回
            return r;
        }
        //6.过期，开始重建
        //6.1.获取锁
        String lockKey = lockKeyPrefix + id;
        boolean lock = getLock(lockKeyPrefix + id);
        if (lock) {
            //6.2.成功，开启一个线程去更新redis中的数据
            EXECUTOR.execute(() -> {
                try {
                    //重建
                    Thread.sleep(100);
                    //查询数据
                    R r1 = dbFallBack.apply(id);
                    //重建缓存
                    setWithLogicalExpire(key,r1,time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //6.3.返回旧数据
        return r;
    }

    private boolean getLock(String lockKey) {
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    private void unLock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }
}
