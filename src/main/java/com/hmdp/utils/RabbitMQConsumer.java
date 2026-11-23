package com.hmdp.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.RabbitMQConfig;
import com.hmdp.dto.VoucherOrderDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Component
public class RabbitMQConsumer {
    @Resource
    private VoucherOrderServiceImpl voucherOrderService;
    @Resource
    private ObjectMapper objectMapper;

    /** 队列由 RabbitMQConfig 声明（带 x-queue-type=quorum），这里只监听不重复声明，避免 406 */
    @RabbitListener(queues = RabbitMQConfig.ORDER_SAVE_QUEUE)
    public void onOrderSave(VoucherOrderDTO voucherOrderDTO) {
        voucherOrderService.saveOrder2DB(voucherOrderDTO);
    }

    /**
     * 多轮支付检查。参数必须写具体泛型实参 MultiDelayMessage&lt;Long&gt;：
     * Spring AMQP 把方法签名的 ParameterizedType 放进 inferredArgumentType，
     * Jackson 才能把 data 还原成 Long（实测验证过）。
     */
    @RabbitListener(queues = RabbitMQConfig.PAY_CHECK_QUEUE)
    public void onPayCheck(MultiDelayMessage<Long> msg) {
        Long orderId = msg.getData();
        // 1. 先判断数据库订单支付状态
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null) {
            // 订单还没落库或已被清理，不能当成「未支付」
            log.warn("支付检查找不到订单 orderId={}", orderId);
            return;
        }
        Integer status = order.getStatus();
        // 2. 已取消 / 已支付 / 其它终态直接 Return，后续消息不再产生
        if (status == null || status != 1) {
            return;
        }
        log.info("支付检查 orderId={}，还有档位={}", orderId, msg.hasNextDelay());
        // 3. 仍未支付且还有档位 → 调用 sendPayCheck 发下一条延迟消息
        if (msg.hasNextDelay()) {
            voucherOrderService.sendPayCheck(msg);
            return;
        }
        // 3.1 档位耗尽 → 末轮：查渠道 → close → CAS 关单 → 释放四处占用
        voucherOrderService.handleLastRound(orderId);
    }

    /**
     * 死信消费者：DLQ 是消息终点，这里不再产生任何新消息（不重投、不自动重试）。
     * 职责只有两件：① 完整留痕，作为人工处置凭据 ② 按业务状态做一次性幂等补偿。
     * 刻意收原始 Message —— 死信队列混装了两种消息体，用 __TypeId__ 分流。
     */
    @RabbitListener(queues = RabbitMQConfig.DEAD_QUEUE)
    public void onDeadLetter(Message message) {
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        String typeId = String.valueOf(headers.get("__TypeId__"));
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        // 一条日志给足人工处置需要的全部信息：来源、原因、死信历史、原始 body
        log.warn("死信留痕 typeId={}，原队列={}，原因={}，原exchange={}，x-death={}，body={}",
                typeId,
                headers.get("x-first-death-queue"),
                headers.get("x-first-death-reason"),
                headers.get("x-first-death-exchange"),
                headers.get("x-death"),
                body);
        try {
            if (typeId.contains("MultiDelayMessage")) {
                // 只取 data（orderId），忽略 delayTimes 和老消息里残留的 delayTime
                JsonNode node = objectMapper.readTree(body);
                long orderId = node.path("data").asLong();
                voucherOrderService.compensatePayCheckFromDead(orderId);
            } else if (typeId.contains("VoucherOrderDTO")) {
                VoucherOrderDTO dto = objectMapper.readValue(body, VoucherOrderDTO.class);
                voucherOrderService.compensateOrderSaveFromDead(dto);
            } else {
                // 不做业务补偿，仅留痕；新消息类型必须在这里显式加分支
                log.error("未知死信类型，等待人工处理 typeId={}", typeId);
            }
        } catch (Exception e) {
            /*
             * 死信队列没有配 DLX，抛异常会 reject 且不回队（消息直接丢），
             * 而且这个消费者同样吃 retry.max-attempts=3。所以不抛，
             * 上面的 WARN 已留下完整 body，这里只补到堆栈，正常返回让容器 ack。
             * 生产环境：把留痕改成落 tb_dead_letter 表 + 告警 + 人工重放入口。
             */
            log.error("死信补偿失败，消息将被确认并移出队列，可凭上方 body 人工处理", e);
        }
    }
}
