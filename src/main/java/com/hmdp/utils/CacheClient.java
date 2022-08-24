package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/***
 #Create by LCK on 2022/8/19
 # 用法:
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    //线程池
    public static ExecutorService newCachedTreadPool() {
        return new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
    }

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value));
    }

    /**
     * @return R 结果
     * @Param[ keyPrefix：key前缀
     * id：查询id
     * type：结果类型
     * dbFallback：查询函数
     * time：过期时间
     * unit：过期时间单位
     * R 结果类型泛型
     * ID 时间类型泛型]
     */
    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1从Redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2判断是否存在
        if (StrUtil.isNotBlank(json)) {
            //3存在直接返回
            return JSONUtil.toBean(json, type);

        }
        //判断命中的是否是控制
        if (json != null) {
            //返回一个错误信息
            return null;
        }
        //4不存在根据id查询数据库
        R r = dbFallback.apply(id);
        //5不存在 返回错误
        if (r == null) {
            //将空值写入Redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //6存在写入redis
        this.set(key, r, time, unit);
        return r;

    }

    /**
     * @return R 结果
     * @Param[ keyPrefix：key前缀
     * id：查询id
     * type：结果类型
     * dbFallback：查询函数
     * time：过期时间
     * unit：过期时间单位
     * R 结果类型泛型
     * ID 时间类型泛型]
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback
            , Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1从Redis查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3未命中，返回空
            return null;
        }
        //4 命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，直接返回
            return r;
        }
        //5.2已过期，需要缓存重建

        //6缓存重建

        //6.1获取互斥锁
        String lockKey = keyPrefix + id;
        boolean isLock = tryLock(lockKey);
        //6.2判断是否获取锁成功
        if (isLock) {
            // 6.3成功，开启独立线程，实现缓存重建
            newCachedTreadPool().submit(() -> {
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入Redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //6.4返回过期的商铺信息
        return r;

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
}
