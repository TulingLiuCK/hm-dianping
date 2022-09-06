package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;


import java.util.Collections;
import java.util.concurrent.TimeUnit;

/***
 #Create by LCK on 2022/8/25
 # 用法:
 */
public class SimpleRedisLock implements ILock {


    private String name;
    private static final String KEY_PEIFIX = "lock:";
    private static final String ID_PEIFIX = UUID.randomUUID().toString(true) + "-";

    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取当前线程标识
        String threadId = ID_PEIFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PEIFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        //返回的是Boolean，如果直接返回，自动拆箱，会有风险
        return Boolean.TRUE.equals(success);
    }

    //    @Override
//    public void unlock() {
//        //获取线程标识
//        String threadId = ID_PEIFIX+Thread.currentThread().getId();
//        //获取锁中的标识
//        String id = stringRedisTemplate.opsForValue().get(KEY_PEIFIX + name);
//        //判断标识是否一致
//        if (threadId.equals(id)){
//            //释放锁
//            stringRedisTemplate.delete(KEY_PEIFIX + name);
//        }
//    }
    //lua脚本版本
    @Override
    public void unlock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PEIFIX + name),
                ID_PEIFIX+Thread.currentThread().getId());
    }
}
