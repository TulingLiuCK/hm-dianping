package com.hmdp.service.impl;


import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        List<String> range = stringRedisTemplate.opsForList().range(CACHE_SHOP_KEY+"全部信息", 0, -1);

        if(range.size() != 0){
            return Result.ok(range);
        }
        List<ShopType> sort = query().orderByAsc("sort").list();
        if (sort.size() ==0){
            return Result.fail("出现错误");
        }
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_KEY+"全部信息", String.valueOf(sort));
        return Result.ok(sort);
    }
}
