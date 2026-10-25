package com.hmdp.utils;

public interface Lock {
    /**
     * 获取锁
     * @return 返回是否获取到锁,注意拆箱的问题
     */
    public Boolean tryLock(Long expireTime);

    /**
     * 释放锁.
     */
    public void unlock();
}
