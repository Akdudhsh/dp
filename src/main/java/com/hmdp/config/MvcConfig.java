package com.hmdp.config;

import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.utils.UserInterceptor;
import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resources;

/**
 * @author 罗蓉鑫
 * @version 1.0
 * 配置登录拦截器
 * 配置刷新登录状态拦截器
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //注册登录拦截器
        registry.addInterceptor(new UserInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/voucher/**",
                        "/shop/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot"
                ).order(1);
        //注册刷新登录令牌拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**").order(0);
    }
}
