package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;

    @Override
    public Result isFollowed(Long followId) {
        //1.获取当前用户id
        Long id = UserHolder.getUser().getId();
        //2.从数据库中查询是否已经关注
        Integer count = query().eq("user_id", id).eq("follow_user_id", followId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result follow(Long followId, Boolean isFollow) {
        //1.获取当前用户id
        Long id = UserHolder.getUser().getId();
        String followsKey = "follows:" + id;
        if (isFollow) {
            //3.关注，新增记录
            Follow follow = new Follow();
            follow.setUserId(id);
            follow.setFollowUserId(followId);
            if (save(follow)) {
                stringRedisTemplate.opsForSet().add(followsKey,followId.toString());
            }
            return Result.ok();
        } else {
            //2.取关，删减记录
            if (remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, id)
                    .eq(Follow::getFollowUserId, followId))) {
                stringRedisTemplate.opsForSet().remove(followsKey,followId.toString());
            }
            return Result.ok();
        }
    }

    @Override
    public Result common(Long id) {
        //获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        String key2 = "follows:" + id;
        //获取关注的用户id
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(intersect == null || intersect.isEmpty()){
            //没有共同关注的用户，直接返回空集合
            return Result.ok(Collections.emptyList());
        }
        //解析id
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        //查询出共同关注的用户信息
        List<UserDTO> userDTOS = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

}
