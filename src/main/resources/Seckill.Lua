local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order:" .. voucherId

--1.需要判断库存是否足够,返回1
if (tonumber(redis.call('get', stockKey)) <= 0) then
	return 1
end
--2.需要判断是否已经下单,需要一个set,返回2
if (redis.call('sismember',orderKey, userId) == 1) then
	return 2
end
--3.条件满足,返回0
--3.1扣减库存
redis.call('incrby',stockKey,-1)
--3.2创建set集合(判断有哪些客户已经买了哪些优惠券.)
redis.call('sadd',orderKey,userId)
--4.资格判断没问题,回到把消息放进mq队列,
return 0
