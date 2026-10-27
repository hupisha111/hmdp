package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.RedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;

    /**
     * 外层方法只负责按用户和优惠券加锁。
     * 不要在此方法上加 @Transactional，否则事务可能在此锁释放后才提交。
     */
    @Override
    public Result buySeckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        //这里我们通过redis分布式锁来串联起来所有的集群.
        RedisLock redisLock = new RedisLock(stringRedisTemplate, "seckillVoucher:" + userId + ":" + voucherId);
        final Boolean isLock = redisLock.tryLock(1200L);
        if(!isLock){
            return Result.fail("该优惠券只能购买一张");
        }
        // 通过当前 Spring 代理再次调用本类的公开事务方法。
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            redisLock.unlock();
        }

    }

    /**
     * 经 Spring 代理调用后，事务会在本方法返回给代理时提交，
     * 然后调用才返回到外层 synchronized 代码块并释放锁。
     */
    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 查询秒杀券并校验活动时间。
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("秒杀券不存在");
        }

        LocalDateTime now = LocalDateTime.now();
        if (voucher.getBeginTime().isAfter(now)) {
            return Result.fail("秒杀活动尚未开启");
        }
        if (voucher.getEndTime().isBefore(now)) {
            return Result.fail("秒杀活动已经结束");
        }

        // 在锁内查重，避免同一 JVM 内同一用户同一券的请求同时通过。
        long count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();
        if (count > 0) {
            return Result.fail("您已购买过该优惠券，不能再次购买");
        }

        // 条件更新本身承担库存 CAS：库存大于 0 才扣减。
        boolean updated = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!updated) {
            return Result.fail("库存不足");
        }

        // 当前表要求显式提供订单 id 和 user_id。
        //TODO 为什么userId一定要默认值?,而且我这里不是传了userId的值吗
        VoucherOrder order = new VoucherOrder();
        order.setId(redisIdWorker.nextId("order"));
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        if (!save(order)) {
            // 抛出运行时异常，让事务回滚已扣减的库存。
            throw new IllegalStateException("创建秒杀订单失败");
        }

        return Result.ok(order.getId());
    }
}
