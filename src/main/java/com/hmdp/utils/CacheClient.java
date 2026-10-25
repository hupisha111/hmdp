package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

//这是一个工具类.需要把cache,也就是针对于redis的一些数据操作封装成工具类和工具方法.
//1.把数据存进redis set:
//1.1set带有过期时间.
//1.2set但是逻辑外键
//2.从redis里面读数据
//2.1考虑缓存穿透
//2.2考虑逻辑外键的缓存击穿
@Component
@Slf4j
public class CacheClient
{
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService pool = Executors.newFixedThreadPool(10);
    //不对,还是得严格考虑入参到底是啥.要做的操作挺清楚的:就是存redis.入参应该是id
    //value需要传入一个函数吗,应该是需要的?因为value值需要数据库查询吧..?
    //还是应该看回到需求/
    public <T> void setCacheValue(String key, T value,  Long time , TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public <T> void setWithLogicalExpire(String key, T value,  Long time , TimeUnit unit){
        //应该是需要拼接一个RedisData
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <T,ID> T queryWithPassThrough(
            String keyPrefix, ID id,
            Class<T> type, Function<ID,T> dbFallBack,
            Long time, TimeUnit unit){
        //1.先去redis里面查询是否有对应数据
        String key = keyPrefix + id;
        String JsonT = stringRedisTemplate.opsForValue().get(key);
        //2.存在,返回
        if(StrUtil.isNotBlank(JsonT)){
            T t = JSONUtil.toBean(JsonT, type);
            log.info("redis中查询到了{}", t);
            return t;
        }
        //如果是为空值,返回不存在信息
        if(JsonT != null){
            return null;
        }
        //3.店铺不存在于redis,数据库查询
        //问题来了,你不知道查询哪一张表啊,所以查询数据库方法由调用者传参.
        T t = dbFallBack.apply(id);
        //4.不存在,返回报错
        if(t == null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //5.存在,redis写入
        this.setCacheValue(key,t,time,unit);
        log.info("信息写入redis");
        //6.返回
        return t;
    }

    public <T,ID> T queryWithLogicalExpire(
            String keyPrefix,ID id,
            Class<T> type, Function<ID,T> dbFallBack,
            String lockPrefix, Long time, TimeUnit unit
    ){
        //1.redis查询数据
        String key = keyPrefix + id;
        String Json = stringRedisTemplate.opsForValue().get(key);
        //判断数据是否存在
        if(StrUtil.isBlank(Json)){
            return null;
        }
        RedisData<T> redisData = JSONUtil.toBean(Json, RedisData.class);
        T t = JSONUtil.toBean((JSONObject) redisData.getData(),type);
        //2.比较判断过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())) {
            //3.未过期,直接返回redisData里面的data
            return t;
        }
        //4.已过期,尝试获取锁
        String lockKey = lockPrefix + id;
        Boolean isLock = tryLock(lockKey, 10L, TimeUnit.SECONDS);
        if(!isLock) {
            //5.获取锁失败,直接返回
            return t;
        }
        //6.获取锁成功,再次redis查询过期时间
        Json = stringRedisTemplate.opsForValue().get(key);
        redisData = JSONUtil.toBean(Json, RedisData.class);

        expireTime = redisData.getExpireTime();
        //7.未过期返回
        if(expireTime.isAfter(LocalDateTime.now())) {
            //未过期,直接返回
            return t;
        }
        //8.过期,线程池调用线程去数据库查询数据,自己返回信息
        pool.submit(() -> {
            try {
                Thread.sleep(200);
                T newT = dbFallBack.apply(id);
                if(newT == null){
                    this.setWithLogicalExpire(key, null, time, unit);
                }
                this.setWithLogicalExpire(key, newT,time,unit);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                //9.释放锁
                unLock(lockKey);
            }
        });
        return t;
    }

    private Boolean tryLock(String lockKey, Long time, TimeUnit unit) {
        //利用redis的setNx尝试拿锁
        Boolean lock = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", time, unit);
        return BooleanUtil.isTrue(lock);
    }

    private void unLock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }
}
