package com.hmdp.service.impl;

import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.val;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public List<ShopType> queryTypeList() {
        //1.redis里查询list<shoptype>这个数据是否存在.
        String key = "shop:type:list";
        //TODO 疑问:redis的list数据类型是指value存了一个list吗,是list<json>吗,怎么表示一个list的.
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range(key, 0, -1);
        //2.存在,返回
        if (shopTypeJson != null && !shopTypeJson.isEmpty()) {
            List<ShopType> shopType = new ArrayList<>();
            for(String json : shopTypeJson) {
                shopType.add(JSONUtil.toBean(json, ShopType.class));
            }
            return shopType;
        }

        //3.不存在,查询数据库
        List<ShopType> shopType = query().list();
        //4.不存在,报错
        if(shopType == null || shopType.isEmpty()) {
            //TODO 这个函数返回一个list,如果查不到数据要怎么返回错误信息,抛出异常,怎么写
        }
        //5.存在,存入redis,返回.
        for(ShopType shop : shopType) {
            stringRedisTemplate.opsForList().rightPush(key, JSONUtil.toJsonStr(shop));
        }
        return shopType;
    }
}
