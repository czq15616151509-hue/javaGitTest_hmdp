package com.hmdp.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @function 使用lua脚本和redis自定义分布式锁
 * @date 2025/10/20
 */
public class SimpleRedisLock implements ILock{
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString()+"-"; //不同服务器线程id可能相同，所以加个id前缀
    private static DefaultRedisScript<Long> UNLOCK_SCRIPT = null;  //指向lua脚本文件的变量

    //类加载就初始化加载lua脚本
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT .setLocation(new ClassPathResource("src/main/resources/unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    @Override
    public boolean tryLock(long timeoutSeconds) {
        //获取当前线程id
        String threadId = ID_PREFIX + Thread.currentThread().getId();  //既要区分不同的服务器还要区分同服务器不同的线程
        //获取锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId , timeoutSeconds, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag); //boolean
    }
    @Override
    public void unLock() {
          //用lua脚本代替下面的方案
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name), ID_PREFIX + Thread.currentThread().getId());

//        //获取当前线程id
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //获取锁中的线程id
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //判断锁是否属于当前线程
//        if (!threadId.equals(id)){
//            //释放锁(是自己的才释放以免，错误释放其他正在阻塞的线程的锁)
//            stringRedisTemplate.delete(KEY_PREFIX + name);
//        }
    }
}
