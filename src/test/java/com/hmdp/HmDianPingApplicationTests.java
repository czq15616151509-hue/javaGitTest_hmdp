package com.hmdp;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import com.hmdp.utils.CacheOpsUtils;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisGenerateID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {
    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Resource
    private VoucherOrderServiceImpl voucherOrderService;
    @Resource
    private RedisGenerateID redisGenerateID;
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheOpsUtils cacheOpsUtils;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //UV统计：统计访问量（不算重复用户）
    //思路：如果把百万用户信息直接存放在数据库或者redis中，会造成很大的内存压力，所以这里使用一种redis中的数据结构HyperLogLog（占用内存超低），概率估算访问量（误差小到可忽略）。
    @Test
    public void UVStatistics(){
        //准备数组，装用户数据
        String[] users = new String[1000];

        //模拟用户访问，把用户放入HyperLogLog结构
        for (int i = 0; i < 1000; i++){  //装1000次，分一千次送进HyperLogLog
            for (int j = i * 1000; j < (i+1) * 1000; j++){
                users[i] = "user_" + j;
            }
            stringRedisTemplate.opsForHyperLogLog().add(RedisConstants.UV_COUNT_KEY, users);
        }
        //统计访问量
        Long size = stringRedisTemplate.opsForHyperLogLog().size(RedisConstants.UV_COUNT_KEY);
        System.out.println("size = " + size);
    }

    //把所以店铺按照类型分类添加到Redis中 (我们之后浏览店铺有一个功能是按照距离排序，需要同类店铺之间铺进行距离比较然后进行排序，我们把同类型店铺用sortedSet结构储存起来)
    @Test
    public void ShopTypeToRedis(){
        // 1. 查询控制信息
        List<Shop> list = shopService.list();

        // 2. 把控制分组，按照typeId分组，typeId一致的找到一个集合
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        // 3. 分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1. 获取类型id
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;

            // 3.2. 获取同类型的店铺的集合
            List<Shop> value_shops = entry.getValue();

            // 3.3. 写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value_shops) {
                 stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());
            }
        }
    }

    @Test
    public void testSaveRedisData() throws InterruptedException {  //秒杀优惠卷
//        Result result_OrderId = voucherOrderService.seckillVoucher(10L);
    }
    @Test
    public void testSaveRedisDataWithExpire() throws InterruptedException {
        shopService.saveRedisDataWithLogicalExpireTime(1L, 20L);
    }

    @Test
    public void testLogicalExpire() throws InterruptedException {  //预备添加热点key
        Shop shop = shopService.getById(1L);
        cacheOpsUtils.setWithLogicalExpireTime(RedisConstants.CACHE_SHOP_KEY + shop.getId(),shop,20L, TimeUnit.SECONDS);
    }

    @Test
    public void testLogicalExpire11() throws InterruptedException {  //缓存击穿
        Shop shop = null;
        shop = cacheOpsUtils.breakdowmLogicalExpireTime(RedisConstants.CACHE_SHOP_KEY,1, Shop.class,idParam-> shopService.getById(idParam),20L, TimeUnit.SECONDS);
    }

    @Test
    public void testLogicalExpire11222() throws InterruptedException {  //缓存击穿
        Shop shop = null;
        shop = cacheOpsUtils.breakdowmMutex(RedisConstants.CACHE_SHOP_KEY,2, Shop.class,idParam-> shopService.getById(idParam),20L, TimeUnit.SECONDS);
    }

    @Test
    public void testGenerateID() throws InterruptedException {  //全局id生成器
        CountDownLatch latch = new CountDownLatch(300);

        Runnable task = new Runnable() {  //每个线程生成一百个订单id(模拟每个用户下一百单)
            @Override
            public void run() {
                for (int i = 0; i < 100; i++) {
                    System.out.println(redisGenerateID.generateID("order"));
                }
                latch.countDown();
            }
        };

        for(int i = 0; i < 300; i++){
            es.submit(task);
        }

        latch.await();
    }
}
