--1.需要判断库存是否足够,返回1
if (tonumber(redis.call('get', KEYS[1])) <= 0) then
	return 1
end
--2.需要判断是否已经下单,需要一个set,返回2
if (redis.call('sismember', KEYS[2], ARGV[1]) == 1) then
	return 2
end
--3.条件满足,返回0
redis.call('incrby',KEYS[1],-1)
redis.call('sadd',KEYS[2],ARGV[1])
return 0
