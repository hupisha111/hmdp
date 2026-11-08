package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import lombok.extern.slf4j.XSlf4j;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;


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

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setResultType(Long.class);//这一步是何意味?何作用?:lua脚本返回0.1.2,这里是告诉java返回值当做什么类型的数据.
        SECKILL_SCRIPT.setLocation(new ClassPathResource("Seckill.Lua"));//这一步是何意味?何作用? :就是加载文件.
    }

    //2.2.4那么什么时候线程池里面的线程去干这个事情呢->阻塞队列里面有任务的时候,那为什么需要一个init函数去干这个活呢?:
    //因为一个init可以在应用一开始就启动这个监听阻塞队列的线程,并准备执行任务.而这个模块是由SpringBoot管理的,所以线程都是进入这个对象进行操作,它们都会把订单放到阻塞队列中,然后都由线程池去处理.

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
        //4.把消息(订单消息,包括userId,voucherId,id)放到mq中.
        final VoucherOrderDTO voucherOrderDTO = new VoucherOrderDTO();
        voucherOrderDTO.setId(orderId);
        voucherOrderDTO.setUserId(userId);
        voucherOrderDTO.setVoucherId(voucherId);
        rabbitTemplate.convertAndSend("order.topic","order",voucherOrderDTO);
        //5.return
        return Result.ok("购买成功,订单id是" + orderId);
    }

    /**
     * 经 Spring 代理调用后，事务会在本方法返回给代理时提交，
     * 然后调用才返回到外层 synchronized 代码块并释放锁。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveOrder2DB(VoucherOrderDTO voucherOrderDTO) {
        //1.保存order数据到db
        final VoucherOrder voucherOrder = new VoucherOrder();
        BeanUtils.copyProperties(voucherOrderDTO,voucherOrder);
        save(voucherOrder);
        log.info("数据插入成功");
        //2.扣减seckillVoucher的stock
        final boolean isSuccess = seckillVoucherServiceImpl.update().
                eq("voucher_id", voucherOrder.getVoucherId()).
                setSql("stock = stock - 1").
                update();
        if(isSuccess){
            log.info("库存扣减成功");
        }
    }
}



