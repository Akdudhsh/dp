package com.hmdp.utils;/**
 * @author 罗蓉鑫
 * @version 1.0
 */

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @Author: 罗蓉鑫
 * @Date: 2023/7/3 19:16
 * @Description:
 * @Version 1.0
 */
public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final String LOCK_PRE = "lock:";
    private static final String ID_PRE = UUID.randomUUID(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(Long timeOutSec) {
        //获取线程表示 ID_PRE标识的是一个进程，Thread.currentThread().getId()标识的是一个进程
        String threadId = ID_PRE + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(
                LOCK_PRE + name, threadId, timeOutSec, TimeUnit.SECONDS);
        //如果直接返回Boolean，拆箱过程中如果发送success为null,会出现null指针异常
        return Boolean.TRUE.equals(success);
    }
    @Override
    public void unlock() {
        //锁的使用已经造成了性能问题，这里读取的lua脚本需要提前加载（因为要通过io操作）
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(LOCK_PRE + name),
                ID_PRE + Thread.currentThread().getId());
    }

//    @Override
//    public void unlock() {
//        //获取线程表示 ID_PRE标识的是一个进程，Thread.currentThread().getId()标识的是一个进程
//        String threadId = ID_PRE + Thread.currentThread().getId();
//        if (stringRedisTemplate.opsForValue().get(LOCK_PRE + name).equals(threadId)) {
//            // 判断锁是否为自己的，防止误删
//            // 因为验证与删除是俩个动作，不保证原子性，仍有可能再验证完成后，
//            // 程序发生阻塞（非业务阻塞，由于jvm full GC操作），造成误删问题
//            stringRedisTemplate.delete(LOCK_PRE + name);
//        }
//    }
}
