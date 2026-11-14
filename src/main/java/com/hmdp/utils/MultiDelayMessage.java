package com.hmdp.utils;

import cn.hutool.core.collection.CollUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

//这个类是用来多次发送延迟消息的(多轮).所以它需要一个装延迟时间的集合,然后它应该继承或者实现Message吧..
//因为发送的是它啊,我需要它既有Message这个类的性质,又要存储延迟时间.
//还是说我直接给这个类的字段里放一个Message类就完事?
//在converterandsent里是把string字符串变成了Message吧.
//所以我这里只需要存放任意形式的消息实体,然后放到convertandset里边自然会变成Message?
//不应该变成Bean放到容器里吧.因为这个类本质就是Message,不同的线程当然是自己创建自己的Message啊.
//
// 结论：以上都不需要。它就是普通业务 DTO（信纸），Message 是框架造的信封。
// @NoArgsConstructor 是给消费端 Jackson 反序列化用的：没有它，消息发得出去但收不回来。

/**
 * 通用多轮延迟消息体，T 是业务载荷类型（任意类型）。
 * 实测结论：消费端 @RabbitListener 方法参数必须写具体泛型实参
 * （如 MultiDelayMessage&lt;Long&gt;），Spring AMQP 才会把 inferredArgumentType
 * 的完整 ParameterizedType 交给 Jackson，data 才能还原成原类型；
 * 写原始类型 / &lt;?&gt; / &lt;Object&gt; 都会退化成 LinkedHashMap。
 */
@Data
@NoArgsConstructor
// 兼容改名之前就已躺在队列里的老消息：它们的 JSON 多带一个 "delayTime" 字段，反序列化时忽略即可
public class MultiDelayMessage<T> {
    private T data;
    private List<Long> delayTimes;

    public MultiDelayMessage(T data, List<Long> delayTimes) {
        this.data = data;
        this.delayTimes = delayTimes;
    }

    // Long... 是可变参数，编译后就是 Long[]；newArrayList 转成可变 List，remove(0) 才不会抛异常
    public MultiDelayMessage(T data, Long... delayTimes) {
        this.data = data;
        this.delayTimes = CollUtil.newArrayList(delayTimes);
    }

    /**
     * 取走本轮延迟，剩余档位随消息体带到下一轮。
     *
     * 这个方法有副作用，绝对不能命名成 getXxx / isXxx。
     * Jackson 按 JavaBean 规范把 getXxx / isXxx 当属性 getter：原来叫 getDelayTime() 时，
     * 它被当成属性 "delayTime" 序列化，于是每次 convertAndSend 都会额外多调一次
     * delayTimes.remove(0)。前几轮侥幸正常（JSON 里 delayTimes 的快照写在这次额外
     * remove 之前），最后一轮 list 已空，额外调用直接抛 IndexOutOfBoundsException，
     * 消息发不出去 -> listener 抛异常 -> 本地重试耗尽 -> reject 进死信 -> 末轮关单永不执行。
     */
    public Long removeDelayTime() {
        if (!hasNextDelay()) {
            throw new IllegalStateException("延迟档位已耗尽，取档前必须先判断 hasNextDelay()");
        }
        return delayTimes.remove(0);
    }

    /** 档位耗尽即末轮，必须有这个出口，否则 remove(0) 会抛异常 */
    @JsonIgnore
    public boolean hasNextDelay() {
        return delayTimes != null && !delayTimes.isEmpty();
    }
}
