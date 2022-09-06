package com.hmdp.utils;

/***
 #Create by LCK on 2022/8/25
 # 用法: 锁
 */
public interface ILock {
    /**
    * 尝试获取锁
    *@Param [timeoutSec] 锁持有的超时时间，过期后自动释放
    *@return boolean ture，代表获取锁成功， 反之
    */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
