package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderDTO;
import com.hmdp.entity.VoucherOrder;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result buySeckillVoucher(Long voucherId);

    void saveOrder2DB(VoucherOrderDTO voucherOrderDTO);

    /** 支付渠道回调入口：本地状态流转已实现，渠道验签见 Controller 的 TODO */
    Result onPaySuccess(Long orderId);
}
