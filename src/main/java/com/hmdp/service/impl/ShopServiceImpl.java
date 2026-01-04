package com.hmdp.service.impl;

import ch.qos.logback.core.util.TimeUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.RedisDataWithLogicalExpireTime;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheOpsUtils;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import io.lettuce.core.api.async.RedisGeoAsyncCommands;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author
 * @提醒：注释掉的方法体已经封装在CacheOpsUtils类中，这里不再重复写
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate  stringRedisTemplate;
    @Resource
    private CacheOpsUtils cacheOpsUtils;

    //查询：根据店铺id查询店铺信息
    //查询存在一些问题：缓存穿透、缓存击穿、缓存雪崩
    @Override
    public Result queryById(Long id) {
        Shop shop = null;

        //没有封装工具类之前
//        //解决缓存穿透
//        shop = penetration(id);
//        //解决缓存击穿写：互斥锁
//        shop = breakdowmMutex(id);
//        //解决缓存击穿：逻辑过期时间解决
//        shop = breakdowmLogicalExpireTime(id);

        //封装工具类之后
//        //解决缓存穿透
//        shop = cacheOpsUtils.penetration(RedisConstants.CACHE_SHOP_KEY,id, Shop.class,idParam-> getById(idParam),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        //解决缓存击穿：互斥锁解决
//        shop = cacheOpsUtils.breakdowmMutex(RedisConstants.CACHE_SHOP_KEY,id, Shop.class,idParam-> getById(idParam),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //解决缓存击穿：逻辑过期时间解决
        shop = cacheOpsUtils.breakdowmLogicalExpireTime(RedisConstants.CACHE_SHOP_KEY,id, Shop.class,idParam-> getById(idParam),20L, TimeUnit.SECONDS);
        //判断返回值是否为null，是就返回“商铺不存在”
        if (shop == null){
            return Result.fail("商铺不存在");
        }
        //返回商铺信息
        return Result.ok(shop);
    }

//    //根据店铺id查询店铺信息：解决缓存穿透问题
//    //缓存穿透解决办法:当发生穿透时即查询不存在的数据时，向缓存中存入空串——“”，来解决缓存穿透问题，
//    //注意：本函数的返回值是null，上一级函数接收到null后，统一返回用户“店铺id不存在”。
//    private Shop penetration(Long id) {
//        //根据id查询缓存
//        String shopStr = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
//        //判断是否存在，存在就转换成shop对象然后返回
//        if (!StrUtil.isBlank(shopStr)) { //命中真实数据
//            Shop shop = JSONUtil.toBean(shopStr, Shop.class);
//            return shop;
//        }//没有命中真实数据那么shopStr=null或""
//        if (shopStr != null) { //TODO：解决缓存穿透：命中“”，这样就不会总是消耗时间和性能去查询数据库中不存在的数据，避免缓存穿透
//            return null;
//        }//后续代码执行的是，缓存没有命中，去查询数据库的操作
//        //缓存没有就查询数据库,Mybatis plus
//        Shop shop = getById(id);
//        //判断是否存在，不存在就返回“商铺不存在”
//        if (shop == null) {
//            //TODO：解决缓存穿透：存入空串——“”，来解决缓存穿透问题，发生缓存穿透会不断浪费时间在查询不存在的数据上并且每次都打到了数据库
//            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
//            return null;
//        }
//        //存在就缓存到redis
//        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        //返回数据
//        return shop;
//    }

//    //创建线程池（使用Executors工具类进行创建的）：用于开启独立线程，重构热点key的缓存数据
//    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
//
//    //根据店铺id查询店铺信息：解决缓存击穿问题：设置逻辑过期时间（也需要用到互斥锁）
//    //测试方法时，需要预先把热点key提前加入缓存（因为没有本项目没有后台管理系统，这一步操作是模拟后台商家加入热点key的过程），然后模拟逻辑时间过期，来重构缓存（缓存中不存在即不是热点key，既然是针对热点key那么缓存存肯定有key）
//    private Shop breakdowmLogicalExpireTime(Long id) {
//        String key = RedisConstants.CACHE_SHOP_KEY + id;
//        //1.根据id查询缓存
//        String shopStr = stringRedisTemplate.opsForValue().get(key);
//        //2.断是否存在
//        if (StrUtil.isBlank(shopStr)) { //3.不存在，即不是热点key
//            return null;
//        }//命中则执行后续操作
//        //4.缓存命中，先把json字符串反序列化成对象
//        RedisDataWithLogicalExpireTime shopWithExprTime = JSONUtil.toBean(shopStr, RedisDataWithLogicalExpireTime.class);
//        Shop shop = JSONUtil.toBean((JSONObject) shopWithExprTime.getData(), Shop.class);
//        LocalDateTime expireTime = shopWithExprTime.getExpireTime();
//        //TODO
//        //5.判断逻辑过期时间
//        if (expireTime.isAfter(LocalDateTime.now())) {
//            //6.没过期就直接返回商铺信息
//            return shop;
//        }
//        //7.过期就需要重构缓存
//        //8.获取互斥锁
//        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
//        boolean flag = tryLock(lockKey);
//        //9.判断锁是否获取成功
//        if (flag){
//            //10.成功就开启独立线程，去执行缓存重构逻辑
//            CACHE_REBUILD_EXECUTOR.submit(
//                    () -> {
//                        //查数据库+加入redis缓存
//                        try {
//                            saveRedisDataWithLogicalExpireTime(id, 20L); //这里便于测试我们设置成20秒后过期
//                        } catch (Exception e) {
//                            throw new RuntimeException(e);
//                        } finally {
//                            unLock(lockKey);
//                        }
//                    }
//            );
//        }
//        //11.失败，或者成功执行缓存重构，都要返回过期的商铺信息
//        return shop;
//    }
//
    //根据店铺id查询店铺信息：解决缓存击穿问题：使用互斥锁解决
    private Shop breakdowmMutex(Long id) {
        Shop shop = null;
        //1.根据id查询缓存
        String shopStr = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //2.判断是否存在，存在就转换成shop对象然后返回
        if (!StrUtil.isBlank(shopStr)) { //3.命中真实数据
            shop = JSONUtil.toBean(shopStr, Shop.class);
            return shop;
        }
        //4。没有命中真实数据那么可能是shopStr = null或者是""，这里判断是否吗，命中“”
        if (shopStr != null) { //解决缓存穿透：命中“”，说明数据在数据库本身就不存在，缓解了缓存穿透问题
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
                return breakdowmMutex(id);
            }
            //5.4成功，根据id查询数据库
            shop = getById(id);
            Thread.sleep(200);  //模拟缓存复杂构建的耗时
            //5.5判断是否存在，不存在就返回“商铺不存在”
            if (shop == null) {
                //解决缓存穿透：存入空串——“”，来解决缓存穿透问题，发生缓存穿透会不断浪费时间在查询不存在的数据上并且每次都打到了数据库
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //5.6存在就缓存到redis
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //5.7释放锁
            unLock(lockKey);
        }
        //6.返回数据
        return shop;
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

    //（用于Test类中）这个方法编写出来主要是在Test类中测试使用，用于模拟后台管理系统预备添加热点key
    //用于重构热点key，设置逻辑过期时间，并存入redis(热点key默认在数据库中存在，所以没有判断shop)
    public void saveRedisDataWithLogicalExpireTime(Long id, Long expireTimeSeconds) throws InterruptedException {
        //模拟重构延时
        Thread.sleep(100);
        //1.获取数据库中的店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisDataWithLogicalExpireTime redisDataWithLogicalExpireTime = new RedisDataWithLogicalExpireTime();
        redisDataWithLogicalExpireTime.setExpireTime(LocalDateTime.now().plusSeconds(expireTimeSeconds));
        redisDataWithLogicalExpireTime.setData(shop);
        //3.写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisDataWithLogicalExpireTime));
    }

    // 更新：更新数据库同时删除缓存，而且开启事务控制；
    // 缓存更新策略：主动更新策略+超时剔除。
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long shopId = shop.getId();
        if (shopId == null) {
            return Result.fail("店铺id不能为空");
        }
        //修改数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY + shopId);
        //返回结果
        return Result.ok();
    }

    //根据商铺类型分页查询商铺信息（默认按照距离排序）
    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1 判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询 (mybatisplus有传统分页操作)
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        //2 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //3 查询redis，按照距离排序，分页。  结果：shopId、distance
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> searchResult = stringRedisTemplate.opsForGeo()   // GEOSEARCH key BYLONLAT 5000 BYRADIUS x y WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),  //圆心
                        new Distance(5000),  //半径
                        //结果带距离， 获取角标0-end的所有数据，后续再截断
                        RedisGeoCommands.GeoRadiusCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );

        //4 从搜索结果中解析出id
        //4.1判断结果是否为空
        if (searchResult == null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = searchResult.getContent();
        if (list.size() <= from) {
            //没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        //4.2 截取from-end的部分，并且循环取出商铺id和对应的distance,放在集合中
        ArrayList<Long> shopIds = new ArrayList<>(list.size());
        HashMap<Long, Distance> distanceHashMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            //获取商铺id
            String shopIdStr = result.getContent().getName();
            Long shopId = Long.valueOf(shopIdStr);
            shopIds.add(shopId);
            //获取distance
            Distance distance = result.getDistance();
            distanceHashMap.put(shopId, distance);
        });

        //5 根据id查询shop
        String shopIdsStr = StrUtil.join(",", shopIds);
        List<Shop> shops = query().in("id", shopIds).last("ORDER BY FIELD(id, " + shopIdsStr + ")").list();

        //6 把距离封装到shop的distance属性中,返回给前端
        for (Shop shop : shops){
            shop.setDistance(distanceHashMap.get(shop.getId()).getValue());  //getValue()作用是把Distance对象转换成double
        }

        //7 返回
        return Result.ok(shops);
    }
}
