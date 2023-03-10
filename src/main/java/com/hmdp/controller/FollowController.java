package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Autowired
    private IFollowService iFollowService;
    /**
     * 关注或者取关用户
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id")Long followUserId,@PathVariable("isFollow")Boolean isFollow){
        return iFollowService.follow(followUserId,isFollow);
    }
    /**
     * 是否取关
     */
    @GetMapping("/or/not/{id}")
    public Result follow(@PathVariable("id")Long followUserId){
        return iFollowService.isFollow(followUserId);
    }
    /**
     * 获取共同关注
     */
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id")Long id){
        return iFollowService.followCommons(id);
    }

}
