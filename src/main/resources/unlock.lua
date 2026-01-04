-- 获取锁中的线程id信息 get key
local id = redis.call('get',KEYS[1])
-- 比较线程id信息是否与锁中的一致
if(id == ARGV[1]) then
    return redis.call('del',KEYS[1])
end
return 0