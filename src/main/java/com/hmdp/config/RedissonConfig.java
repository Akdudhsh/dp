package com.hmdp.config;/**
 * @author 罗蓉鑫
 * @version 1.0
 */

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Author: 罗蓉鑫
 * @Date: 2023/7/4 10:29
 * @Description: 配置Redisson客户端
 * @Version 1.0
 */
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
        //配置类
        Config config = new Config();
        //配置redis的地址和密码
        config.useSingleServer().setAddress("redis://192.168.68.129:6379").setPassword("123321");
        //创建客户端
        return Redisson.create(config);
    }
    //@Bean
    public RedissonClient redissonClient2(){
        //配置类
        Config config = new Config();
        //配置redis的地址和密码
        config.useSingleServer().setAddress("redis://192.168.68.129:6380").setPassword("123321");
        //创建客户端
        return Redisson.create(config);
    }
    //@Bean
    public RedissonClient redissonClient3(){
        //配置类
        Config config = new Config();
        //配置redis的地址和密码
        config.useSingleServer().setAddress("redis://192.168.68.129:6381").setPassword("123321");
        //创建客户端
        return Redisson.create(config);
    }
}
