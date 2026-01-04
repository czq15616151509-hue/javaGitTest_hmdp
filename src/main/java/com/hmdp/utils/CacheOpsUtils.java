package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.RedisDataWithLogicalExpireTime;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @说明： 非static工具类
 * @function 是一个针对于Redis缓存操作的工具类：里面包含适用于本项目进行存、取redis数据的方法，以及包含针对于解决redis缓存击穿、穿透、雪崩问题的方法
 * @date 2025/10/11
 */
@Slf4j
@Component
public class CacheOpsUtils {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //把对象存入缓存,并设置redis的TTL
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    //把对象存入缓存,并设置过期的逻辑时间而不是redis的TTL
    public void setWithLogicalExpireTime(String key, Object value,Long time, TimeUnit unit){
        //封装成带有逻辑过期时间的对象类型，再存入redis
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time))); //转换成秒
        redisData.setData(value);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    //解决根据id查询信息时，存在的缓存穿透问题
    //缓存穿透解决办法:当发生穿透时即查询不存在的数据时，向缓存中存入空串——“”，来解决缓存穿透问题，
    //注意：本函数的返回值是null，上一级函数接收到null后，统一返回用户“查询结果不存在”。
    public <R,ID> R penetration(String keyPrefixID, ID id, Class<R> type, Function<ID,R> queryDB, Long time, TimeUnit unit) {
        //根据id查询缓存
        String jsonStr = stringRedisTemplate.opsForValue().get(keyPrefixID + id);
        //判断是否存在，存在就转换成对象然后返回
        if (!StrUtil.isBlank(jsonStr)) { //命中真实数据
            return JSONUtil.toBean(jsonStr, type);
        }//没有命中真实数据，那么可能是null或""
        if (jsonStr != null) { //TODO：解决缓存穿透：命中“”，这样就不会总是消耗时间和性能去查询数据库中不存在的数据，避免缓存穿透
            return null;
        }//后续代码执行的是，缓存没有命中，去查询数据库的操作
        //缓存没有就查询数据库,Mybatis plus
        R r = queryDB.apply(id);
        //判断是否存在，不存在就返回“查询信息不存在”
        if (r == null) {
            //TODO：解决缓存穿透：存入空串——“”，来解决缓存穿透问题，发生缓存穿透会不断浪费时间在查询不存在的数据上并且每次都打到了数据库
            stringRedisTemplate.opsForValue().set(keyPrefixID + id, "",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在就缓存到redis (使用该工具类已经实现的存储方法来写入redis)
        this.set(keyPrefixID+id, r, time, unit);
        //返回数据
        return r;
    }

    //创建线程池（使用Executors工具类进行创建的）：用于开启独立线程，重构热点key的缓存数据
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //解决根据id查询信息时，存在的缓存击穿问题：设置逻辑过期时间（也需要用到互斥锁）
    //测试方法时，需要预先把热点key提前加入缓存（因为没有本项目没有后台管理系统，这一步操作是模拟后台商家加入热点key的过程），然后模拟逻辑时间过期，来重构缓存（缓存中不存在即不是热点key，既然是针对热点key那么缓存存肯定有key）
    public <R,ID> R breakdowmLogicalExpireTime(String keyPrefixID, ID id, Class<R> type, Function<ID,R> queryDB, Long time, TimeUnit unit) {
        String key = keyPrefixID + id;
        //1.根据id查询缓存
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        //2.断是否存在
        if (StrUtil.isBlank(jsonStr)) { //3.不存在，即不是热点key
            return null;
        }//命中则执行后续操作
        //4.缓存命中，先把json字符串反序列化成对象
        RedisDataWithLogicalExpireTime dataWithExprTime = JSONUtil.toBean(jsonStr, RedisDataWithLogicalExpireTime.class);
        R r = JSONUtil.toBean((JSONObject) dataWithExprTime.getData(), type);
        LocalDateTime expireTime = dataWithExprTime.getExpireTime();
        //TODO
        //5.判断逻辑过期时间
        if (expireTime.isAfter(LocalDateTime.now())) {
            //6.没过期就直接返回商铺信息
            return r;
        }
        //7.过期就需要重构缓存
        //8.获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lockKey);
//        //9.判断锁是否获取成功
//        if (flag){
//            //10.成功就开启独立线程，去执行缓存重构逻辑
//            CACHE_REBUILD_EXECUTOR.submit(new Runnable() {
//                @Override
//                public void run() {
//                    //查数据库+加入redis缓存
//                    try {
//                        System.out.println("开启一个独立线程进行缓存重构！");
//                        //查询数据库(这条数据库查询一直没有执行成功不知道什么愿意：跳过)
//                        R rNeW = queryDB.apply(id);
//                        //缓存到redis
//                        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(rNeW), time, unit);
//                    } catch (Exception e) {
//                        throw new RuntimeException(e);
//                    } finally {
//                        unLock(lockKey);
//                    }
//                }
//            });
//        }
//        //11.失败，或者成功执行缓存重构，都要返回过期的商铺信息
//        return r;
        //TODO 自己的新思路：没有获取到锁的线程全部返回旧数据，缓存重建由获取到锁的这一个线程去执行即可（不再创建开启新线程），上述黑马给的方案存在一些问题没有解决
        //9.判断锁是否获取成功
        if (!flag){
            //没成功，则返回旧数据
            return r;
        }
        //10.成功，重构数据
        R rNeW = null;
        try {
            //10.1.查询数据库
            rNeW = queryDB.apply(id);
            //10.2缓存到redis
            this.setWithLogicalExpireTime(key, rNeW, time, unit);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            unLock(lockKey);
        }
        return rNeW;
    }

    //TODO 这个工具类方法是自己写的
    //解决根据id查询信息时，解决缓存击穿问题：使用互斥锁解决
    public <R,ID> R breakdowmMutex(String keyPrefixID,ID id,Class<R> type,Function<ID,R> queryDB, Long time, TimeUnit unit) {
        R r = null;
        //1.根据id查询缓存
        String jsonStr = stringRedisTemplate.opsForValue().get(keyPrefixID + id);
        //2.判断是否存在，存在就转换成shop对象然后返回
        if (!StrUtil.isBlank(jsonStr)) { //3.命中真实数据
            r = JSONUtil.toBean(jsonStr, type);
            return r;
        }
        //4。没有命中真实数据那么可能是 null或者是""，这里判断是否吗，命中“”
        if (jsonStr != null) { //解决缓存穿透：命中“”，说明数据在数据库本身就不存在，缓解了缓存穿透问题
            return null;
        }
        //TODO：解决缓存击穿：
        //5.查询数据库
        String lockKey = null;
        try {
            //5.1获取锁
            lockKey = RedisConstants.LOCK_SHOP_KEY + id;
            boolean lock = tryLock(lockKey);
            //5.2判断锁是否获取成功
            if (!lock) {
                //5.3失败，就休眠一段时间，并重试
                Thread.sleep(50);
                return breakdowmMutex(keyPrefixID,id, type, queryDB, time, unit);
            }
            //5.4成功，根据id查询数据库
            r = queryDB.apply(id);
            Thread.sleep(200);  //模拟缓存复杂构建的耗时
            //5.5判断是否存在，不存在就返回“商铺不存在”
            if (r == null) {
                //解决缓存穿透：存入空串——“”，来解决缓存穿透问题，发生缓存穿透会不断浪费时间在查询不存在的数据上并且每次都打到了数据库
                this.set(keyPrefixID + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //5.6存在就缓存到redis
            this.set(keyPrefixID+id, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //5.7释放锁
            unLock(lockKey);
        }
        //6.返回数据
        return r;
    }

    //获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    //释放锁
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }
}

