package com.hmdp.utils;

/**
 * @author chen
 * @function 生成订单号的全局唯一ID，工具类
 * @date 2025/10/12
 */
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisGenerateID {
    private static final long BEGIN_TIMESTAMP = 1640995200L; //2022-01-01 00:00:00
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //设计订单号由 时间戳前缀+redis自增序列号 组成,其中1位符号位,时间戳前缀31位，redis自增序列号32位,组成long的64位
    public long generateID(String keyPrefix) {
        //1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;
        //2。redis自增长，生成序列号    `
        Long number =stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + now.format(DateTimeFormatter.ofPattern("yyyy/MM")));
        //3.拼接全局唯一ID号
        return timeStamp << 32 | number;
    }
}
