package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 罗蓉鑫
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Resource
    private IFollowService followService;

    /**
     * 判断是否已经关注某个用户
     * @param followId
     * @return
     */
    @GetMapping("/or/not/{id}")
    public Result isFollowed(@PathVariable("id") Long followId){
        return followService.isFollowed(followId);
    }

    /**
     * 关注/取关某个用户
     * @param followId
     * @param isFollow 是否关注
     * @return
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followId,@PathVariable("isFollow")Boolean isFollow){
        return followService.follow(followId,isFollow);
    }
    @GetMapping("/common/{id}")
    public Result common(@PathVariable("id")Long id){
        return followService.common(id);
    }

}
