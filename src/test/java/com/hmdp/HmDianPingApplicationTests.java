package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.Voucher;
import com.hmdp.service.impl.SeckillVoucherServiceImpl;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.service.impl.VoucherServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RunWith(SpringRunner.class)
@SpringBootTest
public class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private UserServiceImpl userService;
    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisIdWorker redisIdWorker;
    private ExecutorService executorService = Executors.newFixedThreadPool(200);

    @Test
    public void testSaveShop() {
        shopService.saveShop2Redis(1L, 10L);
    }

    @Test
    public void testRedisIdWorker() {
        CountDownLatch latch = new CountDownLatch(100);
        Runnable task = () -> {
            for (int i = 0; i < 300; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id=" + id);
            }
            latch.countDown();
        };
        //我是需要这100个线程全部出动,那么确实应该有个for循环调用线程,然后再来个for循环告诉线程要干300次生成id.
        for (int i = 0; i < 100; i++) {
            executorService.submit(task);
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /*
    这个测试用来把数据库的seckillvoucherstock信息添加到redis里面
     */
    @Test
    public void saveVoucher2Redis() {
        final SeckillVoucher voucher = seckillVoucherService.query().eq("voucher_id", 10).one();
        stringRedisTemplate.opsForValue().
                set(RedisConstants.SECKILL_STOCK_KEY + voucher.getVoucherId(), voucher.getStock() + "");
    }

    @Test
    public void saveUser2Redis() throws IOException {
        Path csvPath = Paths.get("jmeter-users.csv");

        try (BufferedWriter writer = Files.newBufferedWriter(
                csvPath, StandardCharsets.UTF_8)) {
            //1.取出数据库所有的user
            final List<User> list = userService.query().list();
            //2.然后应该来一个循环,每一次走通获取验证码,然后走登录接口,得到token.然后可以把token都设为不过期.然后如何把token信息导入到jmeter呢?
            for (User user : list) {
                //4.然后是生成token.
                String token = UUID.randomUUID().toString(true);
                UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
                Map<String, Object> userMap = BeanUtil.beanToMap(
                        userDTO,
                        new HashMap<>(),
                        CopyOptions.create()
                                .setIgnoreNullValue(true)
                                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
                );
                String tokenKey = LOGIN_USER_KEY + token;
                stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
                //5.把token信息写入csv
                writer.write(user.getPhone() + "," + token);
                writer.newLine();
            }
        }
    }
}
