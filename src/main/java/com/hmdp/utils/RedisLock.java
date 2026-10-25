package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class RedisLock implements Lock {

    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String keyPrefix = "lock:";

    public RedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    /**
     * SETNX获取锁,注意锁要设计超时时间,防止死锁.
     * @return 返回是否获取锁.
     */
    @Override
    public Boolean tryLock(Long expireTime) {
        Long threadId = Thread.currentThread().getId();
        final Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(keyPrefix + name, threadId+"", expireTime, TimeUnit.SECONDS);
        return isLock != null ? isLock.booleanValue() : false;
    }

    @Override
    public void unlock() {
        stringRedisTemplate.delete(keyPrefix + name);
    }
}
