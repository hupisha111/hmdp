package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
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
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.DefaultStringRedisConnection;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
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
import java.util.stream.Collector;
import java.util.stream.Collectors;

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
        shopService.saveShop2Redis(1L, -1L);
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

    @Test
    public void saveShopWithGeo2Redis() {
        //需求是把商铺(坐标和商铺id)传入redis.
        //1.查询数据库
        final List<Shop> list = shopService.list();
        //2.依据商铺typeid进行分类,用一个map保存,key是typeid,value就是shop的集合?
        Map <Long,List<Shop>> map = new HashMap<>();
        map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //3.redis的key用typeId拼接好,然后value是location(需要id和坐标信息).
        for (Map.Entry <Long,List<Shop>> entry : map.entrySet()) {
            String key = "shop:geo:" +  entry.getKey();
            List<Shop> shops = entry.getValue();
            for (Shop shop : shops) {
                stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(),shop.getY()), shop.getId().toString());
            }
        }
    }

    /**
     * 慢版(原写法):每攒满 1000 个值同步发一次 PFADD,共 1000 次网络往返。
     * 实测在 Windows + Docker Desktop(WSL2 端口转发)下约 7 分半。
     * 保留用于和下面的管道版做对比。
     */
    @Test
    public void HyperLogTestSerial() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "values_" + i;
            if (j == 999) {
                stringRedisTemplate.opsForHyperLogLog().add("hy2", values);
            }
        }
    }

    /**
     * 管道版:1000 条 PFADD 全部写入管道,一次 flush 完成,只发生一次网络往返。
     * 实测 Redis 服务端处理 100 万个值约 1 秒,因此这里预计数秒内完成。
     * 注意:若把数量提到 1000 万(10000 条命令),建议每 1000~5000 条命令分段
     * executePipelined,避免管道缓冲区过大。
     */
    @Test
    public void HyperLogTest() {
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = new DefaultStringRedisConnection(connection);
            String[] values = new String[1000];
            for (int i = 0; i < 1000000; i++) {
                int j = i % 1000;
                values[j] = "values_" + i;
                if (j == 999) {
                    conn.pfAdd("hy2", values);
                }
            }
            return null;
        });
    }
}
