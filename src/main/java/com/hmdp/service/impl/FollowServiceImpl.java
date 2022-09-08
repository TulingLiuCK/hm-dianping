package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;



/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    private Logger logger = LoggerFactory.getLogger(FollowServiceImpl.class);

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        if (followUserId.equals(userId)){
            logger.info("不能关注自己");
            return Result.fail("关注自己，给你一拳");
        }
        logger.info("用户id："+userId);
        //判断是关注还是取关
        if (isFollow) {
            //2关注，新增数据，
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            save(follow);
        } else {
            //3取关，删除数据  delete from tb_follow where user_id = ? and follow_user_id = ?
            remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", followUserId));
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        logger.info("用户id："+userId);
        //查询是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        return Result.ok(count>0);
    }
}
