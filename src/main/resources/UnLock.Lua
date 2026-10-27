--判断锁是否相等和删除锁一起做
if(redis.call('get',Keys[1]) == ARGV[1])then
	return redis.call('del', KEYS[1])
end
return 0