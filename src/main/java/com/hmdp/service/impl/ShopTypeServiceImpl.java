package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.SHOP_LIST;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private RedisTemplate redisTemplate;
    @Override
    public Result queryTypeList() {
        //查询缓存
        String typeListKey = SHOP_LIST;
        List<ShopType> typeList = redisTemplate.opsForList().range(typeListKey, 0, -1);
        //判断数据是否存在
        if(!typeList.isEmpty()){
            //存在，直接返回
            return Result.ok(typeList);
        }
        //不存在，查询数据库
        typeList = query().orderByAsc("sort").list();
        if(typeList == null){
            //如果数据库也不存在，返回数据不存在
            return Result.fail("数据不存在");
        }
        //保存到redis中
        redisTemplate.opsForList().rightPushAll(typeListKey,typeList);
        //返回
        return Result.ok(typeList);
    }
}
