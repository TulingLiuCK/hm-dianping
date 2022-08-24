package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import io.netty.util.internal.StringUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        //缓存穿透解决方法
//        Shop shop = queryWithPassThrough(id);
//        用互斥锁解决缓存揭穿
//        Shop shop = queryWithMutex(id);
        //利用逻辑过期时间解决缓存穿透
//        Shop shop = queryWithLogicalExpire(id);
//        if (shop == null){
//            return Result.fail("店铺不存在！");
//        }
        //利用工具类解决缓存穿透问题
        Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        return Result.ok(shop);

    }

    /**
     * 利用互斥锁解决缓存击穿
     */
    public Shop queryWithMutex(Long id) {
        //1从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3存在直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是控制
        if (shopJson != null) {
            //返回一个错误信息
            return null;
        }
        //开始实现缓存重建
        Shop shop = null;
        try {
            //4.1获取互斥锁
            boolean isLock = tryLock(LOCK_SHOP_KEY + id);
            //4.2判断是否获取成功
            if (!isLock) {
                //4.3如果失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4成功，根据id查询数据库
            shop = getById(id);
            //模拟延迟
            Thread.sleep(200);
            //5不存在 返回错误
            if (shop == null) {
                //将空值写入Redis
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6存在写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7释放互斥锁
            unlock(LOCK_SHOP_KEY + id);
        }
        log.info("shop的值为：{}", shop);
        return shop;
    }

    /**
     * 缓存穿透解决方法-逻辑时间
     */
    public Shop queryWithLogicalExpire(Long id) {

        //1从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3未命中，返回空
            return null;
        }
        //4 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1未过期，直接返回
            return shop;
        }
        //5.2已过期，需要缓存重建

        //6缓存重建

        //6.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY +id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取锁成功
        if (isLock){
            // 6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.4返回过期的商铺信息
        return shop;

    }


    /**
     * 缓存穿透解决方法
     */
    public Shop queryWithPassThrough(Long id) {
        {
            //1从Redis查询商铺缓存
            String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            //2判断是否存在
            if (StrUtil.isNotBlank(shopJson)) {
                //3存在直接返回
                Shop shop = JSONUtil.toBean(shopJson, Shop.class);
                return shop;
            }
            //判断命中的是否是控制
            if (shopJson != null) {
                //返回一个错误信息
                return null;
            }
            //4不存在根据id查询数据库
            Shop shop = getById(id);
            //5不存在 返回错误
            if (shop == null) {
                //将空值写入Redis
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6存在写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return shop;
        }
    }


    /**
     * 尝试获取锁
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * expireSeconds 过期时间
     */
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //2封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //导入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("id不能为空");
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }


    /**
     * 缓存穿透解决方法
     */
   /* public Result queryById(Long id) {
        //1从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3存在直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //判断命中的是否是控制
        if (shopJson != null) {
            //返回一个错误信息
            return Result.fail("信息存在");
        }
        //4不存在根据id查询数据库
        Shop shop = getById(id);
        //5不存在 返回错误
        if (shop == null) {
            //将空值写入Redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺不存在！");
        }
        //6存在写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }*/
}
