package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.toolkit.support.BiIntFunction;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 罗蓉鑫
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 实现发送手机短信验证码功能
     * @param phone 手机号
     * @param session 保存到session中
     * @return
     */

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if(RegexUtils.isPhoneInvalid(phone)){
            //2.格式不正确，返回
            return Result.fail("手机格式不正确");
        }
        //添加一个功能，一个手机一天只能发送3次验证码
        String sendCountKey = SEND_CODE_COUNT + phone;
        String sendCountValue = stringRedisTemplate.opsForValue().get(sendCountKey);
        if( sendCountValue == null){ // 表示第一次发送
            stringRedisTemplate.opsForValue().set(sendCountKey,"0",24,TimeUnit.HOURS);
            sendCountValue = "0";
        }
        if(Integer.parseInt(sendCountValue) >= 3){
            return Result.fail("发送验证码次数达到上限");
        }
        //3.格式正确，生成随机的六位验证码
        String code = RandomUtil.randomNumbers(6);

        //4.将验证码保存到redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5.发送验证码，这里采用日志方式模拟
        log.debug("发送验证码成功phone:{}code:{}",phone,code);
        //发送手机验证码的次数 + 1
        stringRedisTemplate.opsForValue().increment(sendCountKey);
        return Result.ok();
    }

    /**
     * 实现用户登录功能，保存用户信息到redis中
     * @param loginForm 封装用户登录信息的对象
     * @param session  保存到session中
     * @return
     */
    @Override
    public Result saveUserWithPhone(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();
        //1.效验手机号，不正确返回
        if(RegexUtils.isPhoneInvalid(phone)){
            //格式不正确，返回
            return Result.fail("手机格式不正确");
        }
        //2.效验验证码，不正确返回
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if(cacheCode == null || !cacheCode.equals(loginForm.getCode())){
            return Result.fail("验证码不正确");
        }
        //3.根据手机号从数据库中查用户
        User user = query().eq("phone", phone).one();
        //4.判断用户存不存在，如果用户不存在，则创建用户
        if(user == null){
            user = creatUserWithPhone(phone);
        }
        //5.保存到redis中
        // 这里选择将User转换成UserDTO进行保存  DTO(data transfer object) 有关存储粒度问题
        // 保存用户信息完整后面使用越方便，但是会造成内存压力和用户敏感信息泄露
        //生成随机的uuid字符串作为登录令牌
        String token = UUID.randomUUID().toString();
        //登录令牌的key
        String tokenKey = LOGIN_USER_KEY + token;
        //将user对象转换成userDTO对象
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //将userDTO对象转换成hashmap
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().
                        setIgnoreNullValue(true).
                        setFieldValueEditor((fileName, fileValue) -> fileValue.toString()
                        ));
        //保存token信息到redis中
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //设置key存活时间 30分钟
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //返回token信息
        return Result.ok(token);
    }
    //实现登出功能

    @Override
    public Result logout(HttpServletRequest httpServletRequest) {
        String token = httpServletRequest.getHeader("authorization");
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.delete(tokenKey);
        return Result.ok();
    }

    @Override
    public Result sign() {
        //1.获取当前登录用户的信息
        Long userId = UserHolder.getUser().getId();
        //2.获得当天的日期
        LocalDateTime now = LocalDateTime.now();
        String time = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //3.拼接key
        String key = USER_SIGN_KEY + userId + time;
        //4.获取当天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.签到
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth - 1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取当前登录用户的信息
        Long userId = UserHolder.getUser().getId();
        //2.获得当天的日期
        LocalDateTime now = LocalDateTime.now();
        String time = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        //3.拼接key
        String key = USER_SIGN_KEY + userId + time;
        //4.获取当天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取到当前时间为止，签到的记录，返回的是一个十进制数  bitfield bm1 get u2 0
        List<Long> list = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (list == null || list.isEmpty()){
            return Result.ok(0);
        }
        Long number = list.get(0);
        if(number == null || number == 0){
            return Result.ok(0);
        }
        //6.解析结果 (核心)
        int count = 0;
        while (true){
            //将结果与1进行与运行
            if((number & 1) == 0){
                //如果结果为0说明这天未签到
                break;
            }else {
                //如果结果为1说明这天已签到
                count++;
            }
            //无符号右移1位
            number >>>= 1;
        }
        return Result.ok(count);
    }


    private User creatUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        //设置昵称 user_ + 随机10位字符  user_使用常量让代码质量更高，更有逼格
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //保存到数据库中
        save(user);
        return user;
    }
}
