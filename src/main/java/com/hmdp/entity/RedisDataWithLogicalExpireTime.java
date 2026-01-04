package com.hmdp.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @author chen
 * @function 该类用于封装数据，并设置逻辑过期时间，用于解决缓存击穿问题
 * @date 2025/10/9
 */

@Data
public class RedisDataWithLogicalExpireTime {
    private LocalDateTime expireTime;
    private Object data;
}
