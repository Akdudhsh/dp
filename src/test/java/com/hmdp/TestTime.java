package com.hmdp;/**
 * @author 罗蓉鑫
 * @version 1.0
 */

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

/**
 * @Author: 罗蓉鑫
 * @Date: 2023/7/2 17:30
 * @Description:
 * @Version 1.0
 */
public class TestTime {
    @Test
    void test1(){
        System.out.println(LocalDateTime.now().plusSeconds(20));
    }
}
