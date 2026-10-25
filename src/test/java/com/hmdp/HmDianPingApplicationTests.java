package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RunWith(SpringRunner.class)
@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    private ExecutorService executorService = Executors.newFixedThreadPool(200);

    @Test
    public void testSaveShop(){
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    public void testRedisIdWorker(){
        CountDownLatch latch = new CountDownLatch(100);
        Runnable task = () -> {
            for (int i = 0; i < 300; i ++){
                long id = redisIdWorker.nextId("order");
                System.out.println("id=" + id);
            }
            latch.countDown();
        };
        //我是需要这100个线程全部出动,那么确实应该有个for循环调用线程,然后再来个for循环告诉线程要干300次生成id.
        for(int i=0;i<100;i++){
            executorService.submit(task);
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
