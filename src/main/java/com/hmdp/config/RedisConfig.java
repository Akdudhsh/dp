package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;

/**
 * @author 罗蓉鑫
 * @version 1.0
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {
   @Bean
    public RedisTemplate<String,Object> redisTemplate(RedisConnectionFactory redisConnectionFactory){
       RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
       redisTemplate.setConnectionFactory(redisConnectionFactory);
       GenericJackson2JsonRedisSerializer jsonRedisSerializer = new GenericJackson2JsonRedisSerializer();
       //key 和 hashKey 用String序列化
       redisTemplate.setKeySerializer(RedisSerializer.string());
       redisTemplate.setHashKeySerializer(RedisSerializer.string());
       //Value 和 HashValue 采用Json序列化
       redisTemplate.setValueSerializer(jsonRedisSerializer);
       redisTemplate.setHashValueSerializer(jsonRedisSerializer);
       return redisTemplate;
   }
}
