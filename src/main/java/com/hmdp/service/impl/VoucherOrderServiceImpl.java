package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);//这一步是何意味?何作用?:lua脚本返回0.1.2,这里是告诉java返回值当做什么类型的数据.
        SECKILL_SCRIPT.setLocation(new ClassPathResource("Seckill.Lua"));//这一步是何意味?何作用? :就是加载文件.
    }

    @PostConstruct
    public void init() {
        executorService.submit(new OrderExecutor());
    }

    //2.2.1创建一个阻塞队列去存储订单.
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //2.2.2定义一个线程池,由线程池里的线程去干任务.
    private ExecutorService executorService =  Executors.newSingleThreadExecutor();
    //2.2.3定义一个任务orderTask,它里面写好如何去干后面的活的逻辑,runnable是个类.
    private class OrderExecutor implements Runnable {
        @Override
        public void run() {
            while (true) {
                //从阻塞队列中取出任务
                try {
                    final VoucherOrder order = orderTasks.take();
                    //操作数据库.
                    createVoucherOrder(order);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
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
        //1.通过Lua脚本去做能否下单的判断
        //1.1需要去执行Lua脚本,需要Lua脚本,参数
        final Long userId = UserHolder.getUser().getId();
        List<String> Keys = new ArrayList<>();
        Keys.add(SECKILL_STOCK_KEY + voucherId);
        Keys.add(SECKILL_ORDER_KEY + voucherId);
        final Long execute = stringRedisTemplate.execute(SECKILL_SCRIPT, Keys, userId.toString());
        //1.2判断返回值
        if(execute > 0){
            return execute == 1 ?  Result.fail("库存不足") : Result.fail("不可重复购买");
        }
        //2.1首先是创建订单(从thread里面拿userId).然后得到一个voucherOrder.
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(userId);
        voucherOrder.setId(redisIdWorker.nextId("order"));
        orderTasks.offer(voucherOrder);
        //2.3无需等待线程池完成操作,立刻返回.
        return Result.ok("购买成功,订单id是" + voucherOrder.getId());
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



