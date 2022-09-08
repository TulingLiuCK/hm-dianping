-- 1参数列表
-- 1.1优惠券ID
local voucherId = ARGV[1];
-- 1.2用户ID
local userId = ARGV[2]
-- 1.2订单ID
local orderId = ARGV[3]

-- 2 数据key
-- 2.1 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单keu
local orderKey = 'seckill:order:' .. voucherId

-- 3脚本业务
-- 3。1 判断库存是否重组
if(tonumber(redis.call('get',stockKey)) <= 0) then
    --3.2 库存不足 返回1
    return 1
end
--3.2判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember',orderKey,userId) == 1) then
    --3.3存在说明重复下单
    return 2
end
-- 3.4 扣库存、 incrby stockKey -1
redis.call('incrby',stockKey,-1)
-- 3.5 下单（保存用户） sadd orderKey userId
redis.call('sadd',orderKey,userId)
--3.6发送消息到队列中 XADD stream.orders * k1 v1 k2 v2 ...
redis.call('xadd','stream.orders','*',"userId",userId,'voucherId',voucherId,'id',orderId)
return 0


