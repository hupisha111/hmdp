package com.hmdp.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 4.4 原生延迟投递：队列声明为 quorum queue，发布时给消息设置
 * x-opt-delivery-time（未来 Unix 毫秒时间戳，必须是 Long）。
 * 消息立即入队并复制，只是到点才对消费者可见，不需要 TTL、插件或延迟交换机。
 *
 * 队列参数（x-queue-type、x-dead-letter-exchange 等）在声明时固定、不可变更，
 * 改了同名队列会触发 406 PRECONDITION_FAILED，所以本次加死信参数时
 * 两个主队列都换成了 .v2 新名字，旧队列可留着或自行在管理台清理。
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "hmdp.exchange";

    public static final String ORDER_SAVE_QUEUE = "hmdp.order.save.queue.v2";
    public static final String ORDER_SAVE_ROUTING_KEY = "order.save";

    public static final String PAY_CHECK_QUEUE = "hmdp.pay.check.queue.v2";
    public static final String PAY_CHECK_ROUTING_KEY = "pay.check";

    /** RabbitMQ 4.4 原生延迟头，值为未来时间戳毫秒（绝对时间），不是相对时长 */
    public static final String DELIVERY_TIME_HEADER = "x-opt-delivery-time";

    /** 死信：本地重试耗尽后由 reject(requeue=false) 投递到这里 */
    public static final String DLX_EXCHANGE = "hmdp.dlx.exchange";
    public static final String DEAD_QUEUE = "hmdp.dead.queue";
    public static final String DEAD_ROUTING_KEY = "dead";

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange hmdpExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(dlxExchange()).with(DEAD_ROUTING_KEY);
    }

    /** 落库队列：立即投递、有消费者 */
    @Bean
    public Queue orderSaveQueue() {
        return QueueBuilder.durable(ORDER_SAVE_QUEUE)
                .withArgument("x-queue-type", "quorum")
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding orderSaveBinding() {
        return BindingBuilder.bind(orderSaveQueue()).to(hmdpExchange()).with(ORDER_SAVE_ROUTING_KEY);
    }

    /** 支付状态检查队列：多轮延迟消息的落点，延迟头只在 quorum queue 生效 */
    @Bean
    public Queue payCheckQueue() {
        return QueueBuilder.durable(PAY_CHECK_QUEUE)
                // 疑问1 答案：x-queue-type=quorum 把队列声明成 quorum queue，
                // 4.4 的 x-opt-delivery-time 只在 quorum queue 上有效。
                .withArgument("x-queue-type", "quorum")
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding payCheckBinding() {
        return BindingBuilder.bind(payCheckQueue()).to(hmdpExchange()).with(PAY_CHECK_ROUTING_KEY);
    }
}
