package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;

/**
 * 关单的 DB 侧与 Redis 侧拆开：
 * - DB 两步放同一个本地事务，任一步失败整体回滚，status 回到 1，
 *   消费者本地重试时 CAS 能重新赢，不会留下「状态改了但库存没还」的半成品。
 * - Redis 不在事务里，靠 Lua 里的 SREM 返回值做 CAS 保证幂等，可重复补做。
 *
 * 独立 bean 是为了让 cancelInDb 被 VoucherOrderServiceImpl 跨类调用，
 * 一定走 AOP 代理，@Transactional 才生效。
 */
@Slf4j
@Service
public class OrderCancelService extends ServiceImpl<VoucherOrderMapper, VoucherOrder> {

    @Resource
    private SeckillVoucherServiceImpl seckillVoucherServiceImpl;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final int STATUS_UNPAID = 1;
    private static final int STATUS_CANCELLED = 4;

    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT;

    static {
        ROLLBACK_SCRIPT = new DefaultRedisScript<>();
        ROLLBACK_SCRIPT.setResultType(Long.class);
        ROLLBACK_SCRIPT.setLocation(new ClassPathResource("RollbackSeckill.Lua"));
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean cancelInDb(VoucherOrder order) {
        boolean won = lambdaUpdate()
                .eq(VoucherOrder::getId, order.getId())
                .eq(VoucherOrder::getStatus, STATUS_UNPAID)
                .set(VoucherOrder::getStatus, STATUS_CANCELLED)
                .set(VoucherOrder::getUpdateTime, LocalDateTime.now())
                .update();
        if (!won) {
            return false;
        }
        seckillVoucherServiceImpl.update()
                .eq("voucher_id", order.getVoucherId())
                .setSql("stock = stock + 1")
                .update();
        return true;
    }

    /** 幂等：脚本返回 0=本次归还，1=已归还过（重复调用） */
    public void rollbackRedis(VoucherOrder order) {
        Long result = stringRedisTemplate.execute(ROLLBACK_SCRIPT, Collections.emptyList(),
                order.getVoucherId().toString(), order.getUserId().toString());
        log.info("Redis 归还 orderId={}，result={}（0=已归还，1=重复调用）", order.getId(), result);
    }
}
