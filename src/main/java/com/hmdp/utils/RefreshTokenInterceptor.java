package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * @author 罗蓉鑫
 * @version 1.0
 *
 *  刷新登路令牌拦截
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //从redis中取出用户
        String token = request.getHeader("authorization");
        String tokenKey = LOGIN_USER_KEY + token;
        //取出redis中的用户信息
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(tokenKey);
        //判断用户是否存在，不存在代表未登录则直接放行
        if (userMap.isEmpty()) {
            return true;
        }
        if(UserHolder.getUser() == null){ //threadLocal中没有保存用户信息
            //将map转换成UserDTO
            UserDTO user= BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
            //将用户保存到threadLocal中
            UserHolder.saveUser(user);
        }
        //刷新key的存活时间
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户信息
        UserHolder.removeUser();
    }
}
