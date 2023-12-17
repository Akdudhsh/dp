package com.hmdp.utils;/**
 * @author 罗蓉鑫
 * @version 1.0
 */

/**
 * @Author:罗蓉鑫
 * @Date: 2023/7/3 19:15
 * @Description:
 * @Version 1.0
 */
public interface ILock {
    boolean tryLock(Long timeOutSec);
    void unlock();
}
