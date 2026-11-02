package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;

    private String queueName = "stream.orders";

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);//这一步是何意味?何作用?:lua脚本返回0.1.2,这里是告诉java返回值当做什么类型的数据.
        SECKILL_SCRIPT.setLocation(new ClassPathResource("Seckill.Lua"));//这一步是何意味?何作用? :就是加载文件.
    }

    private ExecutorService executorService = Executors.newFixedThreadPool(5);

    @PostConstruct
    public void init() {
        executorService.submit(new OrderExecutor());
    }

    private class OrderExecutor implements Runnable {
        @Override
        public void run() {

            while (true) {
                try{
                    //1.监听redis的消息队列.
                    final List<MapRecord<String, Object, Object>> orderList = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断是否获得消息.//3.没有的话就重试,循环.
                    if (orderList == null || orderList.isEmpty()) {
                        continue;
                    }
                    //4.有的话就执行数据库操作.提取出order.

                        final VoucherOrder order = new VoucherOrder();
                        final MapRecord<String, Object, Object> map = orderList.get(0);
                        final Map<Object, Object> values = map.getValue();
                        BeanUtil.fillBeanWithMap(values, order, true);
                        save(order);
                        //5.成功了就ACK确认,不成功就进入处理已消费未确认的pending-list.
                        stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",map.getId());
                } catch (Exception e) {
                    log.error("订单处理出现异常,消费者需要重新处理该订单",e);
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (true) {
            //1.监听redis的pending-list队列.
            final List<MapRecord<String, Object, Object>> orderList = stringRedisTemplate.opsForStream().read(
                    Consumer.from("g1", "c1"),
                    StreamReadOptions.empty().count(1),
                    StreamOffset.create(queueName, ReadOffset.from("0"))
            );
            //2.判断是否有未确认的订单消息需要处理,没有的话就break.因为有的话,上游线程会再调用这个函数.
            if (orderList == null || orderList.isEmpty()) {
                break;
            }
            //3.有的话就执行数据库操作.提取出order.
            try {
                final VoucherOrder order = new VoucherOrder();
                final MapRecord<String, Object, Object> map = orderList.get(0);
                final Map<Object, Object> values = map.getValue();
                BeanUtil.fillBeanWithMap(values, order, true);
                save(order);
                //5.成功了就ACK确认,不成功就进入处理已消费未确认的pending-list.
                stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", map.getId());
            } catch (Exception e) {
                log.error("订单处理出现异常,消费者需要重新处理该订单", e);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }


    //2.2.4那么什么时候线程池里面的线程去干这个事情呢->阻塞队列里面有任务的时候,那为什么需要一个init函数去干这个活呢?:
    //因为一个init可以在应用一开始就启动这个监听阻塞队列的线程,并准备执行任务.而这个模块是由springboot管理的,所以线程都是进入这个对象进行操作,它们都会把订单放到阻塞队列中,然后都由线程池去处理.

    /**
     * 外层方法只负责按用户和优惠券加锁。
     * 不要在此方法上加 @Transactional，否则事务可能在此锁释放后才提交。
     */
    @Override
    public Result buySeckillVoucher(Long voucherId) {
        //1.应该是把创建订单需要的key-value传给Lua脚本
        //1.1获取userId
        final Long userId = UserHolder.getUser().getId();
        //1.2获取orderId
        final Long orderId = redisIdWorker.nextId("order");
        //2.执行Lua脚本,得到返回值
        final Long execute = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),userId.toString(),orderId.toString());
        //3.依据Lua脚本返回值进行业务逻辑.
        //3.1如果判断不成功.
        if(execute > 0){
            return execute == 1 ?  Result.fail("库存不足") : Result.fail("不可重复购买");
        }
        //4.判断成功之后,需要去执行消费者线程.貌似这里不需要代码了吧.因为消费者会一直监听消息队列并执行工作吧.
        //5.无需等待线程池完成操作,立刻返回.
        return Result.ok("购买成功,订单id是" + orderId);
    }

    /**
     * 经 Spring 代理调用后，事务会在本方法返回给代理时提交，
     * 然后调用才返回到外层 synchronized 代码块并释放锁。
     */
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        if (!save(voucherOrder)) {
            // 抛出运行时异常，让事务回滚已扣减的库存。
            throw new IllegalStateException("创建秒杀订单失败");
        }
    }
}



