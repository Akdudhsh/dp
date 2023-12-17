package com.hmdp.utils;/**
 * @author 罗蓉鑫
 * @version 1.0
 */

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @Author: 罗蓉鑫
 * @Date: 2023/7/2 22:10
 * @Description: 基于redis实现的全局唯一id生成器 结构 32位时间戳（68年） +  32位序列号
 * @Version 1.0
 */
@Component
public class RedisIDWorker {
    private StringRedisTemplate stringRedisTemplate;
    //开始的时间戳
    private final static long BEGIN_TIMESTAMP = 1688256000L;

    public RedisIDWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextInt(String prefixKey){
        //1.生成当前的时间戳（距离 2023年7月2日00：00：00：00）
        LocalDateTime now = LocalDateTime.now();
        long end = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = end - BEGIN_TIMESTAMP;
        //2.生成序列号
        //2.1获取当天的日期，便于统计每天每月每年的生成id次数
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        //自增长
        Long increment = stringRedisTemplate.opsForValue().increment("incr:" + prefixKey + ":" + date);
        //3.拼接成为全局唯一ID
        return timeStamp << 32 | increment;
    }
}
