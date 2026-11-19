local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order:" .. voucherId

-- CAS：SREM 返回 1 表示「这次是我删掉的」，即第一次回滚，才归还库存；
--      返回 0 表示已删过（重复消息 / 本地重试），直接退出，避免库存多加。
-- 整个脚本在 Redis 内原子执行，所以检查 + 删除 + 归还不会被其它命令插队。
local removed = redis.call('srem', orderKey, userId)
if removed == 0 then
    return 1
end

redis.call('incrby', stockKey, 1)
return 0
