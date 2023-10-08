package com.hmdp.controller;


import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    @PutMapping("/{followId}/{isFollow}")
    public Result follow(@PathVariable Long followId, @PathVariable Boolean isFollow) {
        return followService.follow(followId, isFollow);
    }

    @GetMapping("/or/not/{followId}")
    public Result isFollow(@PathVariable Long followId) {
        return followService.isFollow(followId);
    }

    @GetMapping("/common/{id}")
    public Result common(@PathVariable Long id){
        return followService.common(id);
    }
}
