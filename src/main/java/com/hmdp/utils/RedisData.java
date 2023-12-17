package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 针对于解决缓存击穿问题（采用逻辑过期方式），
 * 将对象封装在redisData中，
 * redisData对象中的属性值expireTime即为对象的逻辑过期时间
 */

@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
