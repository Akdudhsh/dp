-- 1.参数
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]
-- 1.3 订单id
local orderId = ARGV[3]

--2.数据key
-- 2.1库存key
local stockKey = "seckill:stock:" .. voucherId
-- 2.2订单key
local orderKey = "seckill:order:" .. userId

--3.脚本业务
--3.1判断库存是否充足
if(tonumber(redis.call("get",stockKey)) <= 0) then
    --说明库存不足，返回1
    return 1
end
-- 3.2判断用户是否已经下单
if (tonumber(redis.call("sismember",orderKey,userId)) == 1) then
    -- 用户已下单，返回2
    return 2
end
-- 3.3扣减库存
redis.call("incrby",stockKey,-1)
-- 3.4保存用户
redis.call("sadd",orderKey,userId)
-- 4 发布消息到队列中
redis.call("xadd","stream.orders","*","userId",userId,"voucherId",voucherId,"id",orderId)
return 0
