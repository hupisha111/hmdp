package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {
     //查询店铺信息
     Result getById(Long id);
     //更新店铺
     Result updateShop(Shop shop);
     //依据typeId查询店铺
     Result queryShopByType(Integer typeId, Integer current, Double x,Double y);
}
