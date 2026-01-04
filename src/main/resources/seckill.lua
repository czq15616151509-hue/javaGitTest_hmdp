--1.1 优惠卷id
local voucherId = ARGV[1];
--1.2 用户id
local userId = ARGV[2];
--TODO: 1.3 订单id
local orderId = ARGV[3];

--2 数据key
--2.1 库存key
local stockKey = "seckill:stock:" .. voucherId;
--2.2 订单key
local orderKey = "seckill:order:" .. userId;

--3 脚本业务
--3.1 判断库存是否充足
if (tonumber(redis.call("get", stockKey)) <= 0) then
    --库存不足，返回1
    return 1;
end;
--3.2 判断用户是否重复抢购
if (redis.call("sismember", orderKey, userId) == 1) then
    --存在，说明是重复下单，返回2
    return 2;
end;
--3.3 扣减库存
redis.call("incrby", stockKey, -1);
--3.4 下单（保存订单和用户信息）
redis.call("sadd", orderKey, userId);
--TODO: 发送消息给消息队列
redis.call('xadd', 'streams.order', '*', 'userId', userId, 'voucherId', voucherId,'id', orderId);

--4 秒杀成功
return 0;


--提示：带有TODO注释的代码是针对使用Stream消息队列方案的，否则是针对阻塞队列的