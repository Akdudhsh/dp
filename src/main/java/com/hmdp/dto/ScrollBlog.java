package com.hmdp.dto;/**
 * @author 罗蓉鑫
 * @version 1.0
 */

import lombok.Data;

import java.util.List;

/**
 * @Author: 罗蓉鑫
 * @Date: 2023/7/9 21:07
 * @Description:
 * @Version 1.0
 */
@Data
public class ScrollBlog {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
