package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisGenerateID;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private IVoucherService voucherService; //操作通用卷(普通卷以及优惠卷)表，卷的基本信息
    @Resource
    private ISeckillVoucherService seckillVoucherService;  //操作优惠券表，优惠券的秒杀信息

    @Resource
    private RedisGenerateID redisGenerateID; //生成订单id
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;  //分布式锁组件
    private static DefaultRedisScript<Long> SECKILL_SCRIPT;  //封装lua秒杀脚本
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("classpath:seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);  //脚本返回结果为long
    }

    //JDK内部的阻塞队列(因为这种方案存在内存限制和数据安全问题，我们后续使用消息队列)
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    //线程池(只有一个线程)
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //类初始化时开启任务，不断从队列中获取订单信息，进行下单
    @PostConstruct
    public void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    //任务(子线程执行)（Stream消息队列方案）
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while(true){
                try {
                    //1.获取队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order >
                    List<MapRecord<String, Object, Object>> readList = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("streams.order", ReadOffset.lastConsumed())  // >
                    );
                    //2.判断消息获取是否成功
                    if (readList == null || readList.isEmpty()) continue;
                    //3.解析消息中的订单数据
                    MapRecord<String, Object, Object> record = readList.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.获取成功，则下单
                    handleVoucherOrder(voucherOrder);
                    //5.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge("streams.order", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理消息队列订单异常", e);
                    handlePendinglist();
                }
            }
        }

        //消息队列处理发生异常时的方案
        private void handlePendinglist() {
            while(true){
                try {
                    //1.获取pendinglist中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS streams.order 0
                    List<MapRecord<String, Object, Object>> readList = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("streams.order", ReadOffset.from("0"))  // >
                    );
                    //2.判断消息获取是否成功
                    if (readList == null || readList.isEmpty()) {
                        //获取失败，说明pendinglist为空，结束循环
                        break;
                    }
                    //3.解析消息中的订单数据
                    MapRecord<String, Object, Object> record = readList.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4.获取成功，则下单
                    handleVoucherOrder(voucherOrder);
                    //5.ACK确认
                    stringRedisTemplate.opsForStream().acknowledge("streams.order", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pendinglist订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

//    //任务(子线程执行)（jdk阻塞队列方案）
//    private class VoucherOrderHandler implements Runnable {
//        @Override
//        public void run() {
//            while(true){
//                try {
//                    //1.获取队列中的订单信息
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    //2.创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常", e);
//                }
//            }
//        }
//    }

    //代理对象(主线程提前取出来给子线程使用）
    private IVoucherOrderService proxy;
    //验证秒杀资格（异步秒杀第一步）
    public Result seckillVoucherAsync(Long voucherId){
        Long userId= UserHolder.getUser().getId();
        Long orderId = redisGenerateID.generateID("order");
        //1 执行lua脚本(判断秒杀资格，并插入消息队列)
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(), //key
                voucherId.toString(), userId.toString(), String.valueOf(orderId) //ARGV
        );

        //2 判断结果是为0
        if (result != 0) {
            //2.1 不为0，代表没有购买资格
            return Result.fail(result == 1 ? "库存不足" : "不能重复下单");
        }

        //3 获取代理对象（保证成功进行管理事务）(因为后续子线程需要用到代理，但是子线程获取不到，所以先在主线程中提前取出来)
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //4 返回订单id
        return Result.ok(orderId);
    }

    //处理已经完成秒杀资格通过的订单（异步秒杀第二步）(第一步已经使用lua判断过秒杀资格了，因为是原子性判断操作，后面可以不需要上锁了)
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //使用代理对象来调用方法，完成事务管理(主线程已经提前获取代理对象)
        proxy.subStockAndCreateOrderAsyncToMysql(voucherOrder); //因为上一级方法不是spring自动进行事务管理的，所以此方法可能会事务管理失效；事务管理是由代理对象完成的，所以要开启以及执行事务（进行事务管理），需要先得到代理对象（必须先暴漏代理对象），来控制事务的完成。
    }
    @Transactional
    public void subStockAndCreateOrderAsyncToMysql(VoucherOrder voucherOrder) {
        //扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") //set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId())  //where id = ?
                .update();
        //把订单写入数据库订单表
        save(voucherOrder);
    }



    /** 根据优惠卷id生成用户订单，实现优惠卷秒杀，而且要实现一人一单功能 (里面存在多线程安全问题，认真学习里面的高并发问题的解决方案)
     *  一人一单逻辑: 查询订单表是否存在(用户id，订单id)的订单，如果存在则返回错误“已重复购买”，不存在则进行扣减创建订单
     * （黄牛可能用脚本使用一个号购买很多个优惠卷）
     */
    /** 解决高并发下修改所造成的问题1：多个线程查询到stock=1，并且都进行了扣减库存
     *  解决方案1：使用悲观锁(认为并发问题一定会发生)，线程串行执行查询和修改操作，在查询之前获取锁，修改完成之后释放锁，但是性能差。
     *  解决方案2(选择)：使用乐观锁(认为并发问题不一定会发生)，本质上不使用锁，它在修改的时候判断是否有其他线程对stock进行了修改，如果没有则进行修改，否则不进行修改。
     *                关键点在于如何去判断是否有其他线程对stock进行了修改呢？只需要在自己修改表时增加一个修改条件即可："stock"=查询时得到的stock ，如果自己准备
     *                修改时，stock已经被其他线程修改过，那么将找不到满足修改条件的这条记录，则不修改。这种判断可以保证多线程对公共资源的互斥修改。
     *                但是这里只有当stock=1时才需要多线程互斥修改，而当stock>1时需要的是并行修改。所以增加修改条件可以这样写："stock" > 0,表示每次修改时同时判
     *                断修改条件stock>0，stock>0表示库存充足，stock<=0表示库存不足。
     *
     *  解决高并发下修改所造成的问题2：多线程（同一个用户）都查询到count=0，那么就会导致一人多单的情况
     *  解决方案：使用悲观锁，在查询count前加锁，在生成订单后解锁，这样当一个线程（同一用户）查询到count=0时，其他线程（同一用户）无法查询需要等待锁，从而避免同一个人多单。
     */
//    @Override   //非异步，非消息队列方案
//    public Result seckillVoucher(Long voucherId) {  //返回参数是订单
//        //1、查询优惠券 (同时获取了查询时的stock)
//        SeckillVoucher Voucher = seckillVoucherService.getById(voucherId);
//        //2、判断是否秒杀时间是否开始以及是否结束
//        if (Voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始");
//        }
//        if (Voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束");
//        }
//        //3、判断剩余库存是否充足
//        if (Voucher.getStock() < 1){
//            return Result.fail("库存不足");
//        }
//
//        //后续：扣减库存和创建订单
//        Long userId= UserHolder.getUser().getId();
//        //创建锁对象,使用redissonClient代替自定义分布式锁
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
////        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        //获取锁
//        boolean isLock = lock.tryLock();
//        //判断锁是否获取成功
//        if (!isLock){
//            //获取锁失败，返回错误
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            // 获取代理对象（保证成功进行管理事务）
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            //使用代理对象来调用方法，完成事务管理
//            return proxy.subStockAndCreateOrder(voucherId); //因为上一级方法不是spring自动进行事务管理的，所以此方法可能会事务管理失效；事务管理是由代理对象完成的，所以要开启以及执行事务（进行事务管理），需要先得到代理对象（必须先暴漏代理对象），来控制事务的完成。
//        }finally {
//            lock.unlock();
//        }
//    }


//    //扣减库存和创建订单
//    @Transactional
//    public Result subStockAndCreateOrder(Long voucherId) {
//        //4、一人一单
//        Long userId= UserHolder.getUser().getId();
//        //4.1根据用户id和订单id查询订单表，查询返回count
//        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        //4.2判断用户是否已经购买过该优惠券，count>0？
//        if (count > 0){
//            return Result.fail("你已重复购买");
//        }
//
//        //5、扣减库存 (存在高并发问题)
//        boolean success = seckillVoucherService.update()
//                .setSql("stock = stock - 1") //set stock = stock - 1
//                .eq("voucher_id", voucherId).gt("stock", 0)  //where id = ? and stock > 0
//                .update();
//        if (!success){
//            //扣减失败
//            return Result.fail("库存不足");
//        }
//        //6、创建订单 (先要得到：订单id、用户id、秒杀卷id)
//        VoucherOrder order = new VoucherOrder();
//        long l = redisGenerateID.generateID("order"); //生成订单id
//        Long id = UserHolder.getUser().getId(); //用户登录时已经把用户信息保存到了线程独立空间
//        order.setVoucherId(voucherId);
//        order.setId(l);
//        order.setUserId(id);
//        //7.写入数据库订单表
//        save(order);
//        //8、返回订单
//        return Result.ok(l);
//
////        //存在一个问题：synchronized先释放锁，Transactional才提交事务（count才修改），如果在释放锁之后到事务提交前的时间间隔，其他线程拿到锁，查询到count=0，那么就会导致一人多单。所以我们需要在事务提交之后再释放锁。
////        synchronized (userId.toString().intern()) { //同一个用户的多线程，只有一个线程可以执行，不同用户的多线程可以并行执行
////            //4.1根据用户id和订单id查询订单表，查询返回count
////            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
////            //4.2判断用户是否已经购买过该优惠券，count>0？
////            if (count > 0){
////                return Result.fail("你已重复购买");
////            }
////
////            //5、扣减库存 (存在高并发问题)
////            boolean success = seckillVoucherService.update()
////                    .setSql("stock = stock - 1") //set stock = stock - 1
////                    .eq("voucher_id", voucherId).gt("stock", 0)  //where id = ? and stock > 0
////                    .update();
////            if (!success){
////                //扣减失败
////                return Result.fail("库存不足");
////            }
////            //6、创建订单 (先要得到：订单id、用户id、秒杀卷id)
////            VoucherOrder order = new VoucherOrder();
////            long l = redisGenerateID.generateID("order"); //生成订单id
////            Long id = UserHolder.getUser().getId(); //用户登录时已经把用户信息保存到了线程独立空间
////            order.setVoucherId(voucherId);
////            order.setId(l);
////            order.setUserId(id);
////            //7.写入数据库订单表
////            save(order);
////            //8、返回订单
////            return Result.ok(l);
////        }
//    }
}
