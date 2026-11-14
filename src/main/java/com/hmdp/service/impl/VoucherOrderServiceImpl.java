package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.PayGateway;
import com.hmdp.utils.MultiDelayMessage;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {
    @Resource
    private SeckillVoucherServiceImpl seckillVoucherServiceImpl;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RabbitTemplate rabbitTemplate;
    @Resource
    private PayGateway payGateway;
    @Resource
    private OrderCancelService orderCancelService;

    /** 订单状态：1 未支付 / 2 已支付 / 4 已取消 */
    private static final int STATUS_UNPAID = 1;
    private static final int STATUS_PAID = 2;
    private static final int STATUS_CANCELLED = 4;

    /** 扫表兜底：支付窗口必须大于 10+20+30=60s 的正常链路，给 MQ 延迟和死信补偿留余量 */
    private static final long PAY_WINDOW_SECONDS = 120L;
    private static final int SCAN_BATCH_SIZE = 100;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        // setResultType：Lua 返回整数，告诉 RedisTemplate 转成 Java Long
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);
        SECKILL_SCRIPT.setLocation(new ClassPathResource("Seckill.Lua"));
    }

    @Override
    public Result buySeckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(), voucherId.toString(), userId.toString(), orderId.toString());
        if (result > 0) {
            return result == 1 ? Result.fail("库存不足") : Result.fail("不可重复购买");
        }
        VoucherOrderDTO dto = new VoucherOrderDTO();
        dto.setId(orderId);
        dto.setUserId(userId);
        dto.setVoucherId(voucherId);
        // 改动1（已确认）：落库消息立即投递，不设延迟；延迟只用于支付状态检查
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE,
                RabbitMQConfig.ORDER_SAVE_ROUTING_KEY, dto);
        return Result.ok("购买成功,订单id是" + orderId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveOrder2DB(VoucherOrderDTO dto) {
        VoucherOrder order = new VoucherOrder();
        BeanUtils.copyProperties(dto, order);
        order.setStatus(STATUS_UNPAID);
        order.setCreateTime(LocalDateTime.now());
        order.setUpdateTime(LocalDateTime.now());
        save(order);
        log.info("订单落库成功 orderId={}", order.getId());

        // 沿用原有逻辑：落库时扣减 DB 库存，超时关单时会加回来
        seckillVoucherServiceImpl.update()
                .eq("voucher_id", order.getVoucherId())
                .setSql("stock = stock - 1")
                .update();

        // 疑问2 答案：afterCommit 在事务「提交成功后」才执行，
        // 事务回滚时消息不会发出，消费者也不会读到未提交的行。
        MultiDelayMessage<Long> msg = new MultiDelayMessage<>(dto.getId(), 10_000L, 20_000L, 30_000L);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    sendPayCheck(msg);
                } catch (Exception e) {
                    // 事务已提交，异常不会回滚；必须记录，否则消息丢了没人知道，
                    // 只能靠 TODO 的定时扫表兜底
                    log.error("首条支付检查消息发送失败 orderId={}", msg.getData(), e);
                }
            }
        });
    }

    /** 生产端：取走本轮档位、剩余档位留在消息体；档位耗尽则进入末轮 */
    public void sendPayCheck(MultiDelayMessage<Long> msg) {
        if (!msg.hasNextDelay()) {
            handleLastRound(msg.getData());
            return;
        }
        long delayMillis = msg.removeDelayTime();
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE, RabbitMQConfig.PAY_CHECK_ROUTING_KEY, msg,
                message -> {
                    message.getMessageProperties().setHeader(
                            RabbitMQConfig.DELIVERY_TIME_HEADER,
                            System.currentTimeMillis() + delayMillis);
                    return message;
                });
    }

    /**
     * 死信补偿（支付检查链断掉）：DLQ 是消息终点，这里不再产生任何新消息，
     * 直接按 orderId 走一次末轮结算（查渠道 → 补单 / 关单），把订单推到终态。
     *
     * 为什么不重投：消息进 DLQ 说明 Spring 本地重试(3 次) 和 quorum 队列的
     * delivery-limit 都已经判过死刑；再发一条新消息等于在死信队列里又做一遍重试，
     * 只会带来循环、计数和幂等负担，还会掩盖根因（比如当初那个 Jackson getter bug）。
     * 一次能收敛是因为所有动作都幂等：markPaid / cancelInDb 是 CAS，
     * rollbackRedis 是 Lua SREM；handleLastRound 内部还会按 status 再判一次。
     */
    public void compensatePayCheckFromDead(Long orderId) {
        log.warn("死信补偿：一次性结算订单 orderId={}", orderId);
        handleLastRound(orderId);
    }

    /**
     * 死信补偿（落库消息）：Lua 已经扣了 Redis 库存 + 一人一单标记。
     * 订单真没落库就只归还 Redis（Lua 的 SREM 做 CAS，重复执行幂等）；
     * 订单其实在库里，说明只是后续步骤失败，直接转一次性结算。
     */
    public void compensateOrderSaveFromDead(VoucherOrderDTO dto) {
        VoucherOrder order = getById(dto.getId());
        if (order != null) {
            log.warn("死信补偿：订单已落库，转一次性结算 orderId={}", dto.getId());
            compensatePayCheckFromDead(dto.getId());
            return;
        }
        VoucherOrder snapshot = new VoucherOrder();
        snapshot.setId(dto.getId());
        snapshot.setVoucherId(dto.getVoucherId());
        snapshot.setUserId(dto.getUserId());
        orderCancelService.rollbackRedis(snapshot);
        log.warn("死信补偿：订单未落库，已归还 Redis 占用 orderId={}", dto.getId());
    }

    /** 末轮结算：二次查单 → 已付补单 / 未付关单。按 orderId 驱动，不依赖消息体 */
    public void handleLastRound(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null || !Integer.valueOf(STATUS_UNPAID).equals(order.getStatus())) {
            return;
        }
        String state = payGateway.query(orderId);       // TODO 真实渠道查单
        if (PayGateway.SUCCESS.equals(state)) {
            if (markPaid(orderId)) {
                log.info("渠道已付款但回调丢失，补单成功 orderId={}", orderId);
            }
            return;
        }
        if (PayGateway.PAYING.equals(state)) {
            // 渠道仍在处理中：不关单，等业务状态驱动的定时扫表捞回，不再派生新消息。
            log.info("渠道支付处理中，本轮不关单 orderId={}", orderId);
            return;
        }
        // NOTPAY：先封渠道入口，再动本地状态，顺序不能反
        if (!payGateway.close(orderId)) {               // TODO 真实 close
            log.warn("渠道关单失败，暂不关本地订单 orderId={}", orderId);
            return;
        }
        cancelOrder(order);
    }

    /** 支付渠道回调入口：CAS 1→2；重复回调或订单已关则走退款补偿 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result onPaySuccess(Long orderId) {
        if (!markPaid(orderId)) {
            log.warn("回调到达但订单不是待支付，走退款补偿 orderId={}", orderId);
            payGateway.refund(orderId);                 // TODO 真实退款
            return Result.fail("订单状态异常，已走退款补偿");
        }
        // 履约：订单生效 / 发券码 / 允许核销（业务侧 TODO）
        log.info("支付成功，订单生效 orderId={}", orderId);
        return Result.ok("支付成功");
    }

    /** CAS 改状态：只有影响行数为 1 的一方赢，回调与超时赛跑时靠它裁决 */
    private boolean markPaid(Long orderId) {
        return lambdaUpdate()
                .eq(VoucherOrder::getId, orderId)
                .eq(VoucherOrder::getStatus, STATUS_UNPAID)
                .set(VoucherOrder::getStatus, STATUS_PAID)
                .set(VoucherOrder::getPayTime, LocalDateTime.now())
                .set(VoucherOrder::getUpdateTime, LocalDateTime.now())
                .update();
    }

    /**
     * 关单：DB 两步交给独立 bean 的事务；Redis 归还「无论 CAS 输赢」都补做。
     * 这样本地重试 / 死信补偿重复到达时，即使 CAS 输了，Redis 那半边也有机会被补完，
     * 不会出现「状态改了但库存没还、且再也没人重试」的死局。
     */
    private void cancelOrder(VoucherOrder order) {
        boolean won = orderCancelService.cancelInDb(order);
        VoucherOrder now = getById(order.getId());
        if (now != null && Integer.valueOf(STATUS_CANCELLED).equals(now.getStatus())) {
            orderCancelService.rollbackRedis(order);   // Lua 幂等，重复执行不会多加库存
        }
        log.info("超时关单处理完成 orderId={}，本轮 CAS 获胜={}", order.getId(), won);
    }

    /**
     * 定时扫表兜底：只按「业务状态」驱动，不依赖任何消息还在不在，
     * 所以能覆盖死信也够不到的故障域（消息没发出 / 丢了 / 消费者挂了 / 补偿也失败）。
     *
     * fixedDelay 而不是 fixedRate：上一轮跑完再等 60s，单机天然不会重叠；
     * 阈值 120s 必须大于正常链路 10+20+30=60s，避免抢在 MQ 前面提前关单。
     * 单条异常不影响整批；下轮还会再捞到没处理的订单（状态没变就还在）。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void scanPendingOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(PAY_WINDOW_SECONDS);
        List<VoucherOrder> orders = lambdaQuery()
                .eq(VoucherOrder::getStatus, STATUS_UNPAID)
                .lt(VoucherOrder::getCreateTime, cutoff)
                .last("limit " + SCAN_BATCH_SIZE)
                .list();
        int handled = 0, failed = 0;
        for (VoucherOrder order : orders) {
            try {
                handleLastRound(order.getId());   // 复用现成的幂等末轮结算
                handled++;
            } catch (Exception e) {
                failed++;
                log.error("扫表补偿失败 orderId={}", order.getId(), e);
            }
        }
        log.info("扫表完成 扫描={}，已处理={}，失败={}", orders.size(), handled, failed);
        // 心跳：外部监控这个 key 的年龄，几分钟没更新就说明「扫表停了」
        stringRedisTemplate.opsForValue().set("hmdp:scan:last-success",
                String.valueOf(System.currentTimeMillis()));
    }
}
