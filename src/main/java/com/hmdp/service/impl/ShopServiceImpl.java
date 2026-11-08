package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    private ExecutorService pool = Executors.newFixedThreadPool(10);

    @Override
    public Result getById(Long id) {
        //第一个版本:缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::queryDB, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //第二个版本:互斥锁实现的缓存击穿
        //return queryWithMutex(id);

        //第三个版本:逻辑过期实现的缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, id, Shop.class, this::queryDB, LOCK_SHOP_KEY, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }
    //考虑了缓存击穿的查询商店版本(逻辑过期)
    private Result queryWithLogicalExpire(Long id) {
        //1.redis查询数据
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //判断数据是否存在
        if(StrUtil.isBlank(shopJson)){
            return Result.fail("店铺信息不存在");
        }
        RedisData<Shop> shop = JSONUtil.toBean(shopJson, RedisData.class);
        //2.比较判断过期时间
        LocalDateTime expireTime = shop.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())) {
            //3.未过期,直接返回
            return Result.ok(shop);
        }
        //4.已过期,尝试获取锁
        String lockKey = LOCK_SHOP_KEY + id;
        Boolean isLock = tryLock(lockKey);
        if(!isLock) {
            //5.获取锁失败,直接返回
            return Result.ok(shop);
        }
        //6.获取锁成功,再次redis查询过期时间
        shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        shop = JSONUtil.toBean(shopJson, RedisData.class);
        expireTime = shop.getExpireTime();
        //7.未过期返回
        if(expireTime.isAfter(LocalDateTime.now())) {
            //3.未过期,直接返回
            return Result.ok(shop);
        }
        //8.过期,线程池调用线程去数据库查询数据,自己返回信息
        pool.submit(() -> {
            try {
                Thread.sleep(200);
                saveShop2Redis(id,20L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                //9.释放锁
                unLock(lockKey);
            }
        });
        return Result.ok(shop);
    }

    //考虑了缓存击穿的查询商店版本(互斥锁)
    private Result queryWithMutex(Long id) {
        //1.先去redis里面查询是否有对应数据
        String shopKey = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);
        //2.存在,返回
        if (StrUtil.isNotBlank(shopJson)) {
            log.info("redis中查询到了shop");
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //3.如果是为空值,返回不存在信息
        else if (shopJson != null) {
            return Result.fail("店铺id不对,信息不存在");
        }
        //4.如果redis没有查询到,那么需要尝试获取锁,去数据库里查询相关信息.
        else {
            //注意锁的key和店铺信息的key不是同一个.
            String lockKey = null;
            Shop shop = null;
            try {
                lockKey = RedisConstants.LOCK_SHOP_KEY + id;
                Boolean isLock = tryLock(lockKey);
                //4.1如果没有拿到锁,休眠,然后重试.
                if (!isLock) {
                    Thread.sleep(50);
                    return queryWithMutex(id);
                }
                //4.2如果拿到锁,要先再次检查redis是否有数据.
                shopJson = stringRedisTemplate.opsForValue().get(shopKey);
                //2.存在,返回
                if (StrUtil.isNotBlank(shopJson)) {
                    log.info("redis中查询到了shop");
                    shop = JSONUtil.toBean(shopJson, Shop.class);
                    return Result.ok(shop);
                }
                //3.如果是为空值,返回不存在信息
                if (shopJson != null) {
                    return Result.fail("店铺id不对,信息不存在");
                }
                //3.店铺不存在于redis,数据库查询
                shop = query().eq("id", id).one();
                //4.不存在,返回报错
                if (shop == null) {
                    stringRedisTemplate.opsForValue().set(shopKey, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                    return Result.fail("店铺id不对,信息不存在");
                }
                //5.存在,redis写入
                stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
                log.info("shop信息写入redis");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }finally {
                //6.释放锁
                //TODO 可以优化的点是:判断只有持有自己的锁的线程才能释放锁,因为有线程失败异常,锁过期后才释放锁等多种情况.
                unLock(lockKey);
            }
            return Result.ok(shop);
        }
    }

    //考虑缓存穿透的查询商店版本
    private Result queryWithPassThrough(Long id){
        //1.先去redis里面查询是否有对应数据
        String key = CACHE_SHOP_KEY + id;
        val shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.存在,返回
        if(StrUtil.isNotBlank(shopJson)){
            log.info("redis中查询到了shop");
            val shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //如果是为空值,返回不存在信息
        if(shopJson != null){
            return Result.fail("店铺id不对,信息不存在");
        }
        //3.店铺不存在于redis,数据库查询
        val shop = query().eq("id", id).one();
        //4.不存在,返回报错
        if(shop == null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return Result.fail("店铺id不对,信息不存在");
        }
        //5.存在,redis写入
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        log.info("shop信息写入redis");
        //6.返回
        return Result.ok(shop);
    }

    @Override
    @Transactional//保证操作的原子性.
    public Result updateShop(Shop shop) {
        //1.更新数据库
        Long id = shop.getId();
        if(id == null){
            return Result.fail("id不能为空");
        }
        updateById(shop);
        //2.删除redis缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.redis查询拿到List<shop>.
        //1.1拼接key和处理current
        String key = SHOP_GEO_KEY + typeId;
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        //1.2.调用redis方法查询.
        final GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(key,
                GeoReference.fromCoordinate(new Point(x, y)),
                new Distance(5000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()
                        .sortAscending()
                        .limit(end)
        );
        //1.3拿到results,弄清楚里面的数据都是什么,如何处理,还要考虑空集的情况.
        if(results == null ){
            return Result.ok(Collections.emptyList());
        }
        final List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        List<Long> shopIdStrList = new ArrayList<>();
        Map<Long,Double> shopIdMap = new HashMap<>();
        list.stream().skip(from).forEach(item -> {
            final RedisGeoCommands.GeoLocation<String> content = item.getContent();
            final String shopIdStr = content.getName();
            final Distance distance = item.getDistance();
            shopIdMap.put(Long.valueOf(shopIdStr), distance.getValue());
            shopIdStrList.add(Long.valueOf(shopIdStr));
        });
        //2.去数据库查询
        final String idStr = StrUtil.join(",", shopIdStrList);
        final List<Shop> shopList = query().in("id", shopIdStrList).last("ORDER BY FIELD(id," + idStr + ")").list();
        //2.1 注意数据类型,注意返回的数据每一个类型和结构都要get到.
        shopList.forEach(shop -> {shop.setDistance(shopIdMap.get(shop.getId()));});
        //3.返回.
        return Result.ok(shopList);
    }

    private Boolean tryLock(String lockKey) {
        //利用redis的setNx尝试拿锁
        Boolean lock = stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(lock);
    }

    private void unLock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

    public void saveShop2Redis(Long id,Long expireTime) {
        Shop shop = query().eq("id", id).one();
        RedisData<Shop> redisData = new RedisData<>();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));

        String shopKey = CACHE_SHOP_KEY + shop.getId();
        stringRedisTemplate.opsForValue().set(shopKey,JSONUtil.toJsonStr(redisData));//逻辑过期的缓存不用设置过期时间
    }

    private Shop queryDB(Long id) {
        Shop shop = query().eq("id", id).one();
        return shop;
    }
}
