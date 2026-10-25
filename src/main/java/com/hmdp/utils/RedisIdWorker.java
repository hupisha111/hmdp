package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // 自定义时间起点：2022-01-01 00:00:00 UTC
    private static final long BEGIN_TIMESTAMP =
            LocalDate.of(2022, 1, 1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    // 低 32 位存当天的自增序列号
    private static final int COUNT_BITS = 32;
    private static final long MAX_COUNT = (1L << COUNT_BITS) - 1;
    private static final long MAX_TIMESTAMP = Long.MAX_VALUE >>> COUNT_BITS;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy:MM:dd");

    public long nextId(String keyPrefix){
        //利用Redis为分布式的服务器和java项目生成全局性的唯一id.
        //1.生成时间戳:现在的时间减去一个固定的开始时间.
        Instant now = Instant.now();
        long timeStamp = now.getEpochSecond() - BEGIN_TIMESTAMP;
        if (timeStamp < 0 || timeStamp > MAX_TIMESTAMP) {
            throw new IllegalStateException("当前时间超出 RedisIdWorker 时间戳可用范围");
        }

        //2.生成序列号,每一天作为键,对应一个自增的序列号
        String date = DATE_FORMATTER.format(now.atZone(ZoneOffset.UTC).toLocalDate());
        String key = "icr:" + keyPrefix +":" + date;
        Long increment = stringRedisTemplate.opsForValue().increment(key);
        if (increment == null || increment <= 0 || increment > MAX_COUNT) {
            throw new IllegalStateException("Redis ID 序列号无效或当天已达到上限: " + key);
        }

        //3.拼接时间戳
        return (timeStamp << COUNT_BITS) | increment;
    }
}

