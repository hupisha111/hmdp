package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RedisLock implements Lock {

    private final String name;
    private StringRedisTemplate stringRedisTemplate;
    private static final String keyPrefix = "lock:";
    private static final String threadIdPrefix = UUID.randomUUID().toString();
    private static final DefaultRedisScript<Long> SCRIPT;
    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new ClassPathResource("UnLock.Lua"));
        SCRIPT.setResultType(Long.class);
    }

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
        String key = keyPrefix + name;
        String value = threadIdPrefix + threadId;
        final Boolean isLock = stringRedisTemplate.opsForValue().setIfAbsent(key, value, expireTime, TimeUnit.SECONDS);
        log.info("尝试往redis写入锁");
        return isLock != null ? isLock.booleanValue() : false;
    }

    @Override
    public void unlock() {
        //这里需要让锁的释放的操作有原子性.
        //先是考虑到释放锁的时候只能够释放自己的锁.(value中的threadid对应自己.)
        //但是呢,直接在java写对redis的操作的话,查询value和delete key保证不了原子性,可能查询完value之后,诶该线程阻塞了,然后刚好redis的锁过期了,然后别的线程新建了锁,然后该线程再执行删锁的操作,就误删锁了.
        //lua语句就能保证查询value和delete key,这两个操作中间插不了其他的redis操作.保证不会有中间有别的线程set锁,然后再被误删.
        //那么这里需要调用api,执行lua脚本.
        stringRedisTemplate.execute(SCRIPT,
                Collections.singletonList(keyPrefix + name),
                threadIdPrefix + Thread.currentThread().getId()
                );
    }
}
