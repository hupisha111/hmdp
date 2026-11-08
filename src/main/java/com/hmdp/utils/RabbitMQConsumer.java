package com.hmdp.utils;


import com.hmdp.dto.VoucherOrderDTO;
import com.hmdp.service.impl.VoucherOrderServiceImpl;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class RabbitMQConsumer {
    @Resource
    private VoucherOrderServiceImpl voucherOrderService;
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "order.queue",durable = "true"),
            exchange = @Exchange(name = "order.topic", type = "topic"),
            key = "order"
    ))
    private void rabbitMqConsumer(VoucherOrderDTO voucherOrderDTO) {
        //这里已经在监听mq的消息,消息是voucherOrderDTO.然后需要去处理插入数据库的活.
        voucherOrderService.saveOrder2DB(voucherOrderDTO);
    }
}
